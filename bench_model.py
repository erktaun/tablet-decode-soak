#!/usr/bin/env python3
"""Experiment 2: how fast does the detector run on the device, and on which processor?

Runs TensorFlow Lite's own benchmark tool (prebuilt/benchmark_model) over adb for each model, input size,
precision and processor:
  cpu1, cpu2, cpu4  CPU (XNNPACK) with 1, 2 or 4 threads
  gpu               GPU delegate (FP16 arithmetic)
  nnapi             Android NNAPI, which hands the model to the vendor's NPU/DSP driver if there is one

Each config runs for about 15 s after a warm-up. Then, optionally:
  --under-load   repeats the fastest configs while the soak app decodes 4 streams from local clips
                 (run_test.py --mode local), so decoding and detection compete as they would in a store
  --sustained S  runs the fastest config for S seconds in a row and logs speed and temperature over time

Results: results/<run_id>/ with summary.md, bench.jsonl and every raw log.

Examples:
  python3 bench_model.py --quick                  # 320 and 640, the 3 models, cpu4 + gpu + nnapi; ~15 min
  python3 bench_model.py                          # the full matrix; ~25 min
  python3 bench_model.py --quick --under-load --sustained 300
  python3 bench_model.py --models yolo11n --sizes 640 --precisions int8 --delegates cpu2 gpu
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

from run_test import DEV_FILES, PKG, ROOT, Adb, log

MODELS_DIR = ROOT / "models"
MODELS_URL = "https://github.com/erktaun/tablet-decode-soak/releases/download/models-v1/"
BENCH = ROOT / "prebuilt" / "benchmark_model"
DEV = "/data/local/tmp/ald_bench"
DELEGATES = {
    "cpu1": ["--num_threads=1"],
    "cpu2": ["--num_threads=2"],
    "cpu4": ["--num_threads=4"],
    "gpu": ["--use_gpu=true", "--gpu_precision_loss_allowed=true"],
    "nnapi": ["--use_nnapi=true", "--nnapi_allow_fp16=true", "--disable_nnapi_cpu=true"],
}
# Draft workload from the feasibility plan: detections per second the store needs, all cameras together.
WORKLOAD = {"order assembly": 5, "order box": 5, "queue": 1, "tables": 1}


# ---------------------------------------------------------------- models

def ensure_models(names: list[str]) -> list[Path]:
    manifest = json.loads((MODELS_DIR / "manifest.json").read_text())
    out = []
    for n in names:
        p = MODELS_DIR / n
        want = manifest[n]["sha256"]
        if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest() != want:
            log(f"downloading {n} ({manifest[n]['bytes'] / 1e6:.1f} MB)")
            tmp = p.with_suffix(".part")
            urllib.request.urlretrieve(MODELS_URL + n, tmp)
            if hashlib.sha256(tmp.read_bytes()).hexdigest() != want:
                tmp.unlink()
                sys.exit(f"{n}: checksum mismatch after download")
            tmp.replace(p)
        out.append(p)
    return out


# ---------------------------------------------------------------- device

def device_info(adb: Adb) -> dict:
    gles = re.search(r"GLES: (.+)", adb.shell("dumpsys SurfaceFlinger", check=False))
    cpus = adb.shell("for c in /sys/devices/system/cpu/cpu[0-9]*; do "
                     "echo $(basename $c) $(cat $c/cpufreq/cpuinfo_max_freq 2>/dev/null); done", check=False)
    return {
        "manufacturer": adb.prop("ro.product.manufacturer"), "model": adb.prop("ro.product.model"),
        "soc": adb.prop("ro.soc.model") or adb.prop("ro.board.platform"), "android": adb.prop("ro.build.version.release"),
        "gpu": gles.group(1).split(", OpenGL")[0].strip() if gles else None,
        "cpu_max_khz": {l.split()[0]: int(l.split()[1]) for l in cpus.splitlines() if len(l.split()) == 2},
        "ram_kb": int(re.search(r"MemTotal:\s+(\d+)", adb.shell("cat /proc/meminfo")).group(1)),
    }


def thermal(adb: Adb) -> dict:
    status = re.search(r"Thermal Status: (\d+)", adb.shell("dumpsys thermalservice", check=False))
    batt = re.search(r"temperature: (\d+)", adb.shell("dumpsys battery", check=False))
    temps = [int(t) for t in adb.shell("cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null", check=False).split()
             if t.lstrip("-").isdigit()]
    temps = [t / 1000 if t > 1000 else t for t in temps]
    return {"thermal_status": int(status.group(1)) if status else None,
            "battery_c": int(batt.group(1)) / 10 if batt else None,
            "zone_max_c": max((t for t in temps if t < 150), default=None)}


def parse(text: str) -> dict:
    r: dict = {}
    m = re.search(r"Inference timings in us: Init: ([\d.]+), First inference: ([\d.]+), "
                  r"Warmup \(avg\): ([\d.e+]+), Inference \(avg\): ([\d.e+]+)", text)
    if m:
        r.update(init_ms=float(m[1]) / 1e3, first_ms=float(m[2]) / 1e3, avg_ms=float(m[4]) / 1e3)
        r["fps"] = round(1000 / r["avg_ms"], 1)
    runs = re.findall(r"count=(\d+) first=\d+ curr=\d+ min=(\d+) max=(\d+) avg=([\d.e+]+) std=(\d+)"
                      r"(?: p5=\d+ median=(\d+) p95=(\d+))?", text)
    if runs:
        n, lo, hi, _, std, med, p95 = runs[-1]
        r.update(runs=int(n), min_ms=int(lo) / 1e3, max_ms=int(hi) / 1e3, std_ms=int(std) / 1e3)
        if med:
            r.update(median_ms=int(med) / 1e3, p95_ms=int(p95) / 1e3)
    d = re.findall(r"Replacing (\d+) out of (\d+) node\(s\) with delegate \((\w+)\) node, yielding (\d+) partitions", text)
    if d:
        r["delegated"] = {"nodes": int(d[-1][0]), "of": int(d[-1][1]), "delegate": d[-1][2], "partitions": int(d[-1][3])}
    mem = re.search(r"peak memory footprint \(MB\)[^:]*: (?:init=[\d.]+ overall=)?([\d.]+)", text, re.I)
    if mem:
        r["peak_mem_mb"] = round(float(mem[1]))
    acc = re.search(r"NNAPI accelerators available: \[(.*)\]", text)
    if acc:
        r["nnapi_accelerators"] = acc[1]
    if "OpenCL" in text:
        r["gpu_api"] = "OpenCL"
    elif "OpenGL" in text:
        r["gpu_api"] = "OpenGL"
    err = [l for l in text.splitlines() if re.search(r"ERROR|Failed|failed|not supported|Aborted", l)]
    if err:
        r["errors"] = err[:5]
    return r


def bench(adb: Adb, model: Path, delegate: str, secs: int, raw_dir: Path, tag: str = "") -> dict:
    args = [f"{DEV}/benchmark_model", f"--graph={DEV}/{model.name}", "--warmup_runs=5", "--warmup_min_secs=2",
            "--num_runs=20", f"--min_secs={secs}", f"--max_secs={secs + 10}", "--report_peak_memory_footprint=true",
            *DELEGATES[delegate]]
    before = thermal(adb)
    t0 = time.time()
    r = subprocess.run([adb.exe, "-s", adb.serial, "shell", " ".join(args)], capture_output=True, text=True,
                       timeout=secs + 180)
    text = r.stdout + r.stderr
    name = f"{model.stem}_{delegate}{tag}"
    (raw_dir / f"{name}.log").write_text(text)
    res = {"model": model.stem, "delegate": delegate, "tag": tag.strip("_"), "wall_s": round(time.time() - t0, 1),
           "exit": r.returncode, "before": before, "after": thermal(adb), **parse(text)}
    meta = model_meta(model)
    res.update(name=meta["model"], size=meta["size"], precision=meta["precision"])
    if "fps" not in res:
        res["failed"] = True
    return res


def model_meta(model: Path) -> dict:
    return json.loads((MODELS_DIR / "manifest.json").read_text())[model.name]


def describe(r: dict) -> str:
    if r.get("failed"):
        return f"{r['model']:24s} {r['delegate']:6s}  FAILED  {(r.get('errors') or ['see log'])[0][:90]}"
    d = r.get("delegated")
    dd = f"  {d['nodes']}/{d['of']} ops on {d['delegate']} in {d['partitions']} parts" if d else ""
    if no_accel(r):
        dd = f"  no NPU driver (NNAPI devices: {r.get('nnapi_accelerators', '?')}), ran on the CPU"
    return (f"{r['model']:24s} {r['delegate']:6s} {r['avg_ms']:7.1f} ms  {r['fps']:6.1f} fps"
            f"  thermal {r['after']['thermal_status']}{dd}")


# ---------------------------------------------------------------- report

def no_accel(r: dict) -> bool:
    """NNAPI with no vendor driver silently runs on the CPU; such rows say nothing about an NPU."""
    return r["delegate"] == "nnapi" and "nnapi" not in r.get("delegated", {}).get("delegate", "").lower()


def kind(r: dict) -> str:
    return "npu" if r["delegate"] == "nnapi" else "gpu" if r["delegate"] == "gpu" else "cpu"


def best(rows: list[dict], name: str, size: int, tag: str = "", on: str | None = None) -> dict | None:
    """Fastest row for a model and size, optionally only on one processor kind (cpu, gpu, npu)."""
    ok = [r for r in rows if not r.get("failed") and r["name"] == name and r["size"] == size and r["tag"] == tag
          and not no_accel(r) and (on is None or kind(r) == on)]
    return max(ok, key=lambda r: r["fps"], default=None)


def report(out: Path, info: dict, rows: list[dict], sustained: list[dict], soak_dir: str | None):
    need = sum(WORKLOAD.values())
    L = [f"# Detector benchmark: {info['manufacturer']} {info['model']}", "",
         f"SoC {info['soc']}, GPU {info['gpu']}, Android {info['android']}, RAM {info['ram_kb'] / 1e6:.1f} GB. "
         f"Run {out.name}.", "",
         "Speed of one inference on one frame, TensorFlow Lite `benchmark_model`, about 15 s per config after a "
         "warm-up. Pre- and post-processing (resize, box decoding, NMS) are not included: about 1-5 ms on the CPU.", ""]

    names = sorted({r["name"] for r in rows})
    sizes = sorted({r["size"] for r in rows})
    def cell(r: dict | None) -> str:
        if not r:
            return "-"
        how = r["precision"] + (f", {r['delegate'][3:]} threads" if kind(r) == "cpu" else "")
        return f"**{r['fps']:.0f}** ({r['fps'] / need:.1f}×) {how}"

    under = any(r["tag"] == "under-load" for r in rows)
    npu = any(kind(r) == "npu" and not no_accel(r) and not r.get("failed") for r in rows)
    L += ["## Best per processor", "",
          f"The draft workload needs about **{need} detections/s** for the whole store "
          f"({', '.join(f'{k} {v}' for k, v in WORKLOAD.items())}). Each cell: fps (headroom = fps / {need}) "
          "and the faster precision. Headroom must stay above 1 **while decoding**: see the under-load columns.", "",
          "| Model | Input | GPU | CPU |" + (" NPU |" if npu else "")
          + (" GPU, 4 streams decoding | CPU, 4 streams decoding |" if under else ""),
          "|---|---|---|---|" + ("---|" if npu else "") + ("---|---|" if under else "")]
    for n in names:
        for s in sizes:
            if not best(rows, n, s):
                continue
            c = [cell(best(rows, n, s, on="gpu")), cell(best(rows, n, s, on="cpu"))]
            if npu:
                c.append(cell(best(rows, n, s, on="npu")))
            if under:
                c += [cell(best(rows, n, s, "under-load", "gpu")), cell(best(rows, n, s, "under-load", "cpu"))]
            L.append(f"| {n} | {s} | " + " | ".join(c) + " |")
    acc = next((r["nnapi_accelerators"] for r in rows if "nnapi_accelerators" in r), None)
    if not npu and acc is not None:
        L += ["", f"No NPU: NNAPI lists only `{acc or 'nothing'}`, so the NNAPI rows ran on the CPU "
                  "and are left out above."]
    L += ["", "## Every config", "",
          "| Model | Input | Precision | Processor | ms (avg) | min-max ms | fps | Ops on accelerator | "
          "Init ms | Peak MB | Thermal after |", "|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in sorted(rows, key=lambda r: (r["tag"], r["name"], r["size"], r["precision"], r["delegate"])):
        tag = " (under load)" if r["tag"] else ""
        if r.get("failed"):
            L.append(f"| {r['name']} | {r['size']} | {r['precision']} | {r['delegate']}{tag} | failed | | | "
                     f"{(r.get('errors') or ['see raw log'])[0][:60]} | | | |")
            continue
        d = r.get("delegated")
        dd = f"{d['nodes']}/{d['of']} on {d['delegate']}, {d['partitions']} parts" if d else "CPU only"
        if no_accel(r):
            dd = f"none: no NPU driver ({r.get('nnapi_accelerators', '?')}), fell back to CPU"
        L.append(f"| {r['name']} | {r['size']} | {r['precision']} | {r['delegate']}{tag} | {r['avg_ms']:.1f} | "
                 f"{r.get('min_ms', 0):.0f}-{r.get('max_ms', 0):.0f} | {r['fps']} | {dd} | {r['init_ms']:.0f} | "
                 f"{r.get('peak_mem_mb', '')} | {r['after']['thermal_status']} |")
    if sustained:
        first, last = sustained[0], sustained[-1]
        drop = 100 * (1 - last["fps"] / first["fps"]) if first.get("fps") and last.get("fps") else None
        L += ["", f"## Sustained run: {first['model']} on {first['delegate']}", "",
              f"{len(sustained)} back-to-back blocks; fps {first.get('fps')} → {last.get('fps')}"
              + (f" ({drop:.0f}% drop)" if drop is not None else "") + ".", "",
              "| Block | fps | Thermal status | Hottest zone °C | Battery °C |", "|---|---|---|---|---|"]
        for i, r in enumerate(sustained):
            L.append(f"| {i + 1} | {r.get('fps', 'failed')} | {r['after']['thermal_status']} | "
                     f"{r['after']['zone_max_c']} | {r['after']['battery_c']} |")
    if soak_dir:
        L += ["", f"Decoding during the under-load runs: see `results/{soak_dir}/summary.md` "
                  "(lost frames there mean detection starved the decoder)."]
    L += ["", "## How to read this", "",
          "- **cpu1/2/4:** XNNPACK with that many threads. On big.LITTLE chips more threads can be slower once they "
          "land on the small cores.",
          "- **gpu:** the GPU delegate in FP16. If fewer than all ops are on the GPU, the rest run on the CPU and "
          "each switch costs time.",
          "- **nnapi:** only counts if ops landed on an accelerator; with 0 ops delegated there is no NPU driver "
          "and the row is ignored for 'fastest'.",
          "- **fp32 vs int8:** INT8 is usually faster on CPU and NPU; the GPU delegate runs both in FP16. "
          "`models/manifest.json` lists how many PyTorch detections each file reproduces.",
          "- One interpreter serves all cameras in turn, so fps here is shared by every camera."]
    (out / "summary.md").write_text("\n".join(L) + "\n")


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--models", nargs="+", default=["yolo11n", "yolo26n", "yoloe-11s"])
    ap.add_argument("--sizes", nargs="+", type=int, default=[320, 640])
    ap.add_argument("--precisions", nargs="+", default=["fp32", "int8"], choices=["fp32", "int8"])
    ap.add_argument("--delegates", nargs="+", default=list(DELEGATES), choices=list(DELEGATES))
    ap.add_argument("--quick", action="store_true", help="only cpu4, gpu and nnapi")
    ap.add_argument("--secs", type=int, default=15, help="seconds per config")
    ap.add_argument("--under-load", action="store_true",
                    help="repeat the fastest configs while the soak app decodes 4 local streams")
    ap.add_argument("--sustained", type=int, metavar="SECONDS", help="run the fastest config this long")
    ap.add_argument("--serial")
    ap.add_argument("--tag", default="")
    a = ap.parse_args()
    if a.quick:
        a.delegates = [d for d in a.delegates if d in ("cpu4", "gpu", "nnapi")]

    manifest = json.loads((MODELS_DIR / "manifest.json").read_text())
    names = [n for n, m in sorted(manifest.items())
             if m["model"] in a.models and m["size"] in a.sizes and m["precision"] in a.precisions]
    if not names:
        sys.exit("no model matches --models/--sizes/--precisions (see models/manifest.json)")
    models = ensure_models(names)
    adb = Adb(a.serial)
    info = device_info(adb)
    log(f"device: {info['manufacturer']} {info['model']}  SoC {info['soc']}  GPU {info['gpu']}")

    run_id = time.strftime("%Y%m%d-%H%M%S") + f"-bench-{info['model'].replace(' ', '_')}" + (f"-{a.tag}" if a.tag else "")
    out = ROOT / "results" / run_id
    raw = out / "raw"
    raw.mkdir(parents=True)
    (out / "device.json").write_text(json.dumps(info, indent=1))

    adb.shell(f"mkdir -p {DEV}")
    adb.run("push", str(BENCH), f"{DEV}/benchmark_model")
    adb.shell(f"chmod 755 {DEV}/benchmark_model")
    for m in models:
        adb.run("push", str(m), f"{DEV}/{m.name}", timeout=300)

    rows: list[dict] = []
    jl = open(out / "bench.jsonl", "w")

    def record(r: dict):
        rows.append(r) if not r["tag"].startswith("sustained") else None
        jl.write(json.dumps(r) + "\n"); jl.flush()
        log(describe(r))

    total = len(models) * len(a.delegates)
    log(f"{total} configs, about {total * (a.secs + 8) / 60:.0f} min")
    for m in models:
        for d in a.delegates:
            record(bench(adb, m, d, a.secs, raw))
            time.sleep(3)  # let the clocks settle between configs

    soak_dir = None
    if a.under_load:
        picks = []
        for n in a.models:
            for s in a.sizes:
                for on in ("gpu", "npu", "cpu"):  # the best of each processor, since a store may use either
                    b = best(rows, n, s, on=on)
                    if b and (b["model"], b["delegate"]) not in picks:
                        picks.append((b["model"], b["delegate"]))
        minutes = len(picks) * (a.secs + 25) / 60 + 3
        log(f"under load: starting the 4-stream local decode for {minutes:.0f} min, then {len(picks)} configs")
        tag = f"under-load-bench{'-' + a.tag if a.tag else ''}"
        installed = adb.shell(f"pm path {PKG}", check=False).strip() != ""
        soak = subprocess.Popen([sys.executable, str(ROOT / "run_test.py"), "--mode", "local", "--minutes",
                                 f"{minutes:.1f}", "--tag", tag, *(["--no-install"] if installed else []),
                                 *(["--serial", a.serial] if a.serial else [])],
                                stdout=open(out / "soak_runner.log", "w"), stderr=subprocess.STDOUT)
        # Wait for the app's first metrics line (clip build and push can take minutes the first time),
        # then let decoding warm up for a minute.
        deadline = time.time() + 900
        while time.time() < deadline and soak.poll() is None:
            if adb.shell(f"ls {DEV_FILES}/runs/*{tag}/metrics.jsonl 2>/dev/null", check=False).strip():
                break
            time.sleep(5)
        time.sleep(60)
        for mname, d in picks:
            if soak.poll() is not None:
                log("the soak run ended early; see soak_runner.log")
                break
            record(bench(adb, MODELS_DIR / f"{mname}.tflite", d, a.secs, raw, "_under-load"))
        log("waiting for the soak run to finish and report")
        soak.wait()
        found = sorted((ROOT / "results").glob(f"*-local-*-{tag}"))
        soak_dir = found[-1].name if found else None

    sustained: list[dict] = []
    if a.sustained:
        b = max((r for r in rows if not r.get("failed") and not r["tag"]
                 and not no_accel(r)
                 and r["size"] == min(a.sizes) and r["name"] == a.models[0]), key=lambda r: r["fps"], default=None)
        if b:
            log(f"sustained: {b['model']} on {b['delegate']} for {a.sustained} s")
            for i in range(max(1, a.sustained // 30)):
                r = bench(adb, MODELS_DIR / f"{b['model']}.tflite", b["delegate"], 30, raw, f"_sustained{i:02d}")
                sustained.append(r)
                record(r)

    jl.close()
    report(out, info, rows, sustained, soak_dir)
    adb.shell(f"rm -rf {DEV}", check=False)
    log(f"results in {out}")
    print((out / "summary.md").read_text())


if __name__ == "__main__":
    main()

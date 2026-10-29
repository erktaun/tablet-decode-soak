#!/usr/bin/env python3
"""Phone/tablet smoke test for the restaurant-analytics app (docs/analytics/plan.md, "Testing").

Builds and installs the app, pushes a site pack, runs it on its recorded video(s) and checks the events against the
site's expect.json. Prints PASS/FAIL per expected event, plus detector speed and dropped frames.

    python3 tools/check_device.py --site pizza_trailer [--speed 2] [--key ../.env] [--no-build] [--no-install]

  --key FILE   push OPENAI_API_KEY from FILE into the app's private storage (never printed), so ingredient checks run.
               Without it the app runs with checks off and dish verdicts can't pass.
  --speed S    play recordings S times faster than real time (the detector rate per stream second stays the same).
  --params J   JSON merged over the site's params for this run.
  --timeout M  minutes to wait for the run to finish (default: video length / speed + 3).

A site pack is sites/<site>/: site.json, menu.json, orders.json, model.tflite, model.json, optional background/,
expect.json, and the videos its cameras' file: URLs name (symlinks are fine). Results: out/device/<run_id>/.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from run_test import Adb, log  # noqa: E402

PKG = "com.ald.ra"
ACT = f"{PKG}/com.ald.ra.app.MainActivity"
FILES = f"/storage/emulated/0/Android/data/{PKG}/files"
APK = ROOT / "android" / "analytics" / "build" / "outputs" / "apk" / "debug" / "analytics-debug.apk"
JBR = Path.home() / "Documents" / "libs" / "android-studio" / "jbr"


def build():
    log("building the app (gradle :analytics:assembleDebug)")
    env = dict(__import__("os").environ)
    if JBR.exists():
        env["JAVA_HOME"] = str(JBR)
    subprocess.run(["./gradlew", "-q", ":analytics:assembleDebug"], cwd=ROOT / "android", env=env, check=True)


def env_key(f: Path, name="OPENAI_API_KEY") -> str:
    for line in f.read_text().splitlines():
        if line.strip().startswith(name + "="):
            return line.split("=", 1)[1].strip().strip("'\"")
    sys.exit(f"{name} not in {f}")


def push_site(adb: Adb, site: Path):
    dst = f"{FILES}/sites/{site.name}"
    adb.shell(f"mkdir -p {dst}/background")
    files = [p for p in site.iterdir() if p.is_file() and p.suffix in (".json", ".tflite", ".mp4")]
    files += sorted((site / "background").glob("*")) if (site / "background").exists() else []
    for p in files:
        rel = p.relative_to(site).as_posix()
        size = p.stat().st_size  # follows symlinks
        have = adb.shell(f"stat -c %s {dst}/{rel} 2>/dev/null", check=False).strip()
        if have == str(size) and p.suffix in (".tflite", ".mp4"):
            continue  # big files: skip when the device already has the same size
        log(f"push {rel} ({size / 1e6:.1f} MB)")
        adb.run("push", str(p.resolve()), f"{dst}/{rel}", timeout=600)
    for v in json.loads((site / "site.json").read_text())["cameras"]:
        u = v["url"]
        if u.startswith("file:") and not (site / u[5:]).exists():
            sys.exit(f"camera {v['name']}: video {u[5:]} not in {site} (add it or a symlink)")


def video_seconds(site: Path) -> float:
    vids = {c["url"][5:] for c in json.loads((site / "site.json").read_text())["cameras"] if c["url"].startswith("file:")}
    secs = [float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
                                  str((site / v).resolve())], capture_output=True, text=True).stdout or 0) for v in vids]
    return max(secs or [0.0])


def check(events: list[dict], expect: dict) -> list[tuple[bool, str]]:
    out, used = [], set()
    for x in expect.get("events", []):
        hits = [i for i, e in enumerate(events) if i not in used and e["kind"] == x["kind"] and abs(e["t"] - x["t"]) <= x.get("tol", 3)
                and all(e.get(k) == v for k, v in x.get("has", {}).items())]
        if hits:
            used.add(hits[0])
            out.append((True, f"{x['kind']} at {events[hits[0]]['t']:.1f} (expected {x['t']}) {x.get('has', '')}"))
        else:
            near = [e for e in events if e["kind"] == x["kind"]]
            got = "; ".join(f"t={e['t']:.1f} " + json.dumps({k: e.get(k) for k in x.get('has', {})}) for e in near[:4]) or "none"
            out.append((False, f"{x['kind']} at {x['t']}±{x.get('tol', 3)} {x.get('has', '')} — got: {got}"))
    for k in expect.get("never", []):
        bad = [e for e in events if e["kind"] == k]
        out.append((not bad, f"no {k}" + (f" — got {len(bad)} at {[round(e['t'], 1) for e in bad]}" if bad else "")))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--site", default="pizza_trailer")
    ap.add_argument("--serial")
    ap.add_argument("--speed", type=float, default=1.0)
    ap.add_argument("--params")
    ap.add_argument("--key", type=Path, help=".env file with OPENAI_API_KEY")
    ap.add_argument("--no-build", action="store_true")
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--save-dets", action="store_true", help="also log every frame's detections")
    ap.add_argument("--timeout", type=float)
    a = ap.parse_args()

    site = ROOT / "sites" / a.site
    adb = Adb(a.serial)
    log(f"device {adb.serial}: {adb.prop('ro.product.model')} ({adb.prop('ro.soc.model')}), Android {adb.prop('ro.build.version.release')}")
    if not a.no_build:
        build()
    if not a.no_install:
        log("installing (on Xiaomi, tap 'Install' on the device if asked)")
        adb.run("install", "-r", str(APK), timeout=300)
    adb.shell(f"am start -n {ACT}")  # creates the app's files/ dirs
    time.sleep(2)
    push_site(adb, site)
    if a.key:
        key = env_key(a.key)
        r = subprocess.run([adb.exe, "-s", adb.serial, "shell", f"run-as {PKG} sh -c 'mkdir -p files && cat > files/openai.key'"],
                           input=key, text=True, capture_output=True)
        if r.returncode != 0:
            sys.exit(f"could not store the key: {r.stderr.strip()}")
        log("API key stored in the app's private files")

    run_id = time.strftime("%Y%m%d-%H%M%S") + "-check-" + a.site
    extras = {"mode": "start", "site": a.site, "run_id": run_id, "speed": str(a.speed)}
    if a.params:
        extras["params"] = a.params
    if a.save_dets:
        extras["save_dets"] = "true"
    args = ["shell", "am", "start", "-n", ACT]
    for k, v in extras.items():
        args += ["--es", k, f"'{v}'" if k == "params" else v]
    adb.run(*args)
    run_dir = f"{FILES}/runs/{run_id}"
    limit = 60 * (a.timeout or (video_seconds(site) / a.speed / 60 + 3))
    log(f"run {run_id} started; waiting up to {limit / 60:.0f} min")
    t0, last = time.time(), ""
    while time.time() - t0 < limit:
        if adb.exists(f"{run_dir}/done.json"):
            break
        m = adb.shell(f"tail -n 1 {run_dir}/metrics.jsonl 2>/dev/null", check=False).strip()
        if m and m != last:
            last = m
            j = json.loads(m)
            log(f"  t={j['t']:.0f}s  detector {j['infer_ms_p50']} ms p50 ({j['backend']}), {j['shots_5s']} shots/5s, "
                f"checks {j['checks']} ({j.get('check_errors', 0)} failed), thermal {j['thermal']}, dropped {sum(c['dropped'] for c in j['cams'].values())}")
        time.sleep(5)
    else:
        log("timed out; stopping the run")
        adb.run("shell", "am", "start", "-n", ACT, "--es", "mode", "stop")
        time.sleep(5)

    out = ROOT / "out" / "device"
    out.mkdir(parents=True, exist_ok=True)
    adb.run("pull", run_dir, str(out), timeout=300)
    local = out / run_id
    done = json.loads((local / "done.json").read_text()) if (local / "done.json").exists() else {"reason": "no done.json"}
    log(f"pulled {local}; finished: {done.get('reason')} {done.get('error', '')}")
    ev_file = local / "events_final.jsonl"
    if not ev_file.exists():
        ev_file = local / "events.jsonl"
    events = [json.loads(l) for l in ev_file.read_text().splitlines() if l.strip()] if ev_file.exists() else []
    ms = [json.loads(l) for l in (local / "metrics.jsonl").read_text().splitlines()] if (local / "metrics.jsonl").exists() else []
    if ms:
        inf = [m["infer_ms_p50"] for m in ms if m.get("infer_ms_p50")]
        last = ms[-1]
        log(f"detector: {last['backend']}, p50 of 5-s medians {sorted(inf)[len(inf) // 2] if inf else '-'} ms; "
            f"dropped {sum(c['dropped'] for c in last['cams'].values())} of {sum(c['shots'] for c in last['cams'].values())} shots; "
            f"checks {last['checks']} (p50 {last['check_p50_s']} s, ${last['cost_usd']:.4f}); max thermal {max(m['thermal'] for m in ms)}")
    errs = [l for l in (local / "log.txt").read_text().splitlines() if "error" in l.lower() or "FAILED" in l] if (local / "log.txt").exists() else []
    for e in errs[:10]:
        log(f"  log: {e}")
    expect = site / "expect.json"
    if not expect.exists():
        log("no expect.json for this site: nothing to check")
        return
    res = check(events, json.loads(expect.read_text()))
    for ok, msg in res:
        print(("PASS  " if ok else "FAIL  ") + msg)
    n_bad = sum(not ok for ok, _ in res)
    print(f"\n{'ALL PASS' if n_bad == 0 else f'{n_bad} FAILED'} ({len(res)} checks) — {local}")
    sys.exit(1 if n_bad else 0)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Plot a soak run and check it against the draft pass criteria.

    python3 plot_run.py results/<run_id>      ->  report.png + summary.md in that folder

Inputs (pulled by run_test.py): metrics.jsonl (app), sampler.log (system CPU, clocks, thermal
zones; logged over adb), config.json, caps.json, events.log, host.json.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

# Draft pass criteria from the feasibility plan (Experiment 1). Change them here, not in the code below.
CRITERIA = {
    "max_lost_frac": 0.01,        # dropped + missing frames, share of all frames the cameras sent
    "max_thermal_status": 2,      # Android thermal status: 0 none, 1 light, 2 moderate, 3 severe ...
    "max_fps_decline": 0.10,      # delivered fps, last window vs first window after warm-up
    "max_system_cpu_pct": 70.0,   # average system-wide CPU over the run
}
WARMUP_S = 60


def load_metrics(d: Path) -> list[dict]:
    rows = []
    for line in (d / "metrics.jsonl").read_text().splitlines():
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            pass  # a line cut off when the run was stopped
    return rows


def load_sampler(d: Path) -> list[dict]:
    """Blocks of: T <epoch>, cpu lines, F <cpu> <khz>, Z <type> <milli-C>, E."""
    p = d / "sampler.log"
    if not p.exists():
        return []
    blocks, cur = [], None
    for line in p.read_text(errors="replace").splitlines():
        parts = line.split()
        if not parts:
            continue
        if parts[0] == "T":
            cur = {"t": int(parts[1]), "cpu": {}, "freq": {}, "zones": []}
        elif cur is None:
            continue
        elif parts[0].startswith("cpu"):
            cur["cpu"][parts[0]] = [int(x) for x in parts[1:]]
        elif parts[0] == "F" and len(parts) == 3 and parts[2].isdigit():
            cur["freq"][parts[1]] = int(parts[2])
        elif parts[0] == "Z" and len(parts) == 3 and parts[2].lstrip("-").isdigit():
            cur["zones"].append((parts[1], int(parts[2])))
        elif parts[0] == "E":
            blocks.append(cur)
            cur = None
    # System CPU % from /proc/stat deltas (idle + iowait count as idle).
    for a, b in zip(blocks, blocks[1:]):
        x, y = a["cpu"].get("cpu"), b["cpu"].get("cpu")
        if x and y:
            tot = sum(y[:8]) - sum(x[:8])
            idle = (y[3] + y[4]) - (x[3] + x[4])
            b["sys_cpu"] = 100.0 * (tot - idle) / tot if tot > 0 else None
    for b in blocks:
        temps = [t / 1000 for _, t in b["zones"] if 0 < t < 150000]
        b["zone_max_c"] = max(temps) if temps else None
    return blocks


def mean(xs):
    xs = [x for x in xs if x is not None]
    return sum(xs) / len(xs) if xs else None


def server_drops(d: Path) -> int | None:
    """mediamtx logs 'reader is too slow, discarding' when the device's connection stops draining:
    those losses are the network's (e.g. Wi-Fi stalls), not the device's decoding."""
    p = d / "mediamtx.log"
    return p.read_text(errors="replace").count("reader is too slow") if p.exists() else None


def evaluate(cfg: dict, rows: list[dict], samp: list[dict], drops: int | None = None) -> tuple[list[tuple[str, bool | None, str]], dict]:
    fps_nom = cfg.get("clip_fps", 25)
    n_streams = len(rows[0]["streams"]) if rows else 0
    period = cfg.get("period_s", 5)
    # Losses while the streams start up are counted separately; the check is about steady state.
    steady = [r for r in rows if r["elapsed_s"] > WARMUP_S]
    startup_lost = sum(s["dropped"] + s["gaps"] for r in rows if r["elapsed_s"] <= WARMUP_S for s in r["streams"])
    frames = sum(s["frames"] for r in steady for s in r["streams"])
    lost = sum(s["dropped"] + s["gaps"] for r in steady for s in r["streams"])
    sent = frames + lost
    lost_frac = lost / sent if sent else None
    thermal = [r.get("thermal_status") for r in rows if r.get("thermal_status") is not None]
    worst = max(thermal) if thermal else None
    # Delivered fps per window (all streams), first vs last window of equal length after warm-up.
    span = rows[-1]["elapsed_s"] if rows else 0
    win = max(period, min(600, (span - WARMUP_S) / 3)) if span > WARMUP_S else None
    first = last = None
    if win:
        def window(lo, hi):
            return mean([sum(s["fps"] for s in r["streams"]) for r in rows if lo <= r["elapsed_s"] < hi])
        first, last = window(WARMUP_S, WARMUP_S + win), window(span - win, span + 1)
    decline = (first - last) / first if first and last is not None else None
    sys_cpu = mean([b.get("sys_cpu") for b in samp])
    decoders = sorted({s["decoder"] for r in rows for s in r["streams"] if s.get("decoder")})
    software = [d for d in decoders if d.startswith(("c2.android.", "OMX.google."))]
    errors = sum(r["streams"][i]["errors_total"] for i in range(n_streams) for r in rows[-1:])
    restarts = sum(r["streams"][i]["restarts_total"] for i in range(n_streams) for r in rows[-1:])
    lats = [s["lat_p95_ms"] for r in rows for s in r["streams"] if s.get("lat_p95_ms") is not None]
    # The app logs every period; a much longer gap means the OS froze it (e.g. Xiaomi's app freezer
    # when the screen is off on battery). Frames lost right after a pause are a consequence of it.
    pauses = [b["elapsed_s"] - a["elapsed_s"] for a, b in zip(rows, rows[1:])
              if b["elapsed_s"] - a["elapsed_s"] > 3 * period]

    c = CRITERIA
    checks = [
        ("Lost frames after the first minute",
         (False if steady else None) if lost_frac is None else lost_frac <= c["max_lost_frac"],
         ("no frames arrived at all" if steady and not sent else
          f"{lost} of {sent} = {100 * (lost_frac or 0):.2f}% (limit {100 * c['max_lost_frac']:.0f}%)")
         + (f"; the RTSP server discarded data {drops} times because the device's connection stalled "
            f"(network, not decoding)" if drops else "")),
        ("Worst thermal status", None if worst is None else worst <= c["max_thermal_status"],
         f"{worst} (limit {c['max_thermal_status']} = moderate)" if worst is not None else "not reported (Android < 10)"),
        ("Delivered fps, last vs first window", None if decline is None else decline <= c["max_fps_decline"],
         f"{first:.1f} -> {last:.1f} fps total ({-100 * decline:+.1f}%, decline limit {100 * c['max_fps_decline']:.0f}%)"
         if decline is not None else "run too short"),
        ("System CPU (average)", None if sys_cpu is None else sys_cpu <= c["max_system_cpu_pct"],
         f"{sys_cpu:.0f}% (limit {c['max_system_cpu_pct']:.0f}%)" if sys_cpu is not None else "no sampler data"),
        ("App never paused by the system", not pauses,
         f"{len(pauses)} pause(s), {sum(pauses):.0f} s in total; see README (Xiaomi app freezer)" if pauses
         else "logged every period"),
        ("Hardware decoders only", not software, ", ".join(decoders) or "none reported"),
        ("No decoder errors / restarts", errors == 0 and restarts == 0, f"{errors} errors, {restarts} restarts"),
    ]
    facts = {
        "duration_min": span / 60,
        "lost_frames_first_minute": startup_lost, "streams": n_streams, "nominal_fps": fps_nom,
        "avg_fps_per_stream": mean([mean([s["fps"] for s in r["streams"]]) for r in rows if r["elapsed_s"] > WARMUP_S]),
        "latency_p95_ms_median": sorted(lats)[len(lats) // 2] if lats else None,
        "battery_temp_max_c": max([r["battery_temp_c"] for r in rows if r.get("battery_temp_c") is not None], default=None),
        "zone_temp_max_c": max([b["zone_max_c"] for b in samp if b.get("zone_max_c") is not None], default=None),
        "max_thermal_headroom": max([r["thermal_headroom"] for r in rows if r.get("thermal_headroom") is not None],
                                    default=None),  # 0 = cool, 1 = the OS starts throttling
        "app_cpu_pct_avg": mean([r.get("app_cpu_pct") for r in rows]),
        "catch_ups": sum(s.get("catchups_total", 0) for s in rows[-1]["streams"]) if rows else None,
        "proc_ms_avg": mean([s.get("proc_ms") for r in rows for s in r["streams"]]),
        "battery_level_first_last": (rows[0].get("battery_level"), rows[-1].get("battery_level")) if rows else None,
        "plugged": sorted({r.get("plugged") for r in rows}),
    }
    return checks, facts


def plot(d: Path, cfg: dict, rows: list[dict], samp: list[dict]):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    t = [r["elapsed_s"] / 60 for r in rows]
    t0 = rows[0]["t_ms"] / 1000 - rows[0]["elapsed_s"]
    ts = [(b["t"] - t0) / 60 for b in samp]
    n = len(rows[0]["streams"])
    fig, ax = plt.subplots(6, 1, figsize=(12, 16), sharex=True)

    for i in range(n):
        ax[0].plot(t, [r["streams"][i]["fps"] for r in rows], label=f"stream {i}", lw=1)
    ax[0].axhline(cfg.get("clip_fps", 25), color="k", ls=":", lw=1, label="camera fps")
    ax[0].set_ylabel("decoded fps")
    ax[0].legend(loc="lower left", ncol=n + 1, fontsize=8)

    for i in range(n):
        ax[1].plot(t, [r["streams"][i]["dropped"] + r["streams"][i]["gaps"] for r in rows], label=f"stream {i}", lw=1)
    ax[1].set_ylabel(f"lost frames\nper {cfg.get('period_s', 5)} s")

    ax[2].plot(t, [r.get("thermal_status") for r in rows], color="C3", label="thermal status (0-6)")
    ax[2].set_ylabel("thermal status")
    h = ax[2].twinx()
    h.plot(t, [r.get("thermal_headroom") for r in rows], color="C0", lw=1, label="headroom (1.0 = throttle)")
    h.set_ylabel("headroom")
    ax[2].legend(loc="upper left", fontsize=8); h.legend(loc="upper right", fontsize=8)

    ax[3].plot(t, [r.get("battery_temp_c") for r in rows], label="battery")
    if samp:
        ax[3].plot(ts, [b.get("zone_max_c") for b in samp], label="hottest thermal zone", lw=1)
    ax[3].set_ylabel("°C")
    ax[3].legend(loc="upper left", fontsize=8)

    if samp:
        ax[4].plot(ts, [b.get("sys_cpu") for b in samp], label="system CPU %")
        cpus = sorted({c for b in samp for c in b["freq"]}, key=lambda c: int(c[3:]))
        f = ax[4].twinx()
        for c in cpus:
            f.plot(ts, [b["freq"].get(c, 0) / 1e6 for b in samp], lw=0.6, alpha=0.6)
        f.set_ylabel("core clocks, GHz")
    ax[4].plot(t, [r.get("app_cpu_pct") for r in rows], label="test app CPU %")
    ax[4].axhline(CRITERIA["max_system_cpu_pct"], color="k", ls=":", lw=1)
    ax[4].set_ylabel("CPU %")
    ax[4].legend(loc="upper left", fontsize=8)

    for i in range(n):
        ax[5].plot(t, [r["streams"][i].get("lat_p95_ms") for r in rows], lw=1, label=f"stream {i} p95")
    ax[5].set_ylabel("latency ms\n(camera to frame)")
    ax[5].set_xlabel("minutes")

    fig.suptitle(f"{cfg.get('run_id')}  —  {n} streams, {cfg.get('source')}, sink {cfg.get('sink')}, "
                 f"{cfg.get('infer_fps')} fps to {cfg.get('infer_size')} px")
    fig.tight_layout()
    fig.savefig(d / "report.png", dpi=110)
    plt.close(fig)


def report(d: Path) -> int:
    d = Path(d)
    cfg = json.loads((d / "config.json").read_text())
    rows = load_metrics(d)
    if not rows:
        print("no metrics in", d)
        return 1
    samp = load_sampler(d)
    checks, facts = evaluate(cfg, rows, samp, server_drops(d))
    caps = json.loads((d / "caps.json").read_text()) if (d / "caps.json").exists() else None
    lines = [f"# Soak run {cfg['run_id']}", ""]
    if caps:
        dev = caps["device"]
        lines += [f"Device: {dev['manufacturer']} {dev['model']}, SoC {dev.get('soc')}, Android {dev['release']}, "
                  f"RAM {dev['ram_mb']} MB", ""]
    lines += [f"Run: {facts['duration_min']:.1f} min, {facts['streams']} streams, source {cfg['source']}, "
              f"power {', '.join(map(str, facts['plugged']))}", ""]
    if (d / "done.json").exists():
        done = json.loads((d / "done.json").read_text())
        if done.get("reason") != "duration reached":
            lines += [f"Ended early: {done.get('reason')}.", ""]
    else:
        last = rows[-1]
        lines += [f"**Did not finish:** the app's log stops at {last['elapsed_s'] / 60:.0f} of {cfg.get('minutes')} min "
                  f"(battery {last.get('battery_level')}%, power {last.get('plugged')}). "
                  + ("The battery ran flat." if (last.get("battery_level") or 100) <= 2 and last.get("plugged") == "none"
                     else "The app was stopped or killed, or the run is still going (collect again later)."), ""]
    if samp and rows[-1]["t_ms"] / 1000 - samp[-1]["t"] > 600:
        lines += [f"The adb sampler (system CPU, thermal zones) stopped after "
                  f"{(samp[-1]['t'] - samp[0]['t']) / 3600:.1f} h; its averages cover only that part.", ""]
    lines += ["| Check | Result | Detail |", "|---|---|---|"]
    for name, ok, detail in checks:
        lines.append(f"| {name} | {'PASS' if ok else 'FAIL' if ok is False else 'n/a'} | {detail} |")
    lines += ["", "| Measure | Value |", "|---|---|"]
    for k, v in facts.items():
        lines.append(f"| {k} | {round(v, 2) if isinstance(v, float) else v} |")
    if caps:
        lines += ["", "Decoder instance probe (Experiment 0):", ""]
        lines += [f"- {p['decoder']} {p['size']}: {p['started']} started at once (reported max {p['reported_max']})"
                  for p in caps["instance_probe"]]
    (d / "summary.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))
    try:
        plot(d, cfg, rows, samp)
        print(f"\nplot: {d / 'report.png'}")
    except ImportError:
        print("\n(matplotlib not installed: pip install matplotlib, then re-run plot_run.py for the plot)")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    sys.exit(report(Path(sys.argv[1])))

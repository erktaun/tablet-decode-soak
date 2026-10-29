#!/usr/bin/env python3
"""Collect everything we need to understand a run, from the tablet, into one file to send us (RUNBOOK.md §6).

    python3 tools/pull_report.py [--serial X] [--runs 3] [--no-frames] [--with-model]

Writes out/reports/report-<time>.tar.gz with:
  files.tar    the app's files: runs/ (events, checks, metrics, log, detections, stills, check images, orders),
               crashes/, sites/ (setup, references, menu); recordings (*.mp4) and models (rebuildable) left out
  device.txt   tablet model, Android version, app version, battery, temperature, battery-saver exemptions
  logcat.txt   the system log (app crashes and warnings that never reached the app's own files)
The API key is not included: it lives in the app's private storage, which this doesn't read.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import tarfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from run_test import Adb, log  # noqa: E402

PKG = "com.ald.ra"
FILES = f"/storage/emulated/0/Android/data/{PKG}/files"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial")
    ap.add_argument("--runs", type=int, default=0, help="only the latest N runs (default: all)")
    ap.add_argument("--no-frames", action="store_true", help="leave out the stills (frames/), which are the bulk")
    ap.add_argument("--with-model", action="store_true", help="include model.tflite (40 MB per site; rebuildable from refs/)")
    a = ap.parse_args()
    adb = Adb(a.serial)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    out = ROOT / "out" / "reports" / f"report-{stamp}"
    out.mkdir(parents=True, exist_ok=True)

    info = []
    for title, cmd in [("device", "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.soc.model; "
                                  "getprop ro.build.version.release; getprop ro.build.display.id; date"),
                       ("app", f"dumpsys package {PKG} | grep -E 'versionName|versionCode|lastUpdateTime'"),
                       ("battery-saver exemptions", f"dumpsys deviceidle whitelist | grep {PKG}; "
                                                    f"cmd appops get {PKG} RUN_ANY_IN_BACKGROUND"),
                       ("battery", "dumpsys battery"),
                       ("thermal", "dumpsys thermalservice | head -30"),
                       ("storage", f"df -h /storage/emulated/0; du -sh {FILES}/runs {FILES}/sites {FILES}/crashes 2>/dev/null")]:
        info.append(f"== {title}\n" + adb.shell(cmd, check=False, timeout=60).strip() + "\n")
    (out / "device.txt").write_text("\n".join(info))
    log("device info saved")

    lc = subprocess.run([adb.exe, "-s", adb.serial, "logcat", "-d", "-b", "all"], capture_output=True, timeout=120)
    (out / "logcat.txt").write_bytes(lc.stdout)
    log(f"system log saved ({len(lc.stdout) / 1e6:.1f} MB)")

    runs = adb.shell(f"ls {FILES}/runs 2>/dev/null", check=False).split()
    pick = [f"runs/{r}" for r in (runs[-a.runs:] if a.runs else runs)]
    have = [p for p in ["crashes", "sites"] if adb.exists(f"{FILES}/{p}")]
    excl = ["--exclude=*.mp4"] + (["--exclude=frames"] if a.no_frames else []) + ([] if a.with_model else ["--exclude=model.tflite"])
    log(f"packing {len(pick)} run(s) + {', '.join(have) or 'nothing else'} on the tablet")
    with open(out / "files.tar", "wb") as f:
        subprocess.run([adb.exe, "-s", adb.serial, "exec-out", "tar", "-c", "-C", FILES, *excl, *pick, *have], stdout=f, check=True, timeout=1800)
    tgz = out.with_suffix(".tar.gz")
    with tarfile.open(tgz, "w:gz") as t:
        t.add(out, arcname=out.name)
    log(f"report: {tgz} ({tgz.stat().st_size / 1e6:.1f} MB). Send it with the time of each problem and what you expected.")


if __name__ == "__main__":
    main()

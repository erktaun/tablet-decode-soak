#!/usr/bin/env python3
"""Record a site's camera streams on the laptop during a demo, so a run can be replayed exactly afterwards.

The app doesn't keep video (only a still every 2 s); this copies each RTSP stream to disk unchanged (no re-encoding,
little CPU), in 10-minute files named with their start time, so they line up with the run's started_at (run.json).

    python3 tools/record_cameras.py --site client_kitchen [--serial X]   # camera URLs from the tablet's site.json
    python3 tools/record_cameras.py --site-json path/to/site.json       # or from a local file

Stop with Ctrl-C. Files: out/recordings/<site>/<camera>_<YYYYmmdd-HHMMSS>.mp4 (about 0.5-1 GB per hour per 720p camera).
The laptop must be on the cameras' network.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

FILES = "/storage/emulated/0/Android/data/com.ald.ra/files"


def redact(url: str) -> str:
    return re.sub(r"(://[^:/@]+:)[^@]*@", r"\1****@", url)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--site", help="site name on the tablet")
    ap.add_argument("--site-json", type=Path)
    ap.add_argument("--serial")
    ap.add_argument("--segment-min", type=int, default=10)
    a = ap.parse_args()
    if not shutil.which("ffmpeg"):
        sys.exit("ffmpeg is not installed")
    if a.site_json:
        site = json.loads(a.site_json.read_text())
    elif a.site:
        from run_test import Adb
        adb = Adb(a.serial)
        site = json.loads(adb.shell(f"cat {FILES}/sites/{a.site}/site.json"))
    else:
        sys.exit("give --site or --site-json")
    cams = [c for c in site["cameras"] if c["url"].startswith("rtsp")]
    if not cams:
        sys.exit("no RTSP cameras in this site")
    out = ROOT / "out" / "recordings" / site["site"]
    out.mkdir(parents=True, exist_ok=True)
    procs = []
    for c in cams:
        pattern = str(out / f"{c['name']}_%Y%m%d-%H%M%S.mp4")
        cmd = ["ffmpeg", "-nostdin", "-loglevel", "warning", "-rtsp_transport", "tcp", "-i", c["url"], "-c", "copy", "-an",
               "-f", "segment", "-segment_time", str(60 * a.segment_min), "-reset_timestamps", "1", "-strftime", "1", pattern]
        procs.append((c["name"], subprocess.Popen(cmd, stderr=open(out / f"{c['name']}_ffmpeg.log", "a"))))
        print(f"recording {c['name']} ({redact(c['url'])}) -> {out}")
    signal.signal(signal.SIGINT, lambda *_: None)
    try:
        while all(p.poll() is None for _, p in procs):
            time.sleep(2)
    finally:
        for name, p in procs:
            if p.poll() is None:
                p.send_signal(signal.SIGINT)
        for name, p in procs:
            try:
                p.wait(timeout=10)
            except subprocess.TimeoutExpired:
                p.kill()
            print(f"{name}: stopped (exit {p.returncode}); see {name}_ffmpeg.log if it ended early")


if __name__ == "__main__":
    main()

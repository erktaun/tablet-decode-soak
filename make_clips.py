#!/usr/bin/env python3
"""Encode the test clips the way IP cameras stream: fixed fps, no B-frames, 2 s GOP, capped bitrate.

Each clip gets a frame-number barcode in its top strip (read by the app to count lost frames and
measure latency). Sources: clips/src/*.mp4 -- replace them with real store recordings if you have any.

    python3 make_clips.py --res 1080 --codec h264      ->  clips/build/h264_1080p25/cam0..3.mp4
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SIZES = {360: (640, 360), 720: (1280, 720), 1080: (1920, 1080), 1440: (2560, 1440)}
# Typical camera main-stream bitrates (kbit/s) at 25 fps; 360p is a typical sub-stream.
BITRATES = {360: 512, 720: 2048, 1080: 4096, 1440: 6144}
CELLS = 18  # must match FrameStamp in the app


def barcode_filter(bits: int = 16) -> str:
    """Black strip, white guard cell 0, black guard cell 17, cells 1..16 = frame index (LSB first)."""
    h = f"ih/20"
    w = f"iw/{CELLS}"
    parts = [f"drawbox=x=0:y=0:w=iw:h={h}:color=black:t=fill",
             f"drawbox=x=0:y=0:w={w}:h={h}:color=white:t=fill"]
    for k in range(bits):
        parts.append(f"drawbox=x={k + 1}*iw/{CELLS}:y=0:w={w}:h={h}:color=white:t=fill"
                     f":enable='eq(mod(floor(n/{2 ** k})\\,2)\\,1)'")
    return ",".join(parts)


def build(res: int, codec: str, fps: int, seconds: int, kbps: int | None, force: bool = False) -> Path:
    w, h = SIZES[res]
    kbps = kbps or BITRATES[res]
    out_dir = ROOT / "clips" / "build" / f"{codec}_{res}p{fps}"
    meta_path = out_dir / "meta.json"
    sources = sorted((ROOT / "clips" / "src").glob("*.mp4"))
    if not sources:
        sys.exit("no source clips in clips/src/")
    if meta_path.exists() and not force:
        return out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    gop = 2 * fps
    if codec == "h264":
        enc = ["-c:v", "libx264", "-preset", "medium", "-profile:v", "main", "-bf", "0",
               "-g", str(gop), "-keyint_min", str(gop), "-sc_threshold", "0"]
    else:
        enc = ["-c:v", "libx265", "-preset", "medium", "-tag:v", "hvc1",
               "-x265-params", f"bframes=0:keyint={gop}:min-keyint={gop}:scenecut=0:log-level=error"]
    clips = []
    for i, src in enumerate(sources):
        dst = out_dir / f"cam{i}.mp4"
        vf = f"fps={fps},scale={w}:{h}:flags=bicubic,setsar=1,{barcode_filter()}"
        cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(src), "-t", str(seconds),
               "-an", "-vf", vf, *enc, "-b:v", f"{kbps}k", "-maxrate", f"{kbps}k", "-bufsize", f"{kbps}k",
               "-pix_fmt", "yuv420p", str(dst)]
        print(f"  encoding {dst.relative_to(ROOT)} ({w}x{h} {codec} {kbps} kbit/s)", flush=True)
        subprocess.run(cmd, check=True)
        clips.append(dst.name)
    meta = {"res": res, "width": w, "height": h, "codec": codec, "fps": fps, "kbps": kbps,
            "seconds": seconds, "frames": seconds * fps, "clips": clips}
    meta_path.write_text(json.dumps(meta, indent=2))
    return out_dir


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--res", type=int, default=1080, choices=sorted(SIZES))
    ap.add_argument("--codec", default="h264", choices=["h264", "h265"])
    ap.add_argument("--fps", type=int, default=25)
    ap.add_argument("--seconds", type=int, default=30)
    ap.add_argument("--kbps", type=int, default=None, help="override the default bitrate")
    ap.add_argument("--force", action="store_true", help="re-encode even if the clips exist")
    a = ap.parse_args()
    if not shutil.which("ffmpeg"):
        sys.exit("ffmpeg not found on PATH")
    print(build(a.res, a.codec, a.fps, a.seconds, a.kbps, a.force))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Run a site's detector on a recording on the laptop, in the replay's detection format.

The model is built from the site's refs/refs.json exactly as make_site_model.py builds the tablet's (same averaged
visual prompts + text classes), but run in PyTorch on the GPU. Full frames are letterboxed into --size like the app
does at ref_scale (the app shows the detector its region of interest at up to 1.2 x ref_scale).

    .venv-export/bin/python tools/detect_video.py <site dir> <video> <out.json> [--fps 10] [--size 640] [--conf 0.2]

Output: {"fps", "w", "h", "classes", "frames": [{"t", "dets": {cls: [{"box": [x0,y0,x1,y1], "conf"}]}}]}, the input
of the engine replay (--detections), e.g. with --tracks to check box tracking.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("site", type=Path)
    ap.add_argument("video", type=Path)
    ap.add_argument("out", type=Path)
    ap.add_argument("--fps", type=float, default=10)
    ap.add_argument("--size", type=int, default=640)
    ap.add_argument("--conf", type=float, default=0.2)
    a = ap.parse_args()
    import cv2
    from make_site_model import build
    model, names, ref_scale = build(a.site, a.size)
    cap = cv2.VideoCapture(str(a.video))
    fps, w, h = cap.get(cv2.CAP_PROP_FPS), int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    print(f"classes {names}, ref_scale {ref_scale}, input scale {a.size / max(w, h):.2f}")
    step, i, frames, t0 = max(1, round(fps / a.fps)), 0, [], time.time()
    while True:
        ok = cap.grab()
        if not ok:
            break
        if i % step == 0:
            _, f = cap.retrieve()
            r = model.predict(f, imgsz=a.size, conf=a.conf, verbose=False)[0]
            dets: dict[str, list] = {}
            for b, c, k in zip(r.boxes.xyxy.tolist(), r.boxes.conf.tolist(), r.boxes.cls.tolist()):
                dets.setdefault(names[int(k)], []).append({"box": [round(v, 1) for v in b], "conf": round(c, 3)})
            frames.append({"t": round(i / fps, 3), "dets": dets})
        i += 1
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps({"fps": a.fps, "w": w, "h": h, "classes": names, "frames": frames}))
    print(f"{len(frames)} frames in {time.time() - t0:.0f} s -> {a.out}")


if __name__ == "__main__":
    main()

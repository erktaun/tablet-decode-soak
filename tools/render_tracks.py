#!/usr/bin/env python3
"""Draw the engine's track ids on a recording, to check box tracking by eye (engine replay --tracks output).

    python3 tools/render_tracks.py <video> <tracks.jsonl> <out.mp4> [--classes "closed box,open box"] [--sheet out.png]

Each track id keeps one colour. Prints per class how many ids were used and how long each lived: an object that
stays in view should keep one id, so more ids than objects means the tracker lost or swapped them.
--sheet also writes a contact sheet (16 frames) of the annotated video.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np


def colour(i: int):
    h = (i * 47) % 180
    return tuple(int(v) for v in cv2.cvtColor(np.uint8([[[h, 220, 255]]]), cv2.COLOR_HSV2BGR)[0, 0])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("video", type=Path)
    ap.add_argument("tracks", type=Path)
    ap.add_argument("out", type=Path)
    ap.add_argument("--classes", default="")
    ap.add_argument("--sheet", type=Path)
    a = ap.parse_args()
    want = {c.strip() for c in a.classes.split(",") if c.strip()}
    rows = [json.loads(l) for l in a.tracks.read_text().splitlines() if l.strip()]
    life: dict[tuple[str, int], list[float]] = {}
    for r in rows:
        for cls, ts in r["tracks"].items():
            for tr in ts:
                life.setdefault((cls, int(tr[0])), []).append(r["t"])
    for cls in sorted({c for c, _ in life}):
        if want and cls not in want:
            continue
        ids = sorted((i, min(v), max(v), len(v)) for (c, i), v in life.items() if c == cls)
        print(f"{cls}: {len(ids)} ids")
        for i, t0, t1, n in ids:
            print(f"  #{i:<3} {t0:6.1f}-{t1:6.1f} s  ({n} frames)")

    cap = cv2.VideoCapture(str(a.video))
    w, h = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = 1 / (rows[1]["t"] - rows[0]["t"]) if len(rows) > 1 else 10
    a.out.parent.mkdir(parents=True, exist_ok=True)
    vw = cv2.VideoWriter(str(a.out), cv2.VideoWriter_fourcc(*"mp4v"), fps, (w, h))
    shots = []
    sheet_ts = set(np.linspace(rows[0]["t"], rows[-1]["t"], 16).round(1)) if a.sheet else set()
    for r in rows:
        cap.set(cv2.CAP_PROP_POS_MSEC, r["t"] * 1000)
        ok, f = cap.read()
        if not ok:
            break
        for cls, ts in r["tracks"].items():
            if want and cls not in want:
                continue
            for tr in ts:
                i, (x0, y0, x1, y1) = int(tr[0]), map(int, tr[1:5])
                c = colour(i) if cls != "person" else (120, 120, 120)
                cv2.rectangle(f, (x0, y0), (x1, y1), c, 3)
                label = f"{cls[:6]} #{i}"
                cv2.rectangle(f, (x0, y0), (x0 + 12 * len(label), y0 + 26), c, -1)
                cv2.putText(f, label, (x0 + 3, y0 + 20), 0, 0.65, (0, 0, 0), 2)
        cv2.putText(f, f"t {r['t']:.1f} s", (10, 30), 0, 1, (0, 255, 255), 2)
        vw.write(f)
        if any(abs(r["t"] - s) < 0.06 for s in sheet_ts) and len(shots) < 16:
            shots.append(cv2.resize(f, (w * 360 // h, 360) if h > w else (480, h * 480 // w)))
    vw.release()
    if a.sheet and shots:
        while len(shots) % 8:
            shots.append(np.zeros_like(shots[0]))
        cv2.imwrite(str(a.sheet), np.vstack([np.hstack(shots[k:k + 8]) for k in range(0, len(shots), 8)]))
    print(f"wrote {a.out}" + (f" and {a.sheet}" if a.sheet else ""))


if __name__ == "__main__":
    main()

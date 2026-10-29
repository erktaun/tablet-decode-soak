#!/usr/bin/env python3
"""Build a site's detector: YOLOE with the site's classes baked in, exported to TFLite (spec §1.7).

Classes come from the site pack's refs/refs.json:
    {"visual": {"<class>": [{"image": "<file in refs/>", "boxes": [[x1, y1, x2, y2], ...]}, ...]},
     "text": ["person"]}
Visual classes: YOLOE's visual-prompt embedding of each reference box, averaged over all reference images of that
class. Text classes: the text embedding. Class order in the model = visual classes, then text classes.

    .venv-export/bin/python tools/make_site_model.py sites/pizza_trailer [--size 640] [--check-video clip.mp4]

Output: <site>/model.tflite and <site>/model.json (class names, input size, accuracy check).
The check runs sampled frames of --check-video through PyTorch and through the TFLite file (same letterboxed input as
the app) and reports how many PyTorch boxes the TFLite model reproduces (same class, IoU > 0.5).
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
import warnings
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WEIGHTS = ROOT / "build" / "export" / "yoloe-11s-seg.pt"
sys.path.insert(0, str(ROOT))
from export_models import boxes, iou  # noqa: E402  same letterbox + matching as Exp 2

warnings.filterwarnings("ignore")


def build(site: Path, size: int):
    import cv2
    import numpy as np
    import torch
    import torch.nn.functional as F
    from ultralytics import YOLOE
    from ultralytics.models.yolo.yoloe import YOLOEVPSegPredictor

    refs = json.loads((site / "refs" / "refs.json").read_text())
    vis: dict[str, list] = refs.get("visual", {})
    names_v, names_t = list(vis), list(refs.get("text", []))
    model = YOLOE(str(WEIGHTS))
    # One visual-prompt pass per reference image, with the classes present in that image.
    per_img: dict[str, list[tuple[str, list]]] = {}
    for cls, items in vis.items():
        for it in items:
            per_img.setdefault(it["image"], []).append((cls, it["boxes"]))
    emb: dict[str, list] = {c: [] for c in names_v}
    scales = []
    for img_name, entries in per_img.items():
        img = cv2.imread(str(site / "refs" / img_name))
        assert img is not None, f"can't read {img_name}"
        scales.append(size / max(img.shape[:2]))  # the reference frame is letterboxed into size x size
        local = [c for c, _ in entries]
        bxs = [b for _, bs in entries for b in bs]
        ids = [i for i, (_, bs) in enumerate(entries) for _ in bs]
        model.predict(img, refer_image=img, visual_prompts=dict(bboxes=np.array(bxs, float), cls=np.array(ids)),
                      predictor=YOLOEVPSegPredictor, imgsz=size, verbose=False)
        pe = model.model.pe.clone()  # (1, len(local), D)
        for i, c in enumerate(local):
            emb[c].append(pe[0, i])
    pe = torch.stack([F.normalize(torch.stack(emb[c]).mean(0), dim=-1) for c in names_v])[None] if names_v else None
    if names_t:
        tpe = model.get_text_pe(names_t)
        pe = tpe if pe is None else torch.cat([pe.to(tpe.device), tpe], dim=1)
    model = YOLOE(str(WEIGHTS))  # fresh model: the VP predictor leaves state behind
    model.set_classes(names_v + names_t, pe)
    return model, names_v + names_t, round(sum(scales) / len(scales), 4) if scales else None


def frames_from(video: Path, n: int, dst: Path) -> list[Path]:
    dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(video)],
                               capture_output=True, text=True, check=True).stdout)
    out = []
    for i in range(n):
        p = dst / f"f{i:03d}.jpg"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{dur * (i + 0.5) / n:.2f}", "-i", str(video),
                        "-frames:v", "1", str(p)], check=True)
        out.append(p)
    return out


def check(ref, tfl: Path, imgs: list[Path], size: int, names: list[str]) -> dict:
    from ultralytics import YOLO
    cand = YOLO(str(tfl), task="segment")
    per = {n: dict(torch=0, reproduced=0, extra=0) for n in names}
    for img in imgs:
        a, b = boxes(ref, img, size), boxes(cand, img, size)
        used = set()
        for k, box in a:
            best, bi = 0.0, None
            for j, (k2, box2) in enumerate(b):
                if k2 == k and j not in used and (v := iou(box, box2)) > best:
                    best, bi = v, j
            per[names[k]]["torch"] += 1
            if bi is not None and best > 0.5:
                used.add(bi); per[names[k]]["reproduced"] += 1
        for j, (k2, _) in enumerate(b):
            if j not in used:
                per[names[k2]]["extra"] += 1
    return per


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("site", type=Path)
    ap.add_argument("--size", type=int, default=640)
    ap.add_argument("--check-video", type=Path, help="video from one of the site's cameras, for the accuracy check")
    ap.add_argument("--check-frames", type=int, default=20)
    a = ap.parse_args()
    model, names, ref_scale = build(a.site, a.size)
    print("classes:", names)
    out = Path(model.export(format="litert", imgsz=a.size))
    dst = a.site / "model.tflite"
    shutil.move(out, dst)
    # ref_scale: how much the reference frames were shrunk into the input square when the prompts were made. The app
    # shows the detector each camera at about this scale (YOLOE's confidence drops when objects look a different size).
    info = dict(classes=names, imgsz=a.size, ref_scale=ref_scale, layout="NCHW float32 RGB 0-1, letterboxed with 114 grey",
                outputs="[1, 4+nc+32, N] (xywh normalised to the input square, class scores, mask coefs) + mask protos")
    if a.check_video:
        with tempfile.TemporaryDirectory() as td:
            imgs = frames_from(a.check_video, a.check_frames, Path(td))
            info["check"] = check(model, dst, imgs, a.size, names)
        print("check:", json.dumps(info["check"]))
    (a.site / "model.json").write_text(json.dumps(info, indent=2))
    print("wrote", dst, f"({dst.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()

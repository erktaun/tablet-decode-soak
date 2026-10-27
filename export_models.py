#!/usr/bin/env python3
"""Experiment 2, laptop side: export the detectors to TFLite and check they still detect the same things.

You don't need this to run the benchmark: bench_model.py downloads the exported models. It is here
so the models can be rebuilt (other prompts, sizes or a fine-tuned model). It needs PyTorch and
Ultralytics' LiteRT exporter, a few GB, so use a separate virtual environment:

    python3 -m venv .venv-export && .venv-export/bin/pip install ultralytics==8.4.175
    .venv-export/bin/python export_models.py                    # all models, 320 and 640, FP32 + INT8
    .venv-export/bin/python export_models.py --models yolo11n --sizes 320

Output: models/<model>_<size>_<fp32|int8>.tflite and models/manifest.json (sizes, sha256, accuracy).

FP32 models run as FP16 on the GPU delegate. INT8 is full integer quantization (float input/output),
calibrated on frames from clips/src/. Accuracy check: the same frames through PyTorch and through
the TFLite file; a PyTorch box counts as reproduced if the TFLite model finds the same class at IoU > 0.5.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import warnings
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "models"
WORK = ROOT / "build" / "export"

# name -> (weights, prompts or None). YOLOE: open-vocabulary model of the ingredient demo, prompts baked in.
# YOLO11n / YOLO26n: the smallest standard detectors (80 COCO classes), the likely production base.
MODELS = {
    "yolo11n": ("yolo11n.pt", None),
    "yolo26n": ("yolo26n.pt", None),
    "yoloe-11s": ("yoloe-11s-seg.pt", ["person", "sandwich", "gloved hand", "box"]),
}
CONF = 0.25


def frames(every_s: float, offset_s: float, dst: Path) -> list[Path]:
    dst.mkdir(parents=True, exist_ok=True)
    if not any(dst.iterdir()):
        for src in sorted((ROOT / "clips" / "src").glob("*.mp4")):
            subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-ss", str(offset_s), "-i", str(src),
                            "-vf", f"fps=1/{every_s}", str(dst / f"{src.stem}_%03d.jpg")], check=True)
    return sorted(dst.glob("*.jpg"))


def load(name: str):
    from ultralytics import YOLO, YOLOE
    weights, prompts = MODELS[name]
    if prompts:
        m = YOLOE(str(WORK / weights))
        m.set_classes(prompts, m.get_text_pe(prompts))
    else:
        m = YOLO(str(WORK / weights))
    return m


def export(name: str, size: int, prec: str, calib_yaml: Path) -> Path:
    dst = OUT / f"{name}_{size}_{prec}.tflite"
    if dst.exists():
        return dst
    m = load(name)
    kw = dict(quantize=8, data=str(calib_yaml)) if prec == "int8" else {}
    out = Path(m.export(format="litert", imgsz=size, **kw))
    shutil.move(out, dst)
    return dst


def iou(a, b) -> float:
    w = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    h = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = w * h
    return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter + 1e-9)


def letterbox(img: Path, size: int):
    """The square, gray-padded input the app produces. PyTorch would otherwise pad less than TFLite."""
    import cv2
    import numpy as np
    im = cv2.imread(str(img))
    s = size / max(im.shape[:2])
    w, h = round(im.shape[1] * s), round(im.shape[0] * s)
    out = np.full((size, size, 3), 114, np.uint8)
    y, x = (size - h) // 2, (size - w) // 2
    out[y:y + h, x:x + w] = cv2.resize(im, (w, h), interpolation=cv2.INTER_LINEAR)
    return out


def boxes(model, img, size):
    r = model.predict(letterbox(img, size), imgsz=size, conf=CONF, verbose=False, device="cpu")[0]
    return [(int(k), b) for b, k in zip(r.boxes.xyxy.tolist(), r.boxes.cls.tolist())]


def check(name: str, size: int, tfl: Path, imgs: list[Path]) -> dict:
    """Share of PyTorch boxes the TFLite model reproduces (same class, IoU > 0.5), and extra boxes."""
    from ultralytics import YOLO
    ref, cand = load(name), YOLO(str(tfl), task="segment" if MODELS[name][1] else "detect")
    n_ref = n_hit = n_extra = 0
    ious = []
    for img in imgs:
        a, b = boxes(ref, img, size), boxes(cand, img, size)
        used = set()
        for k, box in a:
            best, bi = 0.0, None
            for j, (k2, box2) in enumerate(b):
                if k2 == k and j not in used and (v := iou(box, box2)) > best:
                    best, bi = v, j
            n_ref += 1
            if bi is not None and best > 0.5:
                used.add(bi); n_hit += 1; ious.append(best)
        n_extra += len(b) - len(used)
    return {"frames": len(imgs), "torch_boxes": n_ref, "reproduced": n_hit,
            "reproduced_pct": round(100 * n_hit / max(n_ref, 1), 1),
            "mean_iou": round(sum(ious) / max(len(ious), 1), 3), "extra_boxes": n_extra}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--models", nargs="+", default=list(MODELS), choices=list(MODELS))
    ap.add_argument("--sizes", nargs="+", type=int, default=[320, 640])
    ap.add_argument("--precisions", nargs="+", default=["fp32", "int8"], choices=["fp32", "int8"])
    ap.add_argument("--no-check", action="store_true", help="skip the PyTorch comparison")
    args = ap.parse_args()
    warnings.filterwarnings("ignore")

    WORK.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(exist_ok=True)
    # Calibration and test frames come from the same 4 clips but never the same moment.
    calib = frames(1.0, 0.0, WORK / "calib" / "images" / "train")
    test = frames(3.0, 0.5, WORK / "test")
    manifest_path = OUT / "manifest.json"
    manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}

    import os
    os.chdir(WORK)  # Ultralytics downloads weights into the working directory
    for name in args.models:
        weights, prompts = MODELS[name]
        if not (WORK / weights).exists():
            local = ROOT.parent / "models" / weights
            if local.exists():
                shutil.copy(local, WORK / weights)
        classes = prompts or list(load(name).names.values())
        calib_yaml = WORK / f"calib_{name}.yaml"
        calib_yaml.write_text(json.dumps({"path": str(WORK / "calib"), "train": "images/train", "val": "images/train",
                                          "names": dict(enumerate(classes))}))
        for size in args.sizes:
            for prec in args.precisions:
                print(f"== {name} {size} {prec}", flush=True)
                tfl = export(name, size, prec, calib_yaml)
                entry = manifest.get(tfl.name, {})
                entry.update({"model": name, "size": size, "precision": prec, "prompts": prompts,
                              "bytes": tfl.stat().st_size,
                              "sha256": hashlib.sha256(tfl.read_bytes()).hexdigest()})
                if not args.no_check:
                    entry["accuracy_vs_pytorch"] = check(name, size, tfl, test)
                    print("   ", entry["accuracy_vs_pytorch"], flush=True)
                manifest[tfl.name] = entry
                manifest_path.write_text(json.dumps(manifest, indent=1, sort_keys=True))
    print(f"wrote {manifest_path}")


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Build the detector for a site set up on the tablet (spec §1.7), in one command.

Pulls the site's setup (site.json, menu.json, refs/) from the device, runs make_site_model.py on it, and pushes
model.tflite + model.json back. The site then passes the setup screen's Check.

    python3 tools/site_model_device.py --site client_kitchen [--serial X] [--check-video clip.mp4]

The pulled copy stays in out/device_sites/<site>/ (gitignored: reference frames are site footage).
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
FILES = f"/storage/emulated/0/Android/data/{PKG}/files"
PY = ROOT / ".venv-export" / "bin" / "python"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--site", required=True)
    ap.add_argument("--serial")
    ap.add_argument("--size", type=int, default=640)
    ap.add_argument("--check-video", type=Path, help="a recording from one of the site's cameras, for the accuracy check")
    a = ap.parse_args()

    adb = Adb(a.serial)
    src = f"{FILES}/sites/{a.site}"
    if not adb.exists(f"{src}/site.json"):
        sys.exit(f"no site {a.site} on the device ({src}/site.json missing): set it up in the app first")
    local = ROOT / "out" / "device_sites" / a.site
    local.mkdir(parents=True, exist_ok=True)
    for f in ["site.json", "menu.json", "refs"]:
        if adb.exists(f"{src}/{f}"):
            adb.run("pull", f"{src}/{f}", str(local), timeout=300)
    refs = json.loads((local / "refs" / "refs.json").read_text()) if (local / "refs" / "refs.json").exists() else {}
    site = json.loads((local / "site.json").read_text())
    k = site.get("classes", {})
    roles = k.get("base", []) + k.get("open_container", []) + k.get("closed_container", [])
    empty = [c for c in roles if not any(it.get("boxes") for it in refs.get("visual", {}).get(c, []))]
    if empty:
        sys.exit(f"no reference rectangles for {empty}: draw them in the app (Setup, Objects) first")
    # Classes without rectangles (added, then emptied) would have no embedding: leave them out of the model.
    refs["visual"] = {c: [it for it in v if it.get("boxes")] for c, v in refs.get("visual", {}).items() if any(it.get("boxes") for it in v)}
    refs.setdefault("text", ["person"])  # the app's default; make_site_model.py would read a missing list as none
    (local / "refs" / "refs.json").write_text(json.dumps(refs, indent=2))
    n = {c: (len(v), sum(len(it["boxes"]) for it in v)) for c, v in refs["visual"].items()}
    log(f"site {a.site}: classes {', '.join(f'{c} ({i} frames, {b} boxes)' for c, (i, b) in n.items())} + text {refs.get('text', ['person'])}")

    t0 = time.time()
    cmd = [str(PY if PY.exists() else sys.executable), str(ROOT / "tools" / "make_site_model.py"), str(local), "--size", str(a.size)]
    if a.check_video:
        cmd += ["--check-video", str(a.check_video)]
    subprocess.run(cmd, check=True)
    log(f"model built in {time.time() - t0:.0f} s")
    for f in ["model.json", "model.tflite"]:
        adb.run("push", str(local / f), f"{src}/{f}", timeout=600)
    # The edited refs.json goes back too, so the device's class list matches the model.
    adb.run("push", str(local / "refs" / "refs.json"), f"{src}/refs/refs.json")
    adb.shell(f"touch {src}/model.tflite", check=False)  # newer than the references: Check stops warning
    info = json.loads((local / "model.json").read_text())
    log(f"pushed: classes {info['classes']}, ref_scale {info.get('ref_scale')}. In the app: Setup, Check, then Start.")


if __name__ == "__main__":
    main()

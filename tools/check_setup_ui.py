#!/usr/bin/env python3
"""End-to-end check of the setup screens (plan day 4): recreate the pizza site on the phone through the UI only.

Taps through Setup like a person would (adb input, buttons found by their text with uiautomator), using the
pizza_trailer site as the answer key:
  1. Cameras: cam0 = file:pizza_trailer.mp4 (from clips/), still, background at 400 s
  2. Zones: the five zones and the ignored menu board, by tapping their corners, with names and roles
  3. Objects: classes "open box" and "pizza", rectangles drawn on the frame at 256 s
  4. Check: copy the menu from pizza_trailer
then builds the model on the laptop (site_model_device.py), compares the site the UI wrote with
sites/pizza_trailer/site.json, starts a run, types order A-2041 in the New order dialog, and checks the run
against sites/pizza_trailer/expect.json.

    python3 tools/check_setup_ui.py [--site pizza_ui] [--skip-setup] [--skip-model] [--no-run]

The phone needs the app installed and pizza_trailer pushed once (tools/check_device.py --site pizza_trailer).
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tools"))
from run_test import Adb, log  # noqa: E402
from check_device import ACT, FILES, PKG, run_check  # noqa: E402

SETUP = f"{PKG}/com.ald.ra.app.SetupActivity"
KEY = ROOT / "sites" / "pizza_trailer"


class Ui:
    """Finds views by text in uiautomator dumps and taps them."""

    def __init__(self, adb: Adb):
        self.adb = adb
        self.w, self.h = map(int, re.findall(r"(\d+)x(\d+)", adb.shell("wm size"))[-1])
        self.w, self.h = max(self.w, self.h), min(self.w, self.h)  # landscape

    def nodes(self) -> list[dict]:
        for _ in range(5):
            out = self.adb.shell("uiautomator dump /sdcard/ui.xml >/dev/null && cat /sdcard/ui.xml", check=False)
            if "<node" in out:
                break
            time.sleep(1)
        res = []
        for m in re.finditer(r"<node [^>]*>", out):
            n = m.group(0)
            g = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ""])[1]
            b = list(map(int, re.findall(r"\d+", g("bounds"))))
            res.append({"cls": g("class").split(".")[-1], "text": g("text").replace("&#10;", "\n").replace("&amp;", "&"),
                        "desc": g("content-desc"), "b": b})
        return res

    @staticmethod
    def centre(n):
        b = n["b"]
        return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2

    def tap(self, x, y):
        self.adb.shell(f"input tap {int(x)} {int(y)}")

    def find(self, text, prefix=False, cls=None, nodes=None, contains=False):
        for n in nodes if nodes is not None else self.nodes():
            ok = text in n["text"] if contains else n["text"].startswith(text) if prefix else n["text"] == text
            if ok and (cls is None or n["cls"] == cls):
                return n
        return None

    def scroll(self, down=True, dialog=False):
        x = int(self.w * (0.5 if dialog else 0.8))  # a dialog sits in the middle of the screen
        # slow and short: no fling, so rows aren't skipped
        a, b = (int(self.h * 0.75), int(self.h * 0.45)) if down else (int(self.h * 0.45), int(self.h * 0.75))
        self.adb.shell(f"input swipe {x} {a} {x} {b} 1000")
        time.sleep(0.6)

    def top(self):
        for _ in range(8):
            self.scroll(down=False)

    def tap_text(self, text, prefix=False, cls=None, wait=0.8, contains=False):
        """Tap the view with this text, scrolling the side panel down then up to find it."""
        for i in range(26):
            ns = self.nodes()
            n = self.find(text, prefix, cls, nodes=ns, contains=contains)
            if n:
                self.tap(*self.centre(n))
                time.sleep(wait)
                return n
            self.scroll(down=i < 10, dialog=any(m["text"] == "Cancel" and m["cls"] == "Button" for m in ns))
        raise RuntimeError(f"no view with text {text!r} on screen")

    def type_into(self, node, value):
        self.tap(*self.centre(node))
        time.sleep(0.3)
        self.adb.shell("input keyevent KEYCODE_MOVE_END " + " ".join(["KEYCODE_DEL"] * 60))
        self.adb.shell(f"input text '{value.replace(' ', '%s')}'")
        time.sleep(0.3)

    def edits(self):
        return [n for n in self.nodes() if n["cls"] == "EditText"]

    def editor_map(self, img_w, img_h):
        """Image pixel -> screen pixel for the frame editor (fit-centre)."""
        ed = next(n for n in self.nodes() if n["desc"] == "frame editor")
        x0, y0, x1, y1 = ed["b"]
        s = min((x1 - x0) / img_w, (y1 - y0) / img_h)
        ox, oy = x0 + ((x1 - x0) - img_w * s) / 2, y0 + ((y1 - y0) - img_h * s) / 2
        # taps exactly on the picture's edge can land on the panel next to it: stay 2 px inside
        return lambda x, y: (ox + min(max(x, 2), img_w - 3) * s, oy + min(max(y, 2), img_h - 3) * s)

    def back_keyboard(self):
        """Close the on-screen keyboard (Back closes only the keyboard while it is shown)."""
        if "mInputShown=true" in self.adb.shell("dumpsys input_method | grep mInputShown", check=False):
            self.adb.shell("input keyevent KEYCODE_BACK")
            time.sleep(0.6)


def setup(ui: Ui, adb: Adb, name: str):
    key = json.loads((KEY / "site.json").read_text())
    refs = json.loads((KEY / "refs" / "refs.json").read_text())
    cam = key["cameras"][0]
    adb.shell(f"rm -rf {FILES}/sites/{name}; mkdir -p {FILES}/clips; [ -f {FILES}/clips/pizza_trailer.mp4 ] || "
              f"cp {FILES}/sites/pizza_trailer/pizza_trailer.mp4 {FILES}/clips/")
    adb.shell(f"am start -S -n {SETUP} --es site {name}")
    time.sleep(3)

    log("cameras: cam0 from file:pizza_trailer.mp4")
    ui.top()
    e = ui.edits()
    ui.type_into(e[0], cam["name"])
    ui.back_keyboard()
    ui.tap_text("file:pizza_trailer.mp4")
    ui.top()
    ui.tap_text("Connect", wait=4)
    ui.tap_text("Freeze as still", wait=2)
    go_to(ui, 400)
    ui.tap_text("Save as background", wait=2)

    log("zones: tapping corners")
    ui.top()
    ui.tap_text("Zones", wait=2)
    to_screen = ui.editor_map(cam["width"], cam["height"])
    shapes = [(z["name"], z["role"], z["poly"]) for z in key["zones"]] + [("", "ignore", p) for p in cam.get("ignore", [])]
    for zname, role, poly in shapes:
        for x, y in poly:
            ui.tap(*to_screen(x, y))
            time.sleep(0.25)
        ui.top()
        ui.tap_text("Finish zone", wait=1.5)
        if zname:
            ui.type_into(ui.edits()[0], zname)
            ui.back_keyboard()
        ui.tap_text(role, cls="RadioButton", wait=0.4)
        ui.tap_text("OK", wait=1.5)
        log(f"  zone {zname or 'ignored area'} ({role})")

    log("objects: classes and rectangles on the frame at 256 s")
    ui.top()
    ui.tap_text("Objects", wait=2)
    roles = {"open box": "open container", "pizza": "base"}
    for cls in refs["visual"]:
        ui.tap_text("+ Add class")
        ui.type_into(ui.edits()[0], cls)
        ui.back_keyboard()
        ui.tap_text(roles[cls], cls="RadioButton", wait=0.4)
        ui.tap_text("OK", wait=2)
    go_to(ui, 256, pause_first=True)
    ui.tap_text("Freeze frame for references", wait=2)
    for cls, items in refs["visual"].items():
        ui.top()
        ui.tap_text(cls + " · ", contains=True, cls="Button", wait=1.5)
        ui.tap_text(".png: ", contains=True, cls="Button", wait=2)  # the frame just frozen
        to_screen = ui.editor_map(cam["width"], cam["height"])
        for b in items[0]["boxes"]:
            (x0, y0), (x1, y1) = to_screen(b[0], b[1]), to_screen(b[2], b[3])
            adb.shell(f"input swipe {int(x0)} {int(y0)} {int(x1)} {int(y1)} 500")
            time.sleep(1.5)
        log(f"  {cls}: {len(items[0]['boxes'])} rectangle(s)")

    log("check: copy the menu")
    ui.top()
    ui.tap_text("Check", wait=2)
    ui.tap_text("Copy menu from pizza_trailer", wait=2)
    show_check(ui)


def go_to(ui: Ui, second: float, pause_first=False):
    """Seek the recording (Recording controls) and pause there."""
    if pause_first:
        ui.tap_text("Pause/Play", wait=0.5)
    n = ui.find("go to second", cls="EditText")
    if n is None:
        ui.tap_text("go to second", cls="EditText")
        n = ui.find("go to second", cls="EditText")
    ui.type_into(n, str(second))
    ui.back_keyboard()
    ui.tap_text("Go", wait=2)
    if not pause_first:
        ui.tap_text("Pause/Play", wait=1)


def show_check(ui: Ui):
    ui.top()
    texts = [n["text"] for n in ui.nodes() if n["text"].startswith(("✖", "⚠", "Ready", "thing"))
             or re.match(r"\d+ thing", n["text"])]
    for t in texts:
        log(f"  check: {t}")
    return texts


def compare(local: Path) -> list[tuple[bool, str]]:
    """The site the UI wrote vs the answer key: same zones (corners within 3 px), flow, classes, references."""
    got, key = json.loads((local / "site.json").read_text()), json.loads((KEY / "site.json").read_text())
    out = []
    gz = {z["name"]: z for z in got["zones"]}
    for z in key["zones"]:
        g = gz.get(z["name"])
        if g is None:
            out.append((False, f"zone {z['name']} missing"))
            continue
        d = max(abs(a - b) for p, q in zip(z["poly"], g["poly"]) for a, b in zip(p, q))
        same = g["role"] == z["role"] and len(g["poly"]) == len(z["poly"]) and d <= 3 and g.get("for") == z.get("for")
        out.append((same, f"zone {z['name']}: role {g['role']}, corners within {d:.0f} px" + (f", for {g.get('for')}" if g.get("for") else "")))
    out.append((got["flow"] == key["flow"], f"flow {got['flow']}"))
    gi, ki = got["cameras"][0].get("ignore", []), key["cameras"][0].get("ignore", [])
    out.append((len(gi) == len(ki), f"{len(gi)} ignored area(s)"))
    gc = {k: v for k, v in got["classes"].items() if v}
    kc = {k: v for k, v in key["classes"].items() if v and k != "person"}
    out.append((all(gc.get(k) == v for k, v in kc.items()), f"classes {gc}"))
    gr = json.loads((local / "refs" / "refs.json").read_text())["visual"]
    kr = json.loads((KEY / "refs" / "refs.json").read_text())["visual"]
    for c, items in kr.items():
        gb = [b for it in gr.get(c, []) for b in it["boxes"]]
        kb = items[0]["boxes"]
        d = max((abs(a - b) for p, q in zip(kb, gb) for a, b in zip(p, q)), default=99)
        out.append((len(gb) == len(kb) and d <= 4, f"{c}: {len(gb)} rectangle(s), within {d:.0f} px"))
    return out


def type_order(ui: Ui):
    time.sleep(4)
    ui.tap_text("New order", wait=1.5)
    ui.type_into(ui.edits()[0], "A-2041")
    ui.back_keyboard()
    ui.tap_text("+ Prosciutto cotto", wait=0.6)
    ui.tap_text("+ Margherita", wait=0.6)
    ui.tap_text("Add order", wait=1)
    log("typed order A-2041: Prosciutto cotto + Margherita")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--site", default="pizza_ui")
    ap.add_argument("--serial")
    ap.add_argument("--skip-setup", action="store_true")
    ap.add_argument("--skip-model", action="store_true")
    ap.add_argument("--no-run", action="store_true")
    a = ap.parse_args()
    adb = Adb(a.serial)
    ui = Ui(adb)
    if not a.skip_setup:
        setup(ui, adb, a.site)
    if not a.skip_model:
        subprocess.run([sys.executable, str(ROOT / "tools" / "site_model_device.py"), "--site", a.site]
                       + (["--serial", a.serial] if a.serial else []), check=True)
    local = ROOT / "out" / "device_sites" / a.site
    local.mkdir(parents=True, exist_ok=True)
    adb.run("pull", f"{FILES}/sites/{a.site}/site.json", str(local / "site.json"))
    res = compare(local)
    adb.shell(f"am start -S -n {SETUP} --es site {a.site} --es tab Check")
    time.sleep(3)
    texts = show_check(ui)
    res.append((any(t.startswith("Ready") for t in texts), "setup Check says ready to run"))
    for ok, msg in res:
        print(("PASS  " if ok else "FAIL  ") + msg)
    bad = sum(not ok for ok, _ in res)
    print(f"\nsetup: {'ALL PASS' if bad == 0 else f'{bad} FAILED'} ({len(res)} checks)\n")
    if a.no_run:
        sys.exit(1 if bad else 0)
    adb.shell(f"am start -n {ACT}")
    time.sleep(1)
    code = run_check(adb, a.site, KEY / "expect.json", limit=60 * 11, after_start=lambda _: type_order(ui))
    sys.exit(1 if bad or code else 0)


if __name__ == "__main__":
    main()

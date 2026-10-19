#!/usr/bin/env python3
"""Experiment 0 + 1: decoder limits and the multi-stream decode soak test on an Android device.

Laptop side of the test. It
  1. builds the test clips (make_clips.py) and installs the app over adb,
  2. asks the device for its decoder limits (Experiment 0),
  3. streams the clips as looping RTSP cameras (mediamtx + ffmpeg) -- or pushes them for local mode,
  4. starts the soak run on the device and prints a status line every 30 s,
  5. pulls the logs into results/<run_id>/ and plots them.

Modes:
  wifi   RTSP over Wi-Fi from this laptop (the real setup)              [default]
  usb    RTSP over the USB cable (adb reverse) -- network path without Wi-Fi
  local  clips stored on the device -- decode only, no network

Examples:
  python3 run_test.py --minutes 30                       # 4 x 1080p H.264 over Wi-Fi, 30 min
  python3 run_test.py --mode local --minutes 30
  python3 run_test.py --caps-only                        # Experiment 0 only
  python3 run_test.py --self-test 60                     # check the sender on this laptop, no device
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import tarfile
import threading
import time
import urllib.request
import zipfile
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import make_clips

ROOT = Path(__file__).resolve().parent
PKG = "com.ald.soak"
DEV_FILES = f"/storage/emulated/0/Android/data/{PKG}/files"
APK = ROOT / "prebuilt" / "soak.apk"
MEDIAMTX_VERSION = "v1.20.1"
RTSP_PORT = 8554
INFO_PORT = 8899


def log(msg: str):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


# ---------------------------------------------------------------- adb

def find_adb() -> str:
    exe = "adb.exe" if os.name == "nt" else "adb"
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            p = Path(os.environ[env]) / "platform-tools" / exe
            if p.exists():
                return str(p)
    for p in (Path.home() / "Android" / "Sdk" / "platform-tools" / exe,
              Path.home() / "Library" / "Android" / "sdk" / "platform-tools" / exe):
        if p.exists():
            return str(p)
    found = shutil.which("adb")
    if not found:
        sys.exit("adb not found. Install Android platform-tools (see README) and put adb on PATH.")
    return found


class Adb:
    def __init__(self, serial: str | None):
        self.exe = find_adb()
        devices = [l.split()[0] for l in self._raw(["devices"]).splitlines()[1:]
                   if l.strip().endswith("device")]
        if serial:
            if serial not in devices:
                sys.exit(f"device {serial} not connected (connected: {devices})")
            self.serial = serial
        elif len(devices) == 1:
            self.serial = devices[0]
        elif not devices:
            sys.exit("No device found. Plug in the tablet, enable USB debugging and accept the prompt on screen.\n"
                     "`adb devices` must list it as 'device' (not 'unauthorized').")
        else:
            sys.exit(f"Several devices connected {devices}; pick one with --serial")

    def _raw(self, args, timeout=60, check=True) -> str:
        r = subprocess.run([self.exe, *args], capture_output=True, text=True, timeout=timeout)
        if check and r.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr.strip() or r.stdout.strip()}")
        return r.stdout

    def run(self, *args, timeout=120, check=True) -> str:
        return self._raw(["-s", self.serial, *args], timeout=timeout, check=check)

    def shell(self, cmd: str, timeout=60, check=True) -> str:
        return self.run("shell", cmd, timeout=timeout, check=check)

    def prop(self, name: str) -> str:
        return self.shell(f"getprop {name}").strip()

    def am_start(self, extras: dict[str, str]):
        args = ["shell", "am", "start", "-n", f"{PKG}/.MainActivity"]
        for k, v in extras.items():
            args += ["--es", k, str(v)]
        out = self.run(*args)
        if "Error" in out:
            raise RuntimeError(out)

    def exists(self, path: str) -> bool:
        return self.shell(f"ls {path} >/dev/null 2>&1 && echo yes || echo no", check=False).strip() == "yes"


# ---------------------------------------------------------------- sender (mediamtx + ffmpeg)

def ensure_mediamtx() -> Path:
    system = platform.system().lower()
    machine = platform.machine().lower()
    arch = "arm64" if machine in ("arm64", "aarch64") else "amd64"
    osname = {"linux": "linux", "darwin": "darwin", "windows": "windows"}[system]
    d = ROOT / "tools" / f"mediamtx_{MEDIAMTX_VERSION}"
    exe = d / ("mediamtx.exe" if osname == "windows" else "mediamtx")
    if exe.exists():
        return exe
    ext = "zip" if osname == "windows" else "tar.gz"
    name = f"mediamtx_{MEDIAMTX_VERSION}_{osname}_{arch}.{ext}"
    url = f"https://github.com/bluenviron/mediamtx/releases/download/{MEDIAMTX_VERSION}/{name}"
    log(f"downloading {url}")
    d.mkdir(parents=True, exist_ok=True)
    archive = d / name
    urllib.request.urlretrieve(url, archive)
    if ext == "zip":
        zipfile.ZipFile(archive).extractall(d)
    else:
        with tarfile.open(archive) as t:
            t.extractall(d)
    archive.unlink()
    return exe


MEDIAMTX_CONF = f"""
logLevel: warn
api: false
metrics: false
pprof: false
playback: false
rtsp: true
rtspTransports: [udp, tcp]
rtspAddress: :{RTSP_PORT}
rtmp: false
hls: false
webrtc: false
srt: false
moq: false
paths:
  all_others:
"""


class StreamProc:
    """One looping ffmpeg publisher. Tracks when frame 0 of the clip timeline was sent (t0), from
    ffmpeg's progress reports: t0 = min(wall clock - output timestamp) over the last minute."""

    def __init__(self, i: int, clip: Path, rtsp_base: str, events: list):
        self.i, self.clip, self.url = i, clip, f"{rtsp_base}/cam{i}"
        self.events = events
        self.samples: deque = deque(maxlen=120)
        self.t0_ms = 0
        self.restarts = -1
        self.proc: subprocess.Popen | None = None

    def start(self):
        self.restarts += 1
        self.samples.clear()
        self.t0_ms = 0
        cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostats", "-re", "-stream_loop", "-1",
               "-i", str(self.clip), "-c", "copy", "-f", "rtsp", "-rtsp_transport", "tcp", self.url,
               "-progress", "pipe:1"]
        self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        threading.Thread(target=self._read, args=(self.proc,), daemon=True).start()
        if self.restarts:
            self.events.append({"t_ms": int(time.time() * 1000), "stream": self.i, "event": "sender_restart"})

    def _read(self, proc):
        for line in proc.stdout:
            if line.startswith(("out_time_us=", "out_time_ms=")):  # both are microseconds
                v = line.split("=", 1)[1].strip()
                if v.isdigit() and int(v) > 0:
                    self.samples.append(time.time() * 1000 - int(v) / 1000)
                    self.t0_ms = int(min(self.samples))

    def alive(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(5)
            except subprocess.TimeoutExpired:
                self.proc.kill()


class Sender:
    def __init__(self, clips: list[Path], log_path: Path | None = None):
        if not shutil.which("ffmpeg"):
            sys.exit("ffmpeg not found on PATH (see README)")
        self.clips = clips
        self.log_path = log_path
        self.events: list = []
        self.mediamtx: subprocess.Popen | None = None
        self.streams: list[StreamProc] = []
        self.http: ThreadingHTTPServer | None = None
        self._stop = threading.Event()

    def start(self):
        exe = ensure_mediamtx()
        conf = ROOT / "tools" / "mediamtx.yml"
        conf.write_text(MEDIAMTX_CONF)
        out = open(self.log_path, "w") if self.log_path else None  # mediamtx warnings (e.g. dropped data)
        self.mediamtx = subprocess.Popen([str(exe), str(conf)], cwd=conf.parent, stdout=out, stderr=out)
        wait_port(RTSP_PORT)
        self.streams = [StreamProc(i, c, f"rtsp://127.0.0.1:{RTSP_PORT}", self.events)
                        for i, c in enumerate(self.clips)]
        for s in self.streams:
            s.start()
        self._serve_info()
        threading.Thread(target=self._watchdog, daemon=True).start()
        # Let every publisher report a few progress samples before anyone reads t0.
        deadline = time.time() + 15
        while time.time() < deadline and not all(s.t0_ms for s in self.streams):
            time.sleep(0.2)
        if not all(s.t0_ms for s in self.streams):
            self.stop()
            sys.exit("ffmpeg publishers did not start; run the ffmpeg command by hand to see the error")
        log(f"sender up: {len(self.streams)} streams on rtsp://<this laptop>:{RTSP_PORT}/cam0..")

    def _watchdog(self):
        while not self._stop.wait(2):
            for s in self.streams:
                if not s.alive():
                    log(f"sender cam{s.i} exited; restarting")
                    s.start()

    def _serve_info(self):
        sender = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                body = json.dumps({"now_ms": int(time.time() * 1000),
                                   "t0_ms": [s.t0_ms for s in sender.streams]}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *a):
                pass

        self.http = ThreadingHTTPServer(("0.0.0.0", INFO_PORT), Handler)
        threading.Thread(target=self.http.serve_forever, daemon=True).start()

    def stop(self):
        self._stop.set()
        for s in self.streams:
            s.stop()
        if self.http:
            self.http.shutdown()
        if self.mediamtx and self.mediamtx.poll() is None:
            self.mediamtx.terminate()
            try:
                self.mediamtx.wait(5)
            except subprocess.TimeoutExpired:
                self.mediamtx.kill()


def wait_port(port: int, timeout: float = 10):
    deadline = time.time() + timeout
    while time.time() < deadline:
        with socket.socket() as s:
            if s.connect_ex(("127.0.0.1", port)) == 0:
                return
        time.sleep(0.2)
    sys.exit(f"nothing listening on port {port} (did mediamtx start? is the port taken?)")


# ---------------------------------------------------------------- barcode (same layout as the app)

def read_barcode(row: bytes, cell_px: int = 10) -> int:
    luma = [row[c * cell_px + cell_px // 2] for c in range(18)]
    if luma[0] < 160 or luma[17] > 96:
        return -1
    return sum(1 << k for k in range(16) if luma[k + 1] >= 128)


def self_test(clips: list[Path], meta: dict, seconds: int) -> int:
    """Run the sender and read every stream back on this laptop: checks the RTSP path, the barcode
    and the latency arithmetic before a device is involved. Laptop decode adds its own delay."""
    sender = Sender(clips)
    sender.start()
    fps, n = meta["fps"], meta["frames"]
    clip_ms = n * 1000 / fps
    results = []

    def reader(i: int):
        w, h = 180, 4
        cmd = ["ffmpeg", "-v", "error", "-rtsp_transport", "tcp", "-fflags", "nobuffer",
               "-i", f"rtsp://127.0.0.1:{RTSP_PORT}/cam{i}",
               "-vf", "crop=iw:ih/20:0:0,scale=180:4", "-f", "rawvideo", "-pix_fmt", "gray", "-"]
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        frames = gaps = unreadable = 0
        first = last = 0.0
        prev = -1
        lats = []
        end = time.time() + seconds
        while time.time() < end:
            buf = p.stdout.read(w * h)
            if len(buf) < w * h:
                break
            now = time.time() * 1000
            first = first or now
            last = now
            frames += 1
            idx = read_barcode(buf[w * 2:w * 3])
            if idx < 0:
                unreadable += 1
                continue
            if prev >= 0:
                d = (idx - prev - 1) % n
                gaps += d if d < n // 2 else 0
            prev = idx
            t0 = sender.streams[i].t0_ms
            lats.append((now - t0 - idx * 1000 / fps) % clip_ms)
        p.kill()
        lats.sort()
        # fps over the time frames actually flowed (the reader needs a few seconds to connect)
        fps_seen = (frames - 1) * 1000 / (last - first) if frames > 1 else 0.0
        results.append((i, fps_seen, gaps, unreadable, lats))

    threads = [threading.Thread(target=reader, args=(i,)) for i in range(len(clips))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    sender.stop()
    ok = True
    for i, fps_seen, gaps, unreadable, lats in sorted(results):
        p50 = lats[len(lats) // 2] if lats else float("nan")
        mx = lats[-1] if lats else float("nan")
        log(f"cam{i}: {fps_seen:5.1f} fps  gaps {gaps}  unreadable {unreadable}  "
            f"latency p50 {p50:.0f} ms  max {mx:.0f} ms")
        ok &= fps_seen > 0.95 * fps and gaps == 0 and unreadable == 0
    log("self-test " + ("PASSED" if ok else "FAILED"))
    return 0 if ok else 1


# ---------------------------------------------------------------- device steps

def lan_ip_towards(device_ip: str) -> str:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect((device_ip, 9))
        return s.getsockname()[0]


def device_wifi_ip(adb: Adb) -> str:
    out = adb.shell("ip -f inet addr show wlan0", check=False)
    m = re.search(r"inet (\d+\.\d+\.\d+\.\d+)", out)
    if not m:
        sys.exit("The device has no Wi-Fi address (wlan0). Connect it to the same Wi-Fi as this laptop, "
                 "or use --mode usb.")
    return m.group(1)


def run_caps(adb: Adb, out_dir: Path) -> dict | None:
    run_id = time.strftime("%Y%m%d-%H%M%S") + "-caps"
    adb.am_start({"mode": "caps", "run_id": run_id})
    remote = f"{DEV_FILES}/runs/{run_id}"
    for _ in range(60):
        if adb.exists(f"{remote}/caps_done"):
            break
        time.sleep(0.5)
    else:
        log("decoder query did not finish in 30 s (is the screen unlocked?)")
        return None
    adb.run("pull", f"{remote}/caps.json", str(out_dir / "caps.json"))
    caps = json.loads((out_dir / "caps.json").read_text())
    d = caps["device"]
    log(f"device: {d['manufacturer']} {d['model']}  SoC {d.get('soc')}  Android {d['release']}  "
        f"RAM {d['ram_mb']} MB  {d['cores']} cores")
    for p in caps["instance_probe"]:
        log(f"  {p['decoder']} {p['size']}: {p['started']} decoders started at once "
            f"(device reports max {p['reported_max']})")
    for dec in caps["decoders"]:
        if dec["hardware"] and dec["type"] in ("video/avc", "video/hevc"):
            s = dec.get("sizes", {}).get("1920x1080", {})
            log(f"  {dec['name']}: 1080p {s.get('achievable_fps') or 'n/a'} fps achievable, "
                f"perf points {', '.join(dec.get('performance_points', [])[:3]) or 'n/a'}")
    return caps


def wait_for_device(adb: Adb):
    """The tablet may be unplugged during a long run (e.g. moved to a wall charger); wait for it."""
    told = False
    while adb.run("get-state", check=False, timeout=15).strip() != "device":
        if not told:
            log("the device is not connected: plug the USB cable back in to collect the results")
            told = True
        time.sleep(5)


def summarize_line(line: str) -> str:
    try:
        m = json.loads(line)
    except json.JSONDecodeError:
        return "(no metrics yet)"
    el = int(m["elapsed_s"])
    s = "  ".join(f"s{x['id']} {x['fps']:4.1f}fps d{x['dropped']} g{x['gaps']}" for x in m["streams"])
    h = m.get("thermal_headroom")
    return (f"{el // 60:3d}m{el % 60:02d}s  {s}  | thermal {m.get('thermal_status', '?')} "
            f"headroom {h if h is None else round(h, 2)}  batt {m.get('battery_temp_c')}°C {m.get('plugged')}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mode", default="wifi", choices=["wifi", "usb", "local"])
    ap.add_argument("--streams", type=int, default=4)
    ap.add_argument("--res", type=int, default=1080, choices=sorted(make_clips.SIZES))
    ap.add_argument("--codec", default="h264", choices=["h264", "h265"])
    ap.add_argument("--fps", type=int, default=25, help="camera frame rate")
    ap.add_argument("--minutes", type=float, default=30, help="run length; 0 = until Ctrl-C")
    ap.add_argument("--infer-fps", type=float, default=5, help="frames per stream pulled to model size, per second")
    ap.add_argument("--infer-size", type=int, default=320)
    ap.add_argument("--transport", default="tcp", choices=["tcp", "udp"], help="RTSP transport (wifi mode)")
    ap.add_argument("--sink", default="gl", choices=["gl", "yuv", "private"],
                    help="gl = GPU downscale + readback (default, realistic); yuv = full frames to CPU "
                         "(crashes on some Qualcomm devices); private = decode only (diagnostic)")
    ap.add_argument("--serial", help="adb serial when several devices are connected")
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--caps-only", action="store_true", help="only run Experiment 0 (decoder limits)")
    ap.add_argument("--self-test", type=int, metavar="SECONDS", help="check the sender on this laptop; no device")
    ap.add_argument("--tag", default="", help="free text added to the run id, e.g. 'charger-on'")
    ap.add_argument("--stop", action="store_true", help="stop a run on the device (app + sampler)")
    ap.add_argument("--collect", action="store_true",
                    help="copy every finished or stopped run from the device into results/ and report it")
    a = ap.parse_args()

    if a.stop or a.collect:
        adb = Adb(a.serial)
        if a.stop:
            stop_device(adb)
        if a.collect:
            collect(adb)
        return

    clip_dir = make_clips.build(a.res, a.codec, a.fps, 30, None)
    meta = json.loads((clip_dir / "meta.json").read_text())
    if a.streams > len(meta["clips"]):
        sys.exit(f"only {len(meta['clips'])} source clips; add more to clips/src/ for more streams")
    clips = [clip_dir / c for c in meta["clips"][:a.streams]]

    if a.self_test:
        sys.exit(self_test(clips, meta, a.self_test))

    adb = Adb(a.serial)
    run_id = "-".join(x for x in (time.strftime("%Y%m%d-%H%M%S"), a.mode, f"{a.streams}x{a.res}p",
                                  a.codec, f"in{a.infer_size}", a.tag) if x)
    out = ROOT / "results" / run_id
    out.mkdir(parents=True)
    log(f"device {adb.serial}: {adb.prop('ro.product.manufacturer')} {adb.prop('ro.product.model')}, "
        f"Android {adb.prop('ro.build.version.release')}")

    if not a.no_install:
        if not APK.exists():
            sys.exit(f"{APK} missing; build it (README) or pass --no-install")
        log("installing app (accept the prompt on the device if one appears)")
        adb.run("install", "-r", str(APK), timeout=300)

    # Exempt the app from Android battery optimisation (Doze). Vendor freezers (Xiaomi) also need the
    # in-app battery setting described in the README; the report flags any pause either way.
    adb.shell(f"dumpsys deviceidle whitelist +{PKG}", check=False)
    log("Experiment 0: asking the device for its decoder limits")
    run_caps(adb, out)
    if a.caps_only:
        log(f"saved {out / 'caps.json'}")
        return

    extras = {"run_id": run_id, "streams": a.streams, "minutes": a.minutes, "infer_fps": a.infer_fps,
              "infer_size": a.infer_size, "sink": a.sink, "clip_frames": meta["frames"],
              "clip_fps": meta["fps"], "width": meta["width"], "height": meta["height"]}
    sender = None
    host = None
    logcat = None
    sampler_log = f"/data/local/tmp/soak_{run_id}.log"
    remote_run = f"{DEV_FILES}/runs/{run_id}"
    try:
        if a.mode == "local":
            remote = f"{DEV_FILES}/clips/{clip_dir.name}"
            adb.shell(f"mkdir -p {remote}")
            for c in clips:
                log(f"pushing {c.name} to the device")
                adb.run("push", str(c), f"{remote}/{c.name}", timeout=600)
            extras.update(source="file", urls=",".join(f"{remote}/{c.name}" for c in clips))
        else:
            if a.mode == "usb":
                host = "127.0.0.1"
                adb.run("reverse", f"tcp:{RTSP_PORT}", f"tcp:{RTSP_PORT}")
                adb.run("reverse", f"tcp:{INFO_PORT}", f"tcp:{INFO_PORT}")
                transport = "tcp"  # adb reverse forwards TCP only
            else:
                dev_ip = device_wifi_ip(adb)
                host = lan_ip_towards(dev_ip)
                transport = a.transport
                log(f"device Wi-Fi {dev_ip}, laptop {host}")
            sender = Sender(clips, out / "mediamtx.log")
            sender.start()
            extras.update(source="rtsp", transport=transport,
                          urls=",".join(f"rtsp://{host}:{RTSP_PORT}/cam{i}" for i in range(a.streams)),
                          info_url=f"http://{host}:{INFO_PORT}/info")

        (out / "host.json").write_text(json.dumps({
            "args": vars(a), "clips": meta, "host_ip": host, "laptop": platform.platform(),
            "device": {"serial": adb.serial, "model": adb.prop("ro.product.model"),
                       "android": adb.prop("ro.build.version.release")}}, indent=2))

        adb.run("push", str(ROOT / "sampler.sh"), "/data/local/tmp/soak_sampler.sh")
        adb.shell(f"setsid nohup sh /data/local/tmp/soak_sampler.sh {sampler_log} 5 "
                  f">/dev/null 2>&1 </dev/null &")
        logcat = subprocess.Popen([adb.exe, "-s", adb.serial, "logcat", "-v", "time", "-T", "1",
                                   "soak:I", "AndroidRuntime:E", "ExoPlayerImpl:*", "*:S"],
                                  stdout=open(out / "logcat.txt", "w"), stderr=subprocess.DEVNULL)

        log(f"starting run {run_id} ({a.minutes or 'until Ctrl-C'} min). Keep the device unlocked and plugged in.")
        adb.am_start(extras)
        t_end = time.time() + a.minutes * 60 + 60 if a.minutes else float("inf")
        interrupted = False
        try:
            while time.time() < t_end:
                time.sleep(30)
                try:
                    if adb.run("get-state", check=False, timeout=15).strip() != "device":
                        log("(device not connected; the run continues on the device)")
                        continue
                    if adb.exists(f"{remote_run}/done.json"):
                        break
                    last = adb.shell(f"tail -n 1 {remote_run}/metrics.jsonl", timeout=15, check=False)
                    log(summarize_line(last.strip()))
                except Exception as e:  # cable out etc.: the run continues on the device
                    log(f"(device not reachable: {e}; the run continues on the device)")
        except KeyboardInterrupt:
            interrupted = True
            log("stopping (Ctrl-C)")
        wait_for_device(adb)
        if interrupted or not adb.exists(f"{remote_run}/done.json"):
            adb.am_start({"mode": "stop"})
            time.sleep(4)
    finally:
        if sender:
            sender.stop()
            (out / "sender_events.json").write_text(json.dumps(sender.events, indent=2))
        if a.mode == "usb":
            adb.run("reverse", "--remove-all", check=False)

    if logcat:
        logcat.terminate()
    wait_for_device(adb)
    adb.shell("pkill -f soak_sampler.sh", check=False)
    adb.run("pull", f"{remote_run}/.", str(out), timeout=300)
    adb.run("pull", sampler_log, str(out / "sampler.log"), check=False)
    adb.shell(f"rm -f {sampler_log} {sampler_log}.pid", check=False)
    log(f"results in {out}")
    import plot_run
    plot_run.report(out)


def stop_device(adb: Adb):
    adb.am_start({"mode": "stop"})
    time.sleep(4)
    adb.shell("pkill -f soak_sampler.sh", check=False)
    log("stopped the run on the device")


def collect(adb: Adb):
    """For runs whose laptop script was closed (e.g. the 24 h run): copy them from the device."""
    import plot_run
    runs = [r for r in adb.shell(f"ls {DEV_FILES}/runs", check=False).split() if not r.endswith("-caps")]
    for r in runs:
        out = ROOT / "results" / r
        remote = f"{DEV_FILES}/runs/{r}"
        if (out / "summary.md").exists() and (out / "done.json").exists():
            continue
        if not adb.exists(f"{remote}/done.json"):
            log(f"{r} has not finished (still running, or the app was killed): collecting what is there")
        out.mkdir(parents=True, exist_ok=True)
        adb.run("pull", f"{remote}/.", str(out), timeout=300)
        adb.run("pull", f"/data/local/tmp/soak_{r}.log", str(out / "sampler.log"), check=False)
        log(f"collected {out}")
        plot_run.report(out)
    if not runs:
        log("no runs on the device")
    else:
        log("done; every run on the device is in results/")


if __name__ == "__main__":
    main()

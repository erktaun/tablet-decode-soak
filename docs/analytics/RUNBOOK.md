# Restaurant analytics demo: runbook

For whoever runs the demo at a client site. The steps are in order. App version 0.4.

What the demo shows:
1. A dish is built at the assembly counter, and the app ticks off its ingredients against the order.
2. The dish goes into a box, and the box is followed from camera to camera.
3. At checkout, with the box closed, the app shows which order it is and what's inside.

---

## 0. What you need

| Item | Notes |
|---|---|
| Redmi Pad 2 Pro with the app (APK: the `analytics-v0.4` release of this repo) | charger; the tablet stays plugged in |
| 2–3 IP cameras (RTSP), mounts, PoE switch, our router | cameras on our own network; the client's Wi-Fi is not enough |
| Laptop with this repo and the model-build setup (below) | needed on site, once, to build the detector after the references are drawn |
| USB-C cable, tablet to laptop | for the model build and for collecting logs |
| **An OpenAI API key** with a spending cap (set the cap in the OpenAI dashboard) | the ingredient checks call OpenAI. It goes **only on the tablet** (tablet setup, step 4), not on the laptop or in any file. About $0.014 per order; the 24 h test with a looping clip about $3 a day |
| Internet for the tablet | the ingredient checks call the Decisions API; bring a 4G hotspot as a backup |
| Printed QR labels with the order ids, about 8–10 cm wide | only if boxes may be swapped where no camera sees them (spec §4.4) |

### One-time laptop setup (do this at home, it downloads about 1 GB)
```
git clone https://github.com/erktaun/tablet-decode-soak.git && cd tablet-decode-soak
python3.12 -m venv .venv-export && .venv-export/bin/pip install ultralytics==8.4.175
# check it, and fetch the model weights, on the pizza site (about 2 min):
.venv-export/bin/python tools/make_site_model.py sites/pizza_trailer
```
Also install `adb` (Android platform-tools) and `ffmpeg`.

### One-time tablet setup
1. Settings → About → tap "OS version" 7 times → Developer options → turn on **USB debugging**. On Xiaomi, also turn on **USB debugging (Security settings)**.
2. Install the APK: download `analytics-v0.4.apk` from the repo's releases, then `adb install -r analytics-v0.4.apk`.
3. App settings → Battery saver → **No restrictions**. Without this, HyperOS freezes the app in the background and the run is invalid. The app shows "PAUSED BY THE SYSTEM" when that happens.
4. **OpenAI API key:** open the app → main screen → **API key** (top row) → paste the key (`sk-…`) → **Save**.
   - It is kept in the app's private storage, never in logs, reports or the site pack.
   - When no run is active, the status line at the top shows "API key set"; it is used from the next **Start**.
   - Without a key the app still detects and tracks, but the ingredient checks are off ("API key NOT SET (checks off)"), so dish verdicts mean nothing.
   - To replace the key, paste a new one; **Remove** deletes it.
5. **Rehearsal at home:** `python3 tools/check_device.py --site pizza_trailer --no-build` (with the APK in `android/analytics/build/outputs/apk/debug/analytics-debug.apk`, or add `--no-install` after installing it yourself). It plays the pizza clip on the tablet, about 8 minutes, and must end with `ALL PASS`. The `detector: ... ms` line is the tablet's speed (under about 100 ms is fine). Please send us that output: it is the first Redmi measurement.

---

## 1. On site: cameras (about 30 min)

1. Mount the cameras:
   - **Assembly camera:** top-down over the make line. The dish should be **at least 250 px across** in the picture.
   - **Box cameras** (packing, shelf, checkout): every box should be **at least 80 px across**. If QR labels are used, the code needs about 60 px.
   - Fill the picture with the counters. In the CTO test video IMG_2495, half the frame is empty counter and the boxes are only 80–150 px wide.
   - Cameras that should hand boxes over to each other should overlap a little, or the walk between them should be short.
2. Use the **substream** (720p, 10–15 fps, H.264). The checkout camera may use the 1080p main stream if it has to read QR codes. URL paths:
   - Hikvision `/Streaming/Channels/102`
   - Dahua `/cam/realmonitor?channel=1&subtype=1`
   - Reolink `/h264Preview_01_sub`
   - Tapo `/stream2` (enable the camera account first)

   Example: `rtsp://admin:PASSWORD@192.168.1.64:554/Streaming/Channels/102`. If unsure, test the URL in VLC first.

## 2. On site: set up the site in the app (about 30–45 min)

Main screen → **Site** → **+ New site** → name it (e.g. `client_kitchen`) → the Setup screen opens. Every change is saved at once.

1. **Cameras.** For each camera:
   - Enter a name and the RTSP URL, then **Connect**.
   - When the picture shows, press **Freeze as still**. Zones and map points are drawn on this frame.
   - With the counters empty, press **Save as background**. This is only needed when closed boxes are not detected (checkout fallback).
2. **Zones.** For each camera, tap the corners of each zone, then **Finish zone**, give it a name and a role. **Whole view is one zone** is fine for a camera that only watches one thing.
   - `assembly`: the dish is built here, and ingredient checks run.
   - `packing`: the food goes into its box.
   - `hold`: an oven, shelf or rack in between.
   - `checkout`: the order leaves here.
   - `customer`: where customers wait, next to a checkout zone.
   - `ignore`: an area the detector should skip, such as a menu photo of food.
3. **Flow.**
   - Check that the zones are in the order a dish moves through them.
   - For neighbouring zones on different cameras that **don't overlap**, enter the travel time (min and max seconds).
   - Assembly mode: `packed_after` when food goes into a box at a packing zone; `in_container` when the dish is built in its container (a bowl).
4. **Objects.**
   - Add the classes, each with a role:
     - **base:** the dough, bun or bowl the dish is built on;
     - **open box:** an open box or container;
     - **closed box:** a closed box or bag.
   - For each class, from the camera that sees it: **Freeze frame for references**, then drag a tight rectangle around every instance.
   - Use 3–5 frames per class at different stages (bare / half-built / finished; open / closed).
   - For closed boxes, draw references **from every camera that tracks them**.
   - `person` needs nothing.
5. **Map** (optional): only for overlapping cameras. Tap 4 or more points on the counter top and type their floor-plan positions in metres.
6. **Menu.** Write `menu.json` on the laptop (ingredients with a one-line look, and recipes; example: `sites/pizza_trailer/menu.json`), then:
   `adb push menu.json /storage/emulated/0/Android/data/com.ald.ra/files/sites/<site>/menu.json`
7. **Build the detector.** With the tablet plugged into the laptop:
   `python3 tools/site_model_device.py --site <site>`
   It takes 1–5 min and puts the model on the tablet.
8. **Check** page: it must say **Ready to run**. Warnings are fine, but read them. Then **Start**.

## 3. Test pass before the audience (10 min)

1. Main screen → **New order**: an id and the dishes (modifiers like `no basil`, `+olives`).
2. Make one dish while watching the screen:
   - on the line: `dish_started`, then ingredients appear;
   - when it leaves: a verdict (MISSING / EXTRA, or none);
   - in the box: `unit_packed`;
   - across cameras: `unit_arrived`;
   - at checkout: the order with its contents;
   - when it's taken: `handed_over`.
3. If boxes are missed or swapped:
   - add references (Objects) from the camera that missed them;
   - rebuild (step 7);
   - press **Stop**, then **Start**.

## 4. The demo

1. **New order** for each dish *before* it is made. Orders are matched to dishes by contents, oldest order first.
2. Show the assembly camera:
   - ingredients get ticked off as they go on;
   - leave one out on purpose: the verdict says MISSING.
3. Box it. Carry it to the next camera: it keeps its order.
4. Close the box at checkout: the screen shows the order id and what's inside.
5. **Say plainly:** identical boxes swapped where no camera sees them can't be told apart. The app then shows "uncertain: A or B" and never guesses. With a QR label printed with the order id, the box is identified at checkout anyway.

## 5. If something goes wrong

| What you see | What to do |
|---|---|
| "can't play …" on Cameras | wrong URL or password, or the camera is on another network; test in VLC |
| CHECKS FAILING on the live screen | no internet on the tablet, or a bad key; switch to the hotspot |
| PAUSED BY THE SYSTEM | Battery saver is not "No restrictions" (tablet setup step 3); the run is invalid, so restart it |
| a camera shows many "dropped" | too many cameras or too high fps for the tablet; use 5 fps or fewer cameras |
| dishes or boxes not boxed in the preview | add reference frames from that camera, rebuild, restart |
| a box gets the wrong order | note the time; see section 6 |
| the app crashed | reopen it and press Start; the old run and `crashes/crash-<time>.txt` stay on the tablet; run `tools/pull_report.py` when you can |

## 6. Logs: what is saved, and what to send back

Everything stays on the tablet in `Android/data/com.ald.ra/files/`.

**Per run** (`runs/<date-time>-<site>/`), saved today:

| File | What it holds |
|---|---|
| `run.json` | site, settings, detector regions |
| `log.txt` | app log: streams, detector, errors, pauses, codes read |
| `events.jsonl` | every event as it happened (dish started, ingredients, verdicts, packed, arrived, handed over, uncertain…) |
| `events_final.jsonl` | the same events, sorted, written at Stop |
| `decision_calls.jsonl` | each ingredient check: answers, latency, cost |
| `metrics.jsonl` | every 5 s: detector speed, dropped frames, stream state and errors, checks, cost, temperature, pauses, codes |
| `orders_entered.jsonl` | the orders typed on the tablet |
| `done.json` | how the run ended |

**Per site** (`sites/<site>/`): `site.json` (cameras, zones, flow), `refs/` (reference frames and rectangles), `setup/` (stills), `background/`, `menu.json`, `model.tflite` + `model.json`.

Also saved in each run:
- `detections.jsonl`: every frame's detections. This lets us replay the run's logic on the laptop.
- `frames/<camera>/`: a still every 2 s, about 70 MB per hour per camera.
- `crops/`: the image sent for every ingredient check.
- `run.json`: also holds the start time, the app version and the device.

**Crashes:** a crash is written to `crashes/crash-<time>.txt` and into the run's folder.

**Video:** the app keeps no video. For the demo, also record the camera streams on the laptop, so we can replay everything exactly:
`python3 tools/record_cameras.py --site <site>`. It needs the laptop on the cameras' network; stop it with Ctrl-C. It writes 10-minute files named by their start time to `out/recordings/<site>/`.

**What to send back after the demo (or after a problem):**
1. The report: with the tablet plugged in, `python3 tools/pull_report.py` (add `--runs 2` for only the last two runs). It writes `out/reports/report-<time>.tar.gz` with the runs, the sites, any crashes, the system log and the tablet's details. The API key is not in it.
2. The camera recordings, if any.
3. A short note: **the time** of each problem (the run's event times are seconds since Start) and what you expected to happen.

# Restaurant analytics: build plan (2026-10-29)

Spec: `spec.md`. The plan targets a working app by the start of the week of 2026-11-02, cutting in the order given in spec §6.

## Layout: inside this repo (tablet-soak), decided 2026-10-29
The analytics app is Exp 3 of the feasibility plan (the full workload, left running for 24 h), so it shares this repo's stream decoding and its soak tooling (`run_test.py`, `sampler.sh`, `plot_run.py`).

```
android/
  app/        soak app (Exp 0-2), unchanged
  engine/     pure Kotlin (JVM) logic, no Android: runs on the laptop (replay, tests) and in the app
  stream/     (day 3) decoding shared by both apps, moved out of app/
  analytics/  (day 3) the demo app: stream + detector + engine + UI + soak metrics
sites/pizza_trailer/   the pizza scene as a site pack (the regression test)
docs/analytics/        spec.md, plan.md
tools/                 make_site_model.py (refs -> model.tflite), check_device.py (phone smoke test)
```

**Why the engine is a separate module:** all of the logic runs and is tested on the laptop, so the phone only adds camera input, the detector and the UI.

## Testing (decided 2026-10-29)
1. **On the laptop, every change:** `./gradlew :engine:test` (unit tests + scenario tests per swap case + the pizza golden replay). Nothing moves on while anything is red.
2. **On the user's phone, each iteration:** `tools/check_device.py` builds, installs, replays a test clip on the device (decode -> GPU detector -> engine -> UI), pulls `events.jsonl` + screenshots + logcat, and gives PASS/FAIL with tolerances, plus ms/frame. Xiaomi asks for a tap on the phone per install.
3. **Manual 5-minute checklist at the end of each day:** setup screens, live view, kill and reopen, screen locked for 1 min.
4. The CTO's recordings (spec §7) become golden replay tests once they arrive.
5. **Speed counts only on the Redmi.** The phone is a flagship, so it only proves things work. One Redmi run before the demo is a must.
6. Commit locally at every green state. No push without the user's OK. The repo is public for now; the user may make it private, and no keys ever go in it.

## Order of work

| Day | Work | Done when |
|---|---|---|
| 1 | engine: config, geometry, ByteTrack + Kalman, flow and zones, ingredient station, packing, checkout; replay of the pizza clip from the Python detection cache | the Kotlin `events.jsonl` matches the Python one on the pizza clip (same events, ±1 s) |
| 2 | engine: custody (parked/carried), transit queue, shared map, referee (off), identifier hook | synthetic multi-camera test (crops of the pizza clip as 2–3 cameras) passes; unit tests for the swap rules |
| 3 | app: camera input from tablet-soak, TFLite detector + NMS, engine wired in, live view + order panel; `make_site_model.py` | the pizza clip plays from a file on the user's phone with live events |
| 4 | app: setup screens (cameras, zones, flow, references, map taps), Decisions API on device, QR reading (ML Kit) | a new site set up on the phone from scratch, without touching JSON |
| 5 | tablet build for the CTO, README/runbook, buffer; first run on the CTO's recordings (spec §7) if they've arrived | the CTO can install and run it alone |

### Day 2 broken down (the algorithmic core)
1. **Order matching over every pairing** of finished-but-not-packed dishes and open order items (spec §3.7). Day 1 matches greedily per dish.
2. **Closed-container path:** bind the order at packing to a closed-container track; units followed into checkout by track; `order_complete`, `order_summary`, hand-over.
3. **Custody state machine** (§4.2): parked, carried by person X, put down at Q, in transit, uncertain {candidates}.
4. **Transit** (§4.3): travel window + container type + oldest first; uncertain when there are several candidates.
5. **Overlap without the map:** a box seen in both cameras at the same moment is the same unit.
6. **Shared map:** homography from 4+ points per camera (DLT, plain Kotlin). **Decided:** one counter-height homography per camera (boxes sit on counters). Floor homography for people is left out until needed. Matching across cameras by a distance tolerance (0.5 m) plus time (1 s).
7. **Referee, off by default** (§4.5): triggers, labelled frames (drawn with java.awt on the laptop; Android Canvas on day 3), pairwise questions, margin 0.3, `referee_calls.jsonl`.
8. **Identifier hook:** a code read on a container overrides everything else.
9. **Tests:** unit tests per swap scenario (spec §4.4 table); synthetic multi-camera run from pizza-clip crops.

**Moved earlier (first thing on day 2):** verify that YOLOE with *visual-prompt* classes exports to TFLite and gives the same detections as PyTorch. Exp 2 only exported text-prompt classes, and everything on the tablet depends on this.

### Also in scope (spec items the table above didn't name; added 2026-10-29)
| Day | Item | Spec |
|---|---|---|
| 2 | Closed-container path: binding at packing to a closed-container track, units followed into checkout by track, `order_complete`, `order_summary` | §4.1, §5 |
| 2 | Assembly `mode: in_container` (the base is the unit) and the done signals `packed` and `tap` | §3 |
| 3 | Zone occupancy computed on the device against a background frame (the fallback when closed boxes aren't detected) | §1.2, §4.1 |
| 3 | Logs on the device: `events.jsonl`, `decision_calls.jsonl`, `referee_calls.jsonl`, saved crops; pulled with adb | §3, §4.5 |
| 4 | Order entry on the tablet (type an order, or load a JSON list) | §1.5 |
| 4 | Background-frame capture in setup | §1.2 |
| 4 | Checkout camera on the 1080p main stream when reading QR codes | §1.1, §4.4 |

**Not planned (optional or cut first, per spec):** portion check, ONVIF discovery, HTTP MJPEG cameras, editing the menu on the tablet (`menu.json` is prepared on the laptop).

## Status
- **Day 1, done 2026-10-29.** `android/engine/` contains config, geometry, ByteTrack + Kalman (+ distance pass), the assembly, packing and checkout stations (occupancy fallback), the Decisions client and the laptop replay. Tests: 2 tracker unit tests, plus the pizza parity test.
  - Parity with Python (same detections and answers): same verdicts, packing at 317/360.8, hand-over at 373. Dish 2's sauce is confirmed 10 s later, because a live system can't check before the dish is confirmed.
  - Spec defaults with the live API (check every 3 s, base still 1 s): all events correct, and confirmations come earlier (ham at 126 s vs 135 s). 104 checks, p50 0.42 s, $0.014/order.
  - Run (from `android/`): `JAVA_HOME=~/Documents/libs/android-studio/jbr ./gradlew :engine:installDist && engine/build/install/engine/bin/engine --site ../sites/pizza_trailer --detections engine/src/test/resources/pizza_trailer/detections.json --checker live --env ../../.env --video <cam_full.mp4> --out ../out/pizza_live`

- **Day 2, in progress (2026-10-29).**
  - **Model export verified.** YOLOE with the pizza clip's visual-prompt classes exports to TFLite (`tools/make_site_model.py`, 40.6 MB) and reproduces 100% of PyTorch's boxes on 20 frames (open box 56/56, pizza 37/37, person 31/31).
  - **Order matching over every pairing.** `dish_reassigned` when a later dish fixes an earlier tie. Two unit tests.
  - **Custody done and tested** (`Custody.kt`: parked, carried, transit, shared map, QR override, checkout hand-over). `CustodyTest` has 8 scripted scenes, one per row of the spec §4.4 table. All pass; disabling the carried rule makes 3 of them fail.
  - **Shared map decided:** one counter-height homography per camera; no floor homography until a recording needs it (spec §4.3).
  - **Referee built, off by default** (`Referee.kt`, spec §4.5).
    - Triggers: "uncertain" binding and "crossed" boxes.
    - The engine decides what to draw and applies the answer; the host draws (`Painter`) and calls (`RefereeRunner`), so the same code runs on Android.
    - The Decisions client is shared with the ingredient checker; cache keys are unchanged.
    - 5 tests (`RefereeTest`): fixes the out-of-view swap whether the answer comes before or after the second box; stays uncertain when it can't tell; off by default; corrects crossed tracks; pairing enumeration.
    - Live probe: the multi-image request works (3.8 s, $0.00015); it told two pizzas apart by toppings. Identical boxes still need R3.
  - **Synthetic two-camera run done.**
    - `sites/pizza_trailer_2cam`: the clip as two cropped cameras (make line + oven | boxing + pass + customer), linked by a travel window. Cameras take a `crop` field (replays and demos: one video as several cameras).
    - Golden test: the same events as one camera with recorded answers.
    - Live API run: all correct, 108 checks, $0.014.
    - This clip has no closed boxes, so custody across cameras is covered by the scripted tests only, until the CTO's recordings.
  - **Day 2 done.** Tests: 20, all green.
  - **640 px finding.** The tablet runs the detector at 640; the Python demo ran at 1280. On this wide clip (pizza about 80 px) a full-frame 640 input breaks the make line and loses the customer. Two changes:
    - The detector runs on a **region of interest = the union of the camera's zones** (app default, to build on day 3; tested in Python with `roi` in the scene config).
    - Rules that don't depend on resolution:
      - a dish reappearing at the same spot within 30 s resumes (`dish_resumed`, verdict withdrawn);
      - empty dishes don't take an order slot;
      - the check gate is relative to the dish's best confidence (0.5×);
      - "dish finished" after 10 s unseen instead of 6.
  - **Results with the defaults and live API:**
    - 1280 full frame: all correct, 108 checks, $0.014.
    - 640 + region of interest: packing, hand-over and both recipes correct, but one **false "extra salami"** on the ham pizza (3 checks in a row at 112–120 s while ham was being laid on an 80 px pizza) and one early verdict withdrawn.
    - This clip is outside the spec's camera guidance (dish ≥ 250 px), so expect this kind of error at 640 when the dish is small. Fixes: place the camera closer, or use a bigger model input (960/1280, 2.25–4× the compute).

- **Day 3, in progress (2026-10-29).** Android app `android/analytics` (ExoPlayer → GL readback of the zones' region → LiteRT YOLOE on the GPU → engine; ingredient checks on a 4-thread pool; foreground service for long runs) and `tools/check_device.py` (build, install, push the site pack and key, run, pull, PASS/FAIL against `sites/<site>/expect.json`).
  - **First phone run (Xiaomi 24129PN74G, SM8750): ALL PASS** on `pizza_trailer` (7 checks: both verdicts, both packings, hand-over, no false alarms). Detector on the GPU 45 ms p50, 2150 frames at 5 fps with 0 dropped, 95 checks (p50 0.6 s, $0.013), no thermal throttling. No false "extra salami" this time.
  - **Two engine fixes found by the phone** (laptop and phone detections differ by one frame here, which was enough):
    - A dish slid along the line overlapped its last box by IoU 0.29 (< 0.3) and became a new base, which then "resumed" the finished dish in that spot: the two pizzas swapped records. Bases now also continue on a short jump (seen ≤ 0.6 s ago, centre moved ≤ 1 box size; `baseJumpS`). Test: `AssemblyTest`.
    - Recipe ties now go to fewer missing ingredients (sauce + mozzarella alone was matched to "Prosciutto, ham missing"). Test: `OrderMatchingTest`.
    - Replaying the phone's own detections on the laptop gives the same correct events. Tests: 21, all green.
  - The app shows "CHECKS FAILING" when the API can't be reached (the phone's Wi-Fi was off on the first try), and metrics count `check_errors`.
  - **Two-camera site on the phone** (`pizza_trailer_2cam`: the clip cut into two cameras, both decoded once, 4300 detector frames, 0 dropped). It found three more problems:
    - **Xiaomi froze the app** 5 s after Home was pressed, despite the foreground service (91 s lost). `check_device.py` now exempts the app from Doze, the app counts pauses (`pauses`, `paused_s` in metrics, "PAUSED BY THE SYSTEM" on screen) and the check fails a paused run. The phone also needs app settings → Battery saver → **No restrictions** (as for the soak app).
    - **Detector scale.** The two small camera regions were enlarged 1.4–1.5× into the 640 input, and YOLOE's confidence halved. It is best near the scale the references had when the prompts were made (0.5 here). `make_site_model.py` now writes `ref_scale` to model.json, and the app uses at most 1.2 × that (spec §2).
    - **Packing gap 3 s → 6 s.** At the new scale, folding a lid hid a boxed pizza for 3.6 s; the restarted episode took the next dish (still in the oven). `packing_unmatched` is now reported once per episode, not on every frame. Test: `PackingTest`. Tests: 22.
  - Replaying the phone's detections on the laptop with the fixes gives correct packing and hand-over for both one-camera phone runs. One replay had a false "extra mushrooms" on the 80 px Prosciutto (3 wrong "yes" answers in a row): the same small-dish weakness as the "extra salami" at 640.
  - **Final phone runs with all fixes: ALL PASS on both sites** (8 checks each, including "never paused"). One camera: 2150 frames, 0 dropped, detector 44 ms p50, 98 checks $0.013. Two cameras: 4300 frames, 0 dropped, 43 ms p50, 98 checks $0.013. No thermal throttling.
  - Still to do on day 3: Android painter for the referee (optional, it is off by default).

- **Day 4 (2026-10-29).**
  - **Setup screens** (`SetupActivity`; main screen: Site → + New site → Setup): Cameras (URL or `file:` recording, connect, freeze a still at stream resolution, save the background; seek buttons for recordings), Zones (tap corners or whole view; name + role; `ignore` areas), Flow (order, travel times between cameras, assembly mode and done signal), Objects (classes with roles; freeze frames and drag rectangles), Map (optional counter points with plan coordinates), Check (errors and warnings, copy a menu from another site, Start). Every change is saved to the site pack at once.
  - **Site check** in the engine (`SitePack.kt`, `checkSite`), so it is unit-tested on the laptop. It caught a real bug on the first phone pass: `refs.json` was saved without `"text": ["person"]`, so the model had no person class (now always written).
  - **Model from the laptop in one command:** `tools/site_model_device.py --site <name>` pulls the setup, builds (about 1 min here), pushes the model back.
  - **Order entry:** New order on the live screen (id + recipes from the menu + modifiers); the order comes in at the current stream time and is kept in the run's `orders_entered.jsonl`. `orders.json` is now optional.
  - **QR reading** (ML Kit, `Codes.kt`): checkout cameras every 0.5 s, on closed-box crops or the checkout zones grown by half their size (the zone crop alone cut off a QR on a box sticking out of the zone). `code_read` events; the occupancy fallback hands over the order the code names (`by: code`). Tests: `CheckoutCodeTest` (2). Tests: 29.
  - **QR on the phone: ALL PASS** (`sites/pizza_qr`: the pizza clip with a 56 px QR "A-2041" pasted on the box at the pass, 362–373 s). Read 0.4 s after it appeared (48 px code area), 18 reads in 11 s, hand-over decided by the code. Detector 45 ms p50, 0 dropped, 102 checks $0.014.
  - **Setup-by-UI check** (`tools/check_setup_ui.py`): adb taps the whole setup of the pizza site as a new site `pizza_ui` (camera, 5 zones + ignored area, 2 classes with rectangles, menu copy), with no JSON touched. The site it writes matches `sites/pizza_trailer` (all zone corners and rectangles within 1 px, same flow and classes).
    - Then it builds the model with `site_model_device.py`, Check says "Ready to run", and the run gets order A-2041 typed in the New order dialog: **ALL PASS** (setup 11/11 and run 8/8: both verdicts, both packings, hand-over of A-2041, no false alarms, never paused). Detector 44.7 ms p50, 0 of 2150 dropped, 96 checks $0.013.
    - Needs Xiaomi "USB debugging (Security settings)" on the phone for adb input (also removes the install tap).
  - **Not yet tested:** a live RTSP camera in the app (everything so far ran from recordings), and the Redmi tablet.

- **Day 5 (2026-10-29), hand-over to the CTO.**
  - **Runbook:** `RUNBOOK.md` (preparation, cameras, setup, test pass, demo, troubleshooting, logs and what to send back).
  - **Logging for remote fixing** (app 0.4):
    - every run keeps detections per frame, a still per camera every 2 s and every check image;
    - `run.json` has the start time, app version and device;
    - crashes go to `crashes/`;
    - `tools/pull_report.py` packs runs, sites, crashes, the system log and device details into one file (no API key);
    - `tools/record_cameras.py` records the RTSP streams on the laptop for exact replays.

    Phone check with 0.4: ALL PASS, 11 MB for the 7-minute pizza run.
  - **API key field** in the app (main screen → API key); no adb needed.
  - **For the CTO:** the Redmi rehearsal (first Redmi speed numbers) and the 24 h test (Exp 3).
  - **In progress:** box tracking and the referee on the CTO's two test videos (IMG_2494/2495). First pass with minimal references was poor (32 ids for ~8 closed boxes, small far boxes missed); being redone with proper references and a region of interest (`tools/detect_video.py`, `tools/render_tracks.py`, replay `--tracks`).

## Risks
- **Closed-box detection on the client's boxes** (the pizza clip never detected them). We only find out from recording R1.
- **Redmi detector speed:** Exp 2 hasn't been run. The plan assumes about 30 inferences/s at 640.
- **No multi-camera footage until the CTO records it.** Until then, synthetic crops stand in for multiple cameras.
- **Time:** about 6–7 days of work against about 4. Cut in the order in spec §6.

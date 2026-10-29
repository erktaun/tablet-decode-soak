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
6. **Shared map:** homography from 4+ points per camera (DLT, plain Kotlin). **Open question:** one homography covers one flat surface only, so the counter (boxes) and the floor (people) each need their own; carried boxes are off both. Proposal: a counter homography plus an optional floor homography, and matching across cameras by a distance tolerance (about 0.5 m) plus time.
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

## Risks
- **Closed-box detection on the client's boxes** (the pizza clip never detected them). We only find out from recording R1.
- **Redmi detector speed:** Exp 2 hasn't been run. The plan assumes about 30 inferences/s at 640.
- **No multi-camera footage until the CTO records it.** Until then, synthetic crops stand in for multiple cameras.
- **Time:** about 6–7 days of work against about 4. Cut in the order in spec §6.

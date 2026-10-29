# Restaurant Analytics: Kotlin/tablet spec (draft 2, 2026-10-29)

Two pipelines, one app on the Redmi Pad 2 Pro.

- **Ingredient tracking:** an order comes in, and the app checks it is assembled correctly.
- **Box tracking:** the finished order is followed across zones and cameras. At checkout, the app says which order it is and what's inside.

This is a live demo at a client site with our own cameras. Everything specific to the site lives in a **site pack** the CTO creates at setup; the APK is the same everywhere.

## Decisions taken (2026-10-29)
- The five roles (`assembly / packing / hold / checkout / customer`) are enough for now.
- The default done signal is `leaves_zone`.
- The **shared map is in scope** (§4.3).
- Cases where boxes are swapped out of sight need an **identifier** (QR code or order label). Without one, the app reports "uncertain"; it never guesses. See §4.4.
- Fixed cameras only for now. Head-mounted cameras come later.
- The Decisions API "track referee" (§4.5) gets built but stays **off by default** until it's tested on the staged swap clip.
- Identifier: **an order-printed QR, read at the checkout camera** (default, still to confirm with the CTO). The alternative is reusable tags, which the packing camera would then also have to read.
- The CTO records the test clips (§7).

---

## 0. Terms

| Term | Meaning |
|---|---|
| **Camera** | One video stream into the tablet. |
| **Zone** | An area where something happens: **a whole camera view** or **a polygon drawn inside one**. A camera holds one or more zones. |
| **Role** | What the app does in a zone: `assembly`, `packing`, `hold`, `checkout` (+ optional `customer`). |
| **Flow** | The ordered list of zones an order passes through. It can be as short as `assembly → checkout`. |
| **Assembly base** | What the ingredients are put **onto**: pizza dough, bottom bun, tortilla, sandwich roll, bowl, tray. It is the object watched during assembly. |
| **Container** | What the finished food leaves in: pizza box, burger box, bag, bowl with lid. It can be the base itself (e.g. a bowl). |
| **Unit** | One container (or base) carrying an order id and a contents list from assembly on. One order can have several units. |
| **Track** | The same object followed frame to frame inside **one** camera. |
| **Custody** | Where a unit is now, or who has it: on a surface, carried by a person, in transit between cameras, at checkout. |

---

## 1. Setup: what the CTO provides

Steps 1–5 happen on the tablet; step 6 is one laptop command. The result is the **site pack**: `site.json`, `menu.json`, `model.tflite` and the reference images.

### 1.1 Cameras

| Input | Meaning | Example |
|---|---|---|
| `name` | label | `cam_assembly` |
| `url` | stream address with login | `rtsp://admin:pass@192.168.1.64:554/Streaming/Channels/102` |
| `ignore` | optional polygons the detector skips | a menu board with a pizza photo |

**Connection**
- v1 supports **RTSP (H.264/H.265)** and **local video files** for replay.
- Almost every IP camera that isn't cloud-only speaks RTSP; all **ONVIF Profile S** cameras do.
- Substream URL paths for common brands:

  | Brand | Path | Note |
  |---|---|---|
  | Hikvision | `/Streaming/Channels/102` | |
  | Dahua | `/cam/realmonitor?channel=1&subtype=1` | |
  | Reolink | `/h264Preview_01_sub` | |
  | Tapo | `/stream2` | enable the camera account first |

- **Recommended stream:** substream, 720p, 10–15 fps, H.264. Exp 0 showed the tablet decodes 16 streams at 720p, and 4 at 1080p is near its limit.
  - Exception: the **checkout camera** may use the 1080p main stream if it has to read QR codes (§4.4).
- **Network:** our own router, cameras on a PoE switch, tablet on 5 GHz or USB-C Ethernet. The office Wi-Fi failed at about 3 Mbps.
- **Clock:** frames are stamped by the tablet at decode, so all cameras share one clock. Skew between cameras is about 0.2–1 s; the transit windows absorb it.

**Optional extras**

| Extra | What it adds | Effort |
|---|---|---|
| ONVIF discovery | find cameras on the network and fetch their RTSP URL | 0.5–1 day |
| HTTP MJPEG | very cheap cameras | 0.5 day |

**Not supported**
- Cloud-only cameras: Ring, Nest, Blink, and Wyze without RTSP firmware.
- USB webcams.
- Head-mounted cameras (later).

**Placement**
- Assembly camera top-down, with the base **≥ 250 px** across in the stream.
- Containers **≥ 80 px** across in every camera that tracks them.

### 1.2 Zones and flow

1. **Draw zones.** For each camera, choose **"whole view is one zone"** or **draw polygons** by tapping corners on a still frame. Several zones per camera are allowed.
2. **Name each zone and give it a role:**
   - `assembly`: the base is built here and ingredient checks run.
   - `packing`: food goes into its container and the order is bound to it.
   - `hold`: anything in between (oven, heat shelf, rack, conveyor). The app only records presence.
   - `checkout`: the order leaves here (counter, window, courier shelf). The app shows the order and its contents.
   - `customer` (optional, attached to a checkout zone): where customers or couriers stand. Used to confirm the hand-over.
3. **Order the zones into the flow.** Units move **forward** only and may skip zones.
4. **Set the link between each pair of neighbouring zones:**
   - Same camera: the movement is seen directly. Nothing to enter.
   - Different cameras that overlap: the hand-over happens in the overlap. Use the shared map (§4.3).
   - Different cameras with no overlap: enter a **travel time range**, e.g. 2–30 s (default 0–60).
5. **Capture a background frame** per camera with the counters empty. This is used only by the fallback in §4.1.
6. **Shared map** (§4.3): for each camera, tap 4 points on the counter or floor and the same 4 points on the floor plan.

**Example flows**

| Setup | Flow |
|---|---|
| Pizza trailer (1 camera, 4 drawn zones) | `make_line (assembly) → oven (hold) → boxing (packing) → pass (checkout)`, plus `outside (customer)` |
| CTO's setup (3 cameras, whole view each) | `cam1 (assembly) → cam2 (hold) → cam3 (checkout)` |
| Minimal (2 cameras) | `cam1 (assembly, mode in_container) → cam2 (checkout)` |

### 1.3 Objects to recognise (references)

| Class | What it is | What the CTO gives |
|---|---|---|
| `base` | the assembly base: dough, bun, tortilla, bowl… | a name, plus **3–5 still frames from the assembly camera** with a rectangle around the base, at different stages (bare, half-built, finished) |
| `open_container` | an open box or empty container | a name, plus 3–5 frames from the packing camera, with a rectangle drawn |
| `closed_container` | a closed box, a bag or a lidded bowl | a name, plus 3–5 frames **from each camera that tracks it**, with a rectangle drawn |
| `person` | staff, customer, courier | nothing; this class uses a text prompt only |

- References are captured on the tablet: freeze a live frame and drag a rectangle.
- Several base or container types are allowed (e.g. a large and a small box). Types also help tell units apart.

### 1.4 Menu (`menu.json`)

| Field | Meaning | Example |
|---|---|---|
| `ingredients[].name` | the ingredient | `cooked ham` |
| `ingredients[].look` | one line on how it looks on the base | `thin pink slices or strips, larger than a coin` |
| `ingredients[].confusable_with` | optional: what it gets mixed up with | `salami (dark red, round)` |
| `ingredients[].ref_image` | optional crop | `refs/ham.jpg` |
| `recipes[].name` + `ingredients` | the ingredients each recipe requires | `Prosciutto: tomato sauce, mozzarella, cooked ham` |

### 1.5 Orders
- Each order has an id, a list of recipes and optional modifiers (`no X`, `+X`).
- Orders are typed on the tablet or loaded from a JSON list. POS integration comes later.

### 1.6 Ingredient check service
- The Decisions API key lives in the site pack, not in the APK, and has a spending cap.
- The site needs internet, with a 4G hotspot as backup.

### 1.7 Build the model (laptop, one command)

```
adb pull <site pack> → python make_site_model.py <site pack> → adb push model.tflite
```

- The script computes YOLOE visual-prompt embeddings from the reference rectangles, adds the `person` text class, and exports TFLite at 640.
- Takes about 5 minutes. The classes are then fixed for that site.

---

## 2. Shared runtime: detector and tracker

### Detector
- One YOLOE-11s model per site, running on the tablet GPU, with all classes in one pass.
- Frame rate:
  - 5 fps for assembly-only cameras;
  - **10 fps for cameras where containers get carried**.
- Budget: about 32 ms per frame on the phone, so roughly 30 frames/s in total. Redmi numbers are pending from Exp 2.

### Tracker
- **Today:** `track.py` is ByteTrack-style without the Kalman filter.
  - Greedy IoU matching: high-confidence detections first, then low-confidence detections only to keep tracks alive.
  - A track is dropped after 2 s unseen.
  - This is fine for things standing still, but it **breaks for carried boxes**: at 5 fps a moving box barely overlaps its last position.
- **Port:** full **ByteTrack**, i.e. the same plus a **Kalman filter** that predicts motion. One tracker per camera per class.
  - Added on top of ByteTrack: a third matching pass by **centre distance** (up to 1 box size). Without it, a box carried faster than about half its width per frame never matches, so the Kalman filter never learns its speed (caught by a unit test).
- **Rejected alternatives:**
  - DeepSORT and BoT-SORT add appearance re-identification. That doesn't help with identical boxes and costs a second model. BoT-SORT's camera-motion correction is also useless with fixed cameras.
  - OC-SORT has slightly better occlusion recovery. Not worth switching now.
- **What a tracker can't do:** it only knows "same box as a moment ago, in this camera". It **can't tell identical boxes apart after occlusions or swaps**. The custody layer (§4.2), the shared map (§4.3) and identifiers (§4.4) handle that.

---

## 3. Step 1: Ingredient tracking (`assembly` zone)

**Inputs**
- The assembly zone.
- The `base` class.
- The menu and the open orders.
- The done signal: `leaves_zone` (default), `packed` or `tap`.
- `mode`: `in_container` if the base is itself the container (a bowl), otherwise `packed_after`.

**Algorithm**
1. **Find bases.**
   - Detect bases inside the assembly zone and track them.
   - Hands keep covering bases, so a base is also kept **by position**: one that reappears where a base was (IoU ≥ 0.3) within 6 s is the same base.
   - A base seen for ≥ 2 s raises `dish_started`.
2. **Decide when to check.** No hand model is used; this replaces the Subway demo's MediaPipe gating. A crop is sent only when all of these hold:
   - detector confidence ≥ 0.8 (a stand-in for "not covered");
   - the base hasn't moved for 1 s (its box shifted < 5% of its size);
   - at least 3 s have passed since the last check.
   - (Dropped 2026-10-29: "no `person` box covers its centre". A standing cook's box covers the whole counter, so it blocked every check on the pizza clip.)
3. **Crop.**
   - Take a square around the base with 8% padding, resized to 384 px.
   - Grey out other bases in the crop.
4. **Ask the Decisions API.**
   - Ask one yes/no question per **menu** ingredient, not just the order's, so extras get caught.
   - Question template: "Is there {name} ({look}) on the {base} in the centre? Hands, gloves and utensils are not ingredients. Not {confusable_with}."
5. **Vote.**
   - An ingredient is confirmed after **3 "yes" answers in a row** (p ≥ 0.6).
   - Once confirmed it stays confirmed, even when later layers hide it.
   - The time recorded is crop time + API latency.
6. **Show live.** The checklist ticks ingredients off as they are confirmed.
7. **On the done signal, match the dish to an order.**
   - Done bases are matched to open orders by the best overall ingredient fit, trying every pairing. Ties go to the oldest order.
   - `missing` = expected − confirmed; `extra` = confirmed − expected. Modifiers adjust "expected".
8. **Hand over to Step 2:** `{base_id, order_id, contents, verdict, done_t, zone, last_box}`.
   - In `in_container` mode this record already is the unit, and Step 2 is skipped.

**Outputs**
- Events:
  - `dish_started`;
  - `ingredient_confirmed {ingredient, p, t, known_t}`;
  - `dish_verdict {order, missing, extra}`;
  - `dish_done`.
- Files: `decision_calls.jsonl` (latency, tokens, cost) and the saved crops.
- On the pizza clip: p50 0.73 s per check, $0.005 per order.

**Limits**
- An ingredient fully covered before 3 checks see it is missed.
- Lookalikes need good `look` and `confusable_with` text.
- A portion check ("noticeably more than the reference?") is experimental and the first thing to cut.
- With no internet there are no ingredient checks; detection and tracking keep running.

---

## 4. Steps 2 + 3: binding and tracking the unit

### 4.1 Packing: binding the order to a container (`packing` zone)
1. A finished base reaches the packing zone, either seen moving in the same camera or matched by the transit window.
2. It overlaps an `open_container` track for ≥ 1 s: `dish_in_container`.
3. A `closed_container` is detected at the same spot (IoU ≥ 0.3 with the open box) within 10 s: **`unit_packed`**. The order and contents are now bound to that closed-container track.
4. If several bases are waiting, they are assigned first in, first out by arrival. The result is flagged **uncertain** if two arrived within the clock skew of each other.

**Fallback.** If closed containers aren't detected (they weren't on the pizza clip), the spot where the box closed counts as "occupied" while it differs from the background frame. This is weaker, and **the CTO must test with the client's real box**. It is the riskiest assumption in the plan.

### 4.2 Custody layer: identity when the tracker can't keep it

Each unit has exactly one state at a time:

| From | To | When |
|---|---|---|
| On surface at P (track visible) | on surface | the track continues |
| On surface, track lost | **still at P** ("parked") | a person covered P when the track was lost **and** a container reappears at P within 20 s |
| On surface, track lost | **carried by person X** | a person track overlapped the box when it vanished, and that person then moved away from P |
| Carried by X | **on surface at Q** | a container appears at Q, where X is or just was |
| Carried by X | **in transit** | X leaves the camera view or enters a zone that links to another camera |
| Any | **uncertain among {candidates}** | no rule fits; candidates are the units that could physically be there |

### 4.3 Across cameras and the shared map
- **No overlap.**
  - A unit in transit A→B waits in a queue.
  - When a new container track starts in B, the candidates are the units whose travel window fits and whose container type matches.
  - One candidate: it is assigned. Several: the oldest is assigned and **flagged uncertain among {X, Y}**.
  - If nothing arrives before the maximum travel time, the app raises `unit_lost (last seen A, t)`.
- **Shared map (in scope).**
  - At setup, 4 tapped points per camera on the counter or floor are matched to the floor plan. This gives each camera a homography (a flat-plane mapping) onto the plan.
  - Every box (bottom-centre, on the counter plane) and every person (feet, on the floor plane) gets **map coordinates**.
  - The custody rules then run on the map instead of per camera.
  - Overlapping cameras hand units over by map position. When one camera's view is blocked, another may still see the box.
  - This is the 2D, flat-surface version of the CTO's 3D idea.
  - Effort: 1–1.5 days.
- **Overlap without the map.** A container that appears in camera B while the unit is still visible at camera A's edge is taken to be the same unit.

### 4.4 Identity limits: what needs an identifier

| Scenario | Without an identifier |
|---|---|
| Boxes stand still, people walk in front | ✅ parked |
| Identical boxes moved one at a time, in view | ✅ tracker + carrier |
| Two people each move an identical box at the same time, in view | ✅ mostly; ⚠️ fails if they cross closely |
| Identical boxes cross a camera boundary where cameras overlap | ✅ shared map |
| Several identical boxes in transit between non-overlapping cameras | ⚠️ uncertain unless their order is kept or their types differ |
| One person carries a stack and reorders it | ❌ **needs an identifier** |
| Boxes swapped where no camera sees | ❌ **needs an identifier** |

**Stated plainly: no tracker, map or AI model can recover a swap of identical boxes that no camera saw.** The information isn't in any frame. Those cases need a **visible identifier on the box**: a QR code or order label printed with the order id, or a reusable numbered tag. Without one, the app shows "uncertain: A-2041 or A-2042" and never guesses.

**Identifier reading (optional, about 0.5 day)**
- ML Kit barcode or text recognition runs on crops of `closed_container` detections, at least on the checkout camera.
- A read code overrides every other rule.
- If the label is printed with the order id, checkout identity no longer depends on tracking at all. Tracking then only answers *where* the order is.
- Size: a QR code needs about 60 px across in the stream. Mount the checkout camera close to the boxes, use its 1080p main stream, or use larger codes.

### 4.5 Track referee (Decisions API), built but OFF by default
Decided 2026-10-29: build it, keep it off by default, and turn it on only if it beats "uncertain" on the staged swap clip (§7).

The referee corrects **identity, not positions**. The Decisions API only answers yes/no with a probability, so it can't return box coordinates.

1. **Trigger.** It runs only when custody can't decide:
   - a track was lost and neither the "parked" nor the "carried" rule fits;
   - two containers are close together while a person overlaps them;
   - a track jumps more than 2 box sizes in one frame;
   - a new track starts where another one just vanished.
2. **Images sent:**
   - the frame *before*, with known units labelled **A, B** on coloured boxes;
   - 1–2 frames *during*;
   - the frame *after*, with the unknown boxes numbered **1, 2**.
3. **Questions:** "A is now box 1", "A is now box 2", and so on for each pair.
4. **Answer.** The app takes the assignment with the highest total probability. It accepts it only if it beats the next-best assignment by at least 0.3; otherwise the case stays uncertain.
5. **Cost and speed:** about 1 s and $0.001 per call, a few calls per order. It is never run on every frame: that would be about $2–4 per camera-hour.
6. **What it can't do:** fix a swap that no camera saw (§4.4).

Every referee call is logged (images, answers, chosen assignment) in `referee_calls.jsonl` so it can be checked by hand.

**Outputs of Steps 2 + 3**
- Events: `unit_packed`, `unit_entered {zone}`, `unit_carried {person}`, `unit_transit`, `unit_lost`, `unit_uncertain {candidates}`, `unit_identified {code}`.
- A location timeline for each unit.

---

## 5. Step 4: Checkout (`checkout` + optional `customer` zone)
1. A unit enters the checkout zone. The app shows the **order id, contents, assembly verdict and certainty**, e.g. "A-2041: Margherita ✓, Prosciutto ✓", or "A-2041 *or* A-2042".
2. An order is **complete** when all of its units are there.
3. **Handed over:** the unit leaves the checkout zone while a `person` is in the customer zone. With no customer zone, a person near the zone's edge counts.

**Outputs:** `unit_at_checkout`, `order_complete`, `handed_over`, `order_summary`.

---

## 6. Testing, size, cuts

### Testing
- **Replay:** the pizza clip played from a file on the tablet. The Kotlin events must match the Python `events.jsonl`.
- **Multi-camera:** crops of the pizza clip served as 2–3 RTSP cameras by mediamtx.
- **Live:** the CTO's cameras with real boxes, as early as possible, including a staged swap of two identical boxes.

### Size

| Part | Days |
|---|---|
| Setup screens (cameras, zones, flow, references) | 1 |
| Detector + ByteTrack with Kalman + custody | 1.5 |
| Ingredient tracking | 1 |
| Packing, transit, checkout + UI | 1 |
| `make_site_model.py` + replay test | 0.5 |
| Shared map | 1–1.5 |
| Identifier reading (optional) | 0.5 |
| **Total** | **≈ 6–7** |

## 7. Recordings needed from the CTO
These recordings are made **with the cameras and the tablet planned for the demo**: the same positions, the 720p substream, and real client (or similar) boxes. Each camera is recorded at the same time (an NVR export, or `ffmpeg -i rtsp://… -c copy camN.mp4` per camera, all started together). Clips can be short, but each must have **every camera running for the whole clip**.

| # | What happens | Tests |
|---|---|---|
| R1 | One normal order: assembly → packing → (hold) → checkout → hand-over | the whole flow, ingredient checks, closed-box detection |
| R2 | Two orders at once, **identical boxes**, kept in order | transit queue, packing FIFO |
| R3 | Two identical boxes **swapped in view**: one person moves both one at a time; then two people each carry one at the same time, crossing | carrier rules, shared map, referee |
| R4 | Two identical boxes **swapped out of view** (e.g. behind a person, or carried as a stack and reordered) | that the app says "uncertain" and doesn't guess |
| R5 | R3 + R4 again **with a QR label** on each box (order-printed, about 8–10 cm wide, or larger if the camera is far) | identifier reading, and that it overrides everything else |
| R6 | 1–2 minutes of the empty scene | background frames |

With each recording, the CTO also sends:
- **the ground truth**: which box/order is which, and the rough time of each swap;
- **the 4-point taps per camera** for the shared map, or a photo of the floor plan with the same 4 points marked.

### Cut order, if needed
1. Portion check.
2. Identifier reading.
3. Zone drawing on the tablet: put polygons in `site.json` instead, using a laptop helper.
4. Carrier rules: keep parking plus the transit queue.
5. Shared map.

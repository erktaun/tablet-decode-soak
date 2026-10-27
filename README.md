# Tablet decode soak test and detector benchmark (Experiments 0, 1 and 2)

Can one budget Android tablet decode several store cameras at once, all day, without overheating or
dropping frames? How much CPU does that leave, and how fast does a detector run in it?

- **Experiment 0:** asks the tablet for its hardware decoder limits, and checks how many decoders really start at once. Takes 1 minute.
- **Experiment 1:** runs 4 camera streams through the tablet's hardware decoder for 30 minutes to 24 hours. Five times a second, each stream's frame is shrunk on the GPU to the detector's input size and read into memory as a model input. That's the work a detector's input step would do. The size is 320 px by default, or 640 px for small objects. Every 5 s the test logs frame rate, lost frames, delay, thermal status, temperatures, CPU and memory.
- **Experiment 2:** times three detector models on the tablet's CPU, GPU and NPU, also while 4 streams decode. See [Experiment 2](#experiment-2-detector-speed).

The laptop plays the cameras: it serves 4 looping RTSP video streams over Wi-Fi. The tablet runs a small test app (`prebuilt/soak.apk`), and one script on the laptop drives everything over a USB cable.

## What you need

- The tablet and its USB cable, plus its wall charger for the 24-hour run.
- A laptop (macOS, Linux or Windows) with Python 3.8 or newer, on the same Wi-Fi as the tablet.
- About 2 hours spread over a day, plus a 24-hour run that needs no attention.

## Setup (once, about 15 minutes)

**1. Tablet: turn on USB debugging.**
1. Settings → About device → tap *OS version* 7 times.
2. Then Settings → Additional settings → Developer options:
   - turn on **USB debugging**;
   - on Xiaomi/Redmi, also turn on **Install via USB**. This needs a Mi account sign-in, and Xiaomi then asks you to confirm each install on the tablet.
3. Connect the tablet to the same Wi-Fi as the laptop.
4. Plug it into the laptop, tick *Always allow from this computer* and tap *Allow*.

**2. Laptop: install the tools and get the code.**

| OS | Command |
|---|---|
| macOS | `brew install ffmpeg android-platform-tools` |
| Ubuntu | `sudo apt install ffmpeg adb` |
| Windows | `winget install Gyan.FFmpeg Google.PlatformTools`, then open a new PowerShell window |

```bash
git clone <this repo> && cd <repo folder>
pip install matplotlib        # for the plots; everything else is the Python standard library
adb devices                   # must list the tablet as "device" (not "unauthorized")
python3 run_test.py --self-test 30
```

The self-test checks the laptop side on its own, without the tablet. It should end with `self-test PASSED`. On Windows, type `python` instead of `python3` in every command.

**3. Tablet: stop the system from pausing the app.** Do this right after run A below, which installs the app:
- Settings → Apps → Manage apps → *Decode Soak* → Battery saver → **No restrictions**.
- Turn on *Autostart* if the tablet offers it.

This matters on Xiaomi. In our trial on a Xiaomi phone running on battery, the system froze the app 4 seconds after the screen was locked, even though it runs as a foreground service. It woke only when the app was opened again. `summary.md` flags any such pause. Also keep the tablet on its charger during runs.

If macOS or Windows asks whether to allow incoming connections for `mediamtx` or `python`, click **Allow**. The tablet connects to the laptop on TCP ports 8554 and 8899, plus UDP 8000–8001 with `--transport udp`.

## The runs, in order

Run every command from the repo folder, with the tablet plugged in and unlocked.

| Run | Command | Time | What it answers |
|---|---|---|---|
| A | `python3 run_test.py --caps-only` | 1 min | Hardware decoder limits (Experiment 0). **If fewer than 4 decoders start at 1080p, stop here:** the tablet can't take 4 cameras at that resolution |
| B | `python3 run_test.py --mode local --minutes 30` | 35 min | **The main result:** can the tablet itself decode 4 streams? The clips are stored on the tablet, so no network is involved |
| C | `python3 run_test.py --minutes 30` | 35 min | The same over Wi-Fi: does the network cope? |
| E | `python3 run_test.py --minutes 30 --infer-size 640` | 35 min | The same as C with 640 px model input (small objects). Optional |
| D | `python3 run_test.py --mode local --minutes 1440 --tag 24h` | 24 h | A full day: stability, heat, and the tablet sitting at full charge while warm |

**What each run does by itself:**
1. Builds the test clips (about 2 minutes, first time only).
2. Installs the app (on Xiaomi, tap *Install* on the tablet).
3. Runs Experiment 0.
4. Starts the streams and prints a status line every 30 s.
5. At the end, copies everything to `results/<run_id>/` and writes `summary.md` and `report.png`.

**If B passes but C fails,** the problem is the Wi-Fi, not the tablet. `summary.md` says so when the RTSP server had to throw data away because the tablet's connection stalled. In our trial on an office Wi-Fi, stalls of 1–3 s every few minutes lost about 1% of frames, while decoding itself lost none. Options in a store: 5 GHz Wi-Fi close to the router, or a USB-C Ethernet adapter for the tablet.

**Why run D is 24 hours:** in a store the tablet runs around the clock. A full day catches slow memory leaks, overnight system jobs and the effect of a full battery kept warm. Heat itself levels off within the first hour. Because D uses clips stored on the tablet, neither the laptop nor the Wi-Fi matters after the start.
1. Once the first status line appears, unplug the USB cable and put the tablet on its wall charger. The laptop may sleep, or you can close the script.
2. After 24 hours, plug the tablet back into the laptop.
3. If the script is still open, wake the laptop and the script collects the results itself. If you closed it, run `python3 run_test.py --collect`.

**When the real cameras are known,** repeat B with matching settings:
- `--res 720|1080|1440` and `--codec h264|h265` (also `--fps`) to match the cameras;
- `--res 360` for a camera sub-stream. Many IP cameras offer a low-resolution second stream, and decoding it is far cheaper;
- `--streams N` up to the number of clips in `clips/src/`. Add more clips there for more streams.

**Other options:**
- `--infer-fps`: how often each stream's frame is pulled.
- `--transport udp`: RTSP over UDP instead of TCP.
- `--mode usb`: streams over the USB cable instead of Wi-Fi.
- `--serial`: picks a device when several are connected.
- `--sink yuv`: hands full frames to the CPU. It crashes on some Qualcomm devices.
- `--sink private`: decodes only.

## Experiment 2: detector speed

How fast does a detector run on the tablet, on the CPU, the GPU or an NPU? This uses TensorFlow Lite's own benchmark tool (`prebuilt/benchmark_model`) on three ready-made models:

| Model | What it is |
|---|---|
| `yolo11n` | The smallest standard YOLO detector (80 everyday classes). It is the likely base for a fine-tuned store model |
| `yolo26n` | The newest small YOLO, built for edge devices: no separate box-merging step |
| `yoloe-11s` | The open-vocabulary model from the ingredient demo, prompts baked in (person, sandwich, gloved hand, box). It is larger: the upper bound |

Each model is tested at **320 and 640 px**, as **FP32 and INT8**. Each is tried on **CPU with 1, 2 and 4 threads, GPU and NNAPI**; NNAPI is Android's route to an NPU, if the tablet's vendor ships a driver. On first use the script downloads the models (about 150 MB) from this repo's release page and checks their checksums.

| Run | Command | Time | What it answers |
|---|---|---|---|
| F | `python3 bench_model.py --quick` | 15 min | Speed of each model and size on the 4-thread CPU, GPU and NNAPI |
| G | `python3 bench_model.py --quick --under-load --sustained 300` | 35 min | The same while the tablet decodes 4 streams (as in run B), plus 5 minutes non-stop to see heat build up. **The number that decides** |
| H | `python3 bench_model.py` | 25 min | The full matrix, including 1 and 2 CPU threads. Optional |

- Run them after run B, with the tablet plugged in, unlocked and cool: the screen can dim, but wait 10 minutes after a long run.
- `--under-load` starts run B's local decoding in the background (the results folder ends in `under-load-bench`). Then it repeats the fastest configs, so decoding and detection compete as they would in a store.
- Stop early with **Ctrl-C**. A config runs at most about 30 s, so nothing stays running on the tablet for long.
- `--models`, `--sizes 320 640`, `--precisions fp32 int8`, `--delegates cpu1 cpu2 cpu4 gpu nnapi` and `--secs` pick a subset.

**Reading `summary.md`:**
- The first table gives the fastest processor per model and size. It also gives the **headroom**: fps divided by the roughly 12 detections/s the draft workload needs for the whole store (assembly 5, order box 5, queue 1, tables 1).
  - A headroom above 1 **under load** means one model can serve every camera at those rates.
  - Below 1, the store needs a smaller model, a lower input size, fewer detections, or other hardware.
- "Ops on accelerator" shows how much of the model really ran on the GPU or NPU. The rest falls back to the CPU.
- NNAPI with no NPU driver quietly runs on the CPU, and the report marks those rows. In our trial on a Snapdragon phone, NNAPI listed only `nnapi-reference`, the CPU fallback, because Qualcomm no longer ships an NNAPI driver. MediaTek chips like the Redmi's may still have one.
- Not included: resizing the frame (the soak test measured that: 1-5 ms) and decoding boxes, about 1-2 ms.
- `models/manifest.json` shows how many of PyTorch's detections each file reproduces. FP32 reproduces all of them. INT8 reproduces 75-90%: quantization shifts boxes and confidences, so boxes near the threshold appear or vanish. Whether that matters is a question for Experiment 4 on store footage.

To rebuild the models (other prompts, sizes or a fine-tuned model), see `export_models.py`. You don't need it for the runs.

## Stopping and collecting

| To | Do |
|---|---|
| Stop a run early and keep its results | Press **Ctrl-C** in the terminal. The script stops the app, then copies and reports what was recorded |
| Stop a run when the script is gone | `python3 run_test.py --stop`, or tap **Stop** in the app |
| Get results the script didn't copy | `python3 run_test.py --collect` copies every run on the tablet into `results/` and reports it |
| Remove the app afterwards | `adb uninstall com.ald.soak` (this also deletes the clips and logs stored on the tablet) |

A run always ends by itself after `--minutes`, even if the laptop is off.

## What to send back

- The whole `results/` folder, zipped. Each run is one sub-folder.
- The camera details: model, resolution, fps, codec (H.264 or H.265), bitrate, whether a sub-stream exists, and how many cameras per store.
- The store network: Wi-Fi or Ethernet, and how far the tablet would be from the router.
- The exact tablet model and RAM. Run A records them too.

## Reading the results

Each run folder contains:

| File | Contents |
|---|---|
| `summary.md` | Pass/fail against the draft criteria, plus key numbers |
| `report.png` | Plots over time: fps per stream, lost frames, thermal status and headroom, temperatures, CPU and core clocks, delay |
| `metrics.jsonl` | Everything the app logged every 5 s |
| `sampler.log` | System CPU, core clocks and thermal-zone temperatures, read over adb |
| `caps.json` | Decoder limits (Experiment 0) |
| `events.log`, `logcat.txt` | Decoder picked per stream, errors, restarts, catch-ups |
| `mediamtx.log`, `sender_events.json` | Laptop side: RTSP server warnings, sender restarts |
| `config.json`, `host.json` | Run settings, laptop and clip details |

Draft pass criteria (from the feasibility plan; edit `CRITERIA` in `plot_run.py`):

| Criterion | Pass if |
|---|---|
| Lost frames | Under 1% after the first minute. Losses during start-up are reported separately |
| Thermal status | Never above *moderate* (2) |
| Delivered fps | Declines by less than 10% from the start to the end of the run |
| System CPU | Averages under 70% |
| App never paused | The system never froze the app |
| Decoders | Hardware only, no errors or restarts |

Delay (camera to frame) is reported, not judged: the feasibility plan's limits depend on the event type.

## What this test does not show

- **Detection itself.** The model doesn't run here; that's Experiment 2. The CPU left over here is its budget.
- **Real cameras.** The clips are four 30-second segments of a kitchen video, upscaled and re-encoded like an IP camera: fixed fps, no B-frames, a keyframe every 2 s, capped bitrate. Decode cost depends mostly on resolution, fps and bitrate, not on content. To use real recordings, put them in `clips/src/` and delete `clips/build/`.
- **Exact delay.** It relies on matching the laptop and tablet clocks over HTTP, which is accurate to about ±0.1 s. That's fine for alerts measured in seconds.
- **Where decoding CPU shows up.** "App CPU" covers only the test app. Hardware decoding runs in Android's media service, so use the system CPU from `sampler.log`.
- **Catch-ups are a feature, not a fault.** After a Wi-Fi stall, the backlog arrives in a burst. The app then plays at 1.5× speed until it has caught up, as a live client should; `catch_ups` in the summary counts these. Without catch-up, the delay builds up permanently and the server starts throwing data away.
- **The Android emulator.** It only shows that the app starts. Its numbers are not results.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `No device found` / `unauthorized` | Replug the cable; accept *Allow USB debugging?* on the tablet; check `adb devices` |
| Install fails on Xiaomi | Turn on *Install via USB* in Developer options and tap *Install* on the tablet when asked |
| `ffmpeg not found` / `adb not found` | Install them (setup step 2) and open a new terminal |
| `nothing listening on port 8554` | Another program uses the port, or a previous run is still open. Close it and retry |
| Wi-Fi run: tablet can't connect | The tablet must be on the same network as the laptop, not a guest network. Allow the firewall prompt. Or use `--mode usb` |
| Streams at 0 fps, app restarts | Send `logcat.txt`. Try `--sink private` to see whether decoding alone works |
| `App never paused` fails | See setup step 3. Keep the tablet on its charger |

## How it works

```
laptop                                         tablet (app: com.ald.soak)
clips/build/*.mp4 --ffmpeg loop--> mediamtx ==RTSP==> ExoPlayer --> MediaCodec (hardware)
                                                            |
run_test.py /info (clock, sender start) <--HTTP--     GPU texture (full frame stays on the GPU)
                                                            |-- every frame: read the barcode strip
                                                            |-- 5x/s: GPU downscale -> 320/640 px
                                                            |         readback -> RGB float tensor
                                                      Metrics -> metrics.jsonl every 5 s
```

Every test frame carries a small barcode in its top strip with the frame number. The app reads it, so it can count frames that never arrived and work out how old each frame is.

| Path | What |
|---|---|
| `run_test.py` | Laptop runner: sender (mediamtx + ffmpeg), adb install/start/stop/collect, status, `--self-test` |
| `make_clips.py` | Encodes the camera-like clips with the frame barcode |
| `plot_run.py` | `summary.md` + `report.png` for a run folder: `python3 plot_run.py results/<run_id>` |
| `sampler.sh` | Runs on the tablet over adb: system CPU, clocks, thermal zones |
| `bench_model.py` | Experiment 2: pushes `prebuilt/benchmark_model` (TensorFlow Lite's benchmark tool, Android arm64) and the models, runs every config, writes `summary.md` |
| `export_models.py`, `models/manifest.json` | How the models were made (Ultralytics 8.4.175, LiteRT export), their checksums and accuracy versus PyTorch |
| `android/` | The app (Kotlin, media3 ExoPlayer 1.4.1), prebuilt as `prebuilt/soak.apk` |

To rebuild the app (needs JDK 17 or newer and the Android SDK):
`cd android && ./gradlew assembleDebug && cp app/build/outputs/apk/debug/app-debug.apk ../prebuilt/soak.apk`

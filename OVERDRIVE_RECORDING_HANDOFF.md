# Overdrive — "stops recording while driving" — investigation handoff

Repo: https://github.com/yash-srivastava/Overdrive-release
Car: BYD Seal (Global), DiLink v3. Recordings/trips/surveillance all write to a **USB** drive
(`/storage/C89C775F9C7746CA/...`); there is **no SD card** (`sd=false, usb=true`).
Storage target has ~372 GB free, so capacity is not the issue.

Key user fact: **recording works fine on the previous 19.8 build with the same drive and car.**
So the regression is almost certainly in software (newer build), not the storage medium.

Two logs were analyzed (BYD head unit, daemon = `CameraDaemon`):
- `camera_daemon_20260602_151424.log` — short stationary tests.
- `camera_daemon_20260602_175433.log` — a real ~26 min drive (trip id 1516, 5.74 km).

---

## Finding A (log 1): DRIVE_MODE asks the recorder to start before the recorder exists

In `RecordingModeManager.activateMode()` (file
`app/src/main/java/com/overdrive/app/recording/RecordingModeManager.java`, DRIVE_MODE case
~lines 748–773) the sequence is:
1. if `!pipeline.isRunning()` → `pipeline.start(false)` (autoRecord = false)
2. if `!pipeline.isRecording()` → `pipeline.startRecording()`

`GpuSurveillancePipeline.startRecording(outputDir, prefix)`
(`app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java`, ~line 1424)
wraps its whole body in `if (recorder != null) { ... }` **with no else branch**. The `recorder`
(`GpuMosaicRecorder`) is created inside the pipeline's `init()` / on the GL thread
(`recorder = new GpuMosaicRecorder(...)` ~line 950; "Recorder initialization deferred to caller"
/ "scheduled on GL thread"), i.e. asynchronously.

In log 1, both DRIVE_MODE activations entered `startRecording()` (the storage-readiness check
inside it ran — "Recordings/Surveillance/Trips using USB" lines appear) but emitted **neither**
of its two outcome logs:
- success: `Normal recording started`
- deferred: `Encoder not ready yet — recording will start when camera is ready`

Per the source the only path that logs neither is `recorder == null`. So `startRecording()`
silently no-ops and no `cam_*.mp4` is written. On the warm attempt the pipeline was already
running from the ACC-on streaming-on-demand state, so step 1 was skipped and the recorder was
never created.

This is a real bug but it was NOT reproduced as the everyday failure in log 2 (the recorder
worked there). Lower priority than Finding B, but worth fixing: `startRecording()` should handle
`recorder == null` (log + defer) instead of silently returning.

---

## Finding B (log 2): write-queue stall → GL watchdog forces a process restart → truncated clip

This is the one that matches the user's symptom ("records, then stops after a few seconds";
clips `cam_20260602_145531.mp4`, `cam_20260602_151931.mp4` are short).

Observed chain during the 17:20 recording:
1. `Normal recording started ... cam_20260602_172036.mp4` (clean).
2. ~14 s in: `Video drop count N — muxer write queue saturated, SD card likely stalled`,
   climbing to **571 dropped frames** over ~2 min. NOTE: bitrate is **ECONOMY 1 Mbps (~125 KB/s)**
   — a healthy drive should not stall at this rate. Also note the "SD card" wording is wrong;
   target is USB (`sd=false`). Generic/hardcoded message.
3. `CRITICAL: GL thread blocked for 3658ms - forcing process restart`
4. `Daemon exited cleanly (code 0), restarting in 10s...`
5. In-progress segment truncated → `Quarantined broken recording (stopOk=false ... 52 KB):
   cam_20260602_172236.mp4.broken`.
6. After restart, recording resumed and ran ~26 min with NO further drops (intermittent).

Relevant source:
- GL watchdog: `app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java`
  - `GL_THREAD_TIMEOUT_MS = 3000` (~line 209), `startWatchdog()` (~line 2418).
  - On timeout it calls **`System.exit(0)`** deliberately ("EGL contexts cannot be recovered
    from a blocked thread"); exit 0 triggers the DaemonLauncher restart loop.
- Write decoupling + drop policy:
  `app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java`
  - Comment ~lines 245–250: the muxer write queue + dedicated disk-writer thread exist
    specifically to absorb "SD card I/O stalls (50–100 ms during GC)" by dropping frames
    instead of blocking the encoder.
  - `offerMuxerPacket` drop logic; queue capacity sized for a "worst-case SD-backpressure burst".

Interpretation: the design is meant to TOLERATE slow storage (drop frames, keep recording).
The fact that a stall instead escalated to a process-killing `System.exit(0)` — on a drive that
19.8 handled fine — points to a regression: either (a) the newer pipeline starves/under-drains
the disk-writer thread or increased per-frame work (Stage timings during the storm show elevated
`ovl`/`swap` and FPS sagging to ~19), or (b) the GL watchdog now hard-restarts on backpressure
that 19.8 rode out as a stutter.

---

## Finding B — VERIFIED root cause (2026-06-03 follow-up, current `main` @ 05c702f)

The drop-storm alone was survivable (the design dropped frames for ~2 min and kept recording).
What killed the process was the **2-minute segment rotation landing in the middle of the storm**.
Confirmed from the log timing + source:

Log evidence (the trip started recording `cam_20260602_172036.mp4` at 17:20:36.936):
- `17:22:36.917  Segment duration reached (120s), rotating to new file...`
- `17:22:36.919  Rotating segment 0 - hot-swap to new file`
- `17:22:37.071  Audio track added ...` (new muxer pre-constructed OFF the lock — fast, as designed)
- GL block begins ~`17:22:38.49` (3658 ms before the watchdog line)
- `17:22:42.145  CRITICAL: GL thread blocked for 3658ms - forcing process restart` → `System.exit(0)`
- `17:22:43.859  Rotation drained 579 queued frames into old segment`  ← the drain took ~7 s
- in-progress segment quarantined: `cam_20260602_172236.mp4.broken` (52 KB)

Mechanism (all line numbers in `HardwareEventRecorderGpu.java`):
1. `rotateSegment()` runs **on the drainer thread** — it is called from `drainEncoderInternal()`
   (~line 3265), which is the drainer loop body (`startDrainerThread`, ~line 2849), NOT a
   background thread.
2. `rotateSegmentLocked()` (~line 3464) was redesigned (RC6 "hot-swap") to move the slow
   `muxer.stop()` off the critical path to a background finalizer — and its own comment claims
   *"Lock window: ~5-30ms (drain only)"*. But the lock window still contains a **synchronous,
   unbounded drain of the entire `muxerWriteQueue` into the OLD muxer** (~lines 3568–3599):
   `while ((pkt = muxerWriteQueue.poll()) != null) { ... writeRebased(muxer, ...) }` →
   `mux.writeSampleData(...)`. Under the USB stall those 579 `writeSampleData` calls to the
   *already-stalled* old muxer took ~7 s.
3. While the drainer thread is stuck in that drain loop, it is **not dequeuing encoder output**.
   The encoder's output buffers fill → the encoder stops consuming input → the encoder input
   `Surface` fills → the GL thread blocks in `eglSwapBuffers` inside
   `GpuMosaicRecorder.drawFrame()` (`PanoramicCameraGpu.java` ~line 2040). The heartbeat
   (`PanoramicCameraGpu.java:1843`) stops updating.
4. 3658 ms later the GL watchdog (`GL_THREAD_TIMEOUT_MS = 3000`, ~line 209) fires `System.exit(0)`
   (~line 2457) → DaemonLauncher restart → truncated/quarantined clip → ~10 s of no recording.

So the "hot-swap" optimization moved `muxer.stop()` off the critical path but **left the
queue-drain (the actually-slow part under backpressure) on the drainer thread, inside `muxerLock`,
doing the slowest possible op — `writeSampleData` to the stalled drive.** The 3 s GL watchdog
is the executioner, but the rotation drain is the cause. The steady drop-storm (lines 5866–6027)
was the *precondition* (a full 600-entry queue waiting to be drained at rotation time), not the
killer by itself.

Supporting Stage timings (encoder thread) show the `swap` backpressure building before the kill:
pre-storm `total≈33ms / swap≈25ms / 26 FPS` → `17:21:57 total=138ms swap=75ms 18.9 FPS` →
`17:22:28 total=167ms mosaic+swap=159ms swap=99ms` (last sample before the rotation block).

### Targeted fix (supersedes the generic candidates below for Finding B)
The rotation must never do unbounded blocking disk I/O on the drainer thread under `muxerLock`.
Options, smallest-blast-radius first:
- **Bound the rotation drain.** In `rotateSegmentLocked` drain at most a small budget (e.g. a few
  frames or a ~20 ms deadline) into the old muxer; hand the **old muxer + any still-queued
  old-segment packets to the existing background finalizer** (the same thread that already does
  `stop()`+`release()`+rename), so the drainer thread returns to servicing the encoder
  immediately. This keeps the lock window at the advertised ~5–30 ms even under stall.
- Alternatively, **drop the backlog at the rotation boundary** instead of writing it — a sub-second
  gap at a 2-min segment seam is vastly better than a process kill + 10 s outage + `.broken` clip.
- Defence in depth: have the GL watchdog, on a backpressure-attributed timeout, **finalize/close
  the current segment cleanly before exit** (no 52 KB `.broken` stub), and/or treat sustained
  write backpressure as a non-fatal degraded state rather than a `System.exit(0)`.

Note: public tree is `versionName "1.0"` / `versionCode 1`, which does not map to the user's
"19.8"; pinning the exact regressing commit still needs the user's real build. But the mechanism
above is fully reproduced from this build's source + the log, independent of that mapping.

---

## Finding C (log 3, `camera_daemon_20260603_065630.log`): restart→SIGABRT crash loop = 77-min total recording outage

A **new, more severe, and distinct** failure from A/B. Storage was healthy this whole session
(segment rotations drained cleanly, e.g. `Rotation drained 4 queued frames into old segment`; no
drop-storm, no GL block), so this is NOT Finding B recurring.

Timeline:
- Daemon ran healthy for **14077 s (~3.9 h)**. At `05:35:21` gear D→P deactivated DRIVE_MODE; at
  `05:35:24` ACC went OFF and the daemon **tore down and re-initialised the pipeline for sentry
  mode** (`05:35:25–27`: encoder init, camera open, watchdog start — a fresh pipeline only
  ~3–5 s old).
- `05:35:30  Daemon exited with code 130` (= 128+SIGINT). **No `Shutdown hook: cleaning up...`
  lines precede it** — unlike the clean `System.exit(0)` path in log 2 — so the JVM shutdown hook
  did not complete its GPU/EGL/camera-HAL release before the process died.
- From attempt 2 onward: **`Daemon exited with code 134` (= 128+SIGABRT) every retry, for ~77
  minutes** (`05:35:34 → 06:52:15`, 84 consecutive aborts, backoff capped at 60 s). Crucially,
  **attempts 2–84 produced ZERO daemon log lines** — not even `=== CameraDaemon Log Started ===`
  — aborting in <2 s. The abort is therefore in native / early-VM bring-up *before the logger
  opens its file* (native crash → only the shell's `Aborted` reaches the file; the tombstone goes
  to logcat, which is not in this capture).
- `06:52:15` attempt 85 started logging normally and **fully recovered** (encoder created, YOLO
  loaded, pipeline up); daemon healthy through end of log (`06:56:30`). No code/config change —
  it recovered once the wedged resource cleared on its own.

Mechanism (grounded in the code's own comment at `CameraDaemon.java:1353–1358`, on the shutdown
hook registered at line 1359):
> *"the MediaCodec encoder, EGL context, camera HAL connection, and TFLite GPU delegate leak
> across restarts. After 3-4 rapid restarts, the Adreno 610 runs out of GPU contexts and the
> hardware encoder exhausts its codec instance limit, causing system-level freezes."*

The shutdown hook is the ONLY thing that releases those native resources — and a JVM shutdown hook
runs on a clean exit/SIGINT/SIGTERM but **NOT on SIGABRT (134) or SIGKILL (137)**. So:
1. The healthy daemon caught a SIGINT *mid sentry-pipeline-startup*; the hook didn't finish
   releasing the half-initialised camera/EGL/codec → native GPU/HAL resources leaked.
2. Each subsequent start tried to re-acquire those exhausted Adreno contexts / codec instances and
   **SIGABRT'd in vendor native code before the logger even opened** → exit 134.
3. SIGABRT skips the shutdown hook too, so every failed attempt **compounds the leak** — a
   self-sustaining wedge until the system (mediaserver/HAL) reclaimed the orphaned resources after
   ~77 min, letting attempt 85 get a clean codec/context.
The launcher's existing `rm -f LOCK_FILE` on 134/137 (`DaemonLauncher.kt:287–289`) is NOT
sufficient — the blocker is leaked GPU/HAL state, not the singleton lock (which is cleared every
attempt yet the aborts continued).

Impact: the car was **completely unmonitored for ~77 minutes** (no recording, no sentry). Higher
user-facing severity than B (truncated clip + ~10 s gap), even though it fires less often.

### Recommended fixes
- **Release native GPU/EGL/codec/HAL resources on signal death, not just clean exit.** Install a
  SIGINT/SIGTERM handler (or a tiny native `atexit`/signal shim) that runs the same teardown as the
  shutdown hook, so a signal-kill can't leak the camera/EGL/codec contexts. The hook body already
  exists — the gap is that it doesn't run on the paths that actually kill this daemon.
- **Make the shutdown hook robust mid-startup.** The SIGINT landed ~5 s into a fresh sentry
  pipeline; ensure `stopGpuPipeline()` cleanly releases a *half-initialised* pipeline (idempotent /
  partial-state-safe) instead of bailing before the GPU/HAL release.
- **Watchdog escalation for early-abort loops.** If N consecutive attempts exit 134 with <Xs uptime
  and no `=== Log Started ===`, escalate beyond `rm lock` — e.g. force-restart mediaserver / the BYD
  camera service, back off harder, and **surface an alert** (the vehicle is unmonitored). Don't
  hammer a wedged HAL every 60 s for over an hour in silence.
- **Capture the native cause.** On exit 134, have the watchdog copy the latest `/data/tombstones/*`
  (and a logcat snapshot) next to the daemon log — the exact aborting native frame is not in
  stdout/stderr and is needed to confirm the GPU/codec-exhaustion hypothesis vs. another early
  native abort.
- Investigate **who sends SIGINT to a healthy 3.9 h daemon** during the ACC-OFF→sentry transition
  (UI app? AccSentryDaemon? shell parent?). If it's an intentional restart, convert it to a graceful
  code-0 shutdown so the hook runs and nothing leaks.

---

## Suggested next steps in Claude Code

1. Clone the repo fresh; confirm current `versionName` (`app/build.gradle.kts` shows `1.0` in the
   public tree — the public versioning does NOT obviously map to the user's "19.8"/"v7" naming, so
   identifying the exact regressed commit may require the user's installed version string).
2. If both versions' sources are obtainable, diff these areas between 19.8 and current:
   - `HardwareEventRecorderGpu` muxer-write-queue capacity, disk-writer thread priority/`nice`,
     and the drop policy.
   - `PanoramicCameraGpu` GL watchdog (`System.exit(0)` on 3 s block) — did 19.8 have it / a longer
     timeout / a non-fatal recovery?
   - Anything new writing concurrently to the same USB volume during recording (trip telemetry
     `.jsonl.gz`, overlay burn-in, thumbnail/"hero" generation, MQTT).
3. Candidate fixes to evaluate (let the maintainer decide):
   - Make the disk-writer drain robust under sustained backpressure (larger/adaptive queue,
     dedicated high-prio writer, batched/async muxer writes) so it never reaches a GL block.
   - On watchdog timeout caused by write backpressure, finalize/close the current segment cleanly
     before exit instead of leaving a `.broken` stub.
   - Fix the misleading "SD card likely stalled" message when `sd=false` (USB target).
   - `RecordingModeManager` Finding A: handle `recorder == null` in `startRecording()`.
4. Practical user workaround until patched: **stay on 19.8** (known good on this hardware).

## How to feed this to Claude Code
- Put this file in the repo root (e.g. `CONTEXT.md`) or keep it anywhere and reference it.
- Start Claude Code in the repo dir and say: "Read OVERDRIVE_RECORDING_HANDOFF.md and continue
  the investigation; focus on Finding B." Optionally copy durable facts into `CLAUDE.md` so they
  auto-load every session.
- The two log files are the primary evidence — attach them to the Claude Code session if you want
  it to re-verify rather than trust this summary.

# Dual Lens 3D Camera

An android app (Pixel 7+ and newer Samsung S-series) for taking stereo photos and video using
the wide and ultrawide lenses.

It's currently tested and working on:
- Samsung S22 (photos only), S24 and Fold5.
- Pixel 7, 7a, 7 Pro, 8, 8 Pro, 9a, and 10.

So far, left-right image sync is best on Pixel 7 series (~1-4ms for photos, ~4ms for video).

Phones with ultrawide autofocus (e.g., Pixel 7 Pro) will provide the best image quality.

### Motorola Moto G Stylus 2026 (dual-device mode, `0.79.10-moto1`)

This fork adds a **dual-device** mode for the Moto G Stylus 2026 (`lamy`, Android 16),
which does **not** expose a logical multi-camera: its `cameraIdList` is only `0, 1` and no
camera reports `LOGICAL_MULTI_CAMERA`. The ultrawide is a hidden camera (ID 2, f=2.13mm,
FOV 95.1°) that can still be opened directly, and — critically — ID 0 (wide, f=5.56mm)
and ID 2 can be opened **at the same time**.

How it works:

- `LensDiscovery` scans IDs `"0"`–`"20"` for hidden back-facing cameras, classifies by
  focal length (smallest = ultrawide, next = wide), and returns a rig with
  `mode=DUAL_DEVICE`. This branch runs for Moto G Stylus models (or when logical
  discovery fails and a hidden dual-rear pair is found). The Pixel/Samsung logical path
  is untouched.
- `StereoCameraController` opens **two** `CameraDevice`s instead of one logical device.
  The wide session carries the preview surface + wide ImageReader; the ultra session
  carries the ultra ImageReader (photo) or ultra encoder surface (video), plus —
  since `0.78.0` — its own preview surface for the GPU SBS preview. Still
  captures are submitted to both devices back-to-back on the camera thread and the
  existing SBS alignment / JSON logging is reused; `Δstart` is logged the same way.
- **Concurrent-safe sizes only in dual-device mode.** The mandatory concurrent stream
  combinations cap a single stream at **1920x1440** per camera while both are open, so
  photo/video YUV sizes are capped at 1920x1440 (default photo YUV 1440x1080). Do **not**
  select 4000x3000 in this mode — it exceeds the concurrent limit. RAW uses per-lens
  sizes (wide 4096x3072, ultra 4160x3120); both lenses are LEVEL_3 + RAW capable, so
  the Pixel-only RAW gate is relaxed for dual-device mode only (Samsung stays blocked).
- Hold the phone in **landscape** (lenses are stacked vertically, so landscape gives a
  horizontal baseline). SBS LEFT = ultrawide, RIGHT = wide, as on Samsung. On the
  Moto the app locks to landscape (`0.76.5-moto1`) so the UI text stays upright in
  both landscape directions, with a preview as tall as the screen and
  aspect-matched to the preview size, and shutter/record down the right edge;
  the stitch shows a progress bar while it converts. The preview transform
  follows the Camera2Basic sample recipe (`0.76.6-moto1` — `0.76.5` shipped
  a broken no-rotation transform that left the preview sideways and
  stretched).
- **2x zoom is digital crop in dual-device mode (`0.76.5-moto1`).** The 1x/2x
  toggle applies the same centered crop to both lenses (preview, video, and
  stills), so the stereo geometry is unchanged. No 2x physical camera exists
  on this phone; zoom can't change mid-recording.
- Video is implemented in dual-device mode (one encoder + muxer per lens, both repeating
  requests started together), but **untested on real hardware** — test photos first.
- **Color-matched SBS photos (`0.76.1-moto1`, plain SBS dropped in `0.76.2`).**
  Because the two lenses run independent AWB/AE, the eyes can differ a lot in color:
  on the first test photo the wide was much warmer and darker than the ultrawide.
  Every photo saves `*_sbs_matched_wide.jpg` (both eyes synced to the wide's colors)
  and `*_sbs_matched_ultra.jpg` (both eyes synced to the ultrawide's colors), built
  with per-channel mean/std color transfer over the overlap region only. The plain
  unmatched `_sbs.jpg` is no longer saved.
- **Stitched SBS video (`0.76.2-moto1`).** After a recording stops, the app
  combines the two MP4s into one side-by-side `*_sbs.mp4` in the background
  (same alignment as photos, colors matched to the wide — warmer — by default).
  Settings > Video has a "Stitched SBS video" switch and a "SBS video color match"
  choice (Warmer / Cooler / Off). The two separate MP4s are always kept too.
- **Direct SBS video (`0.77.0-moto1`, crash fixes in `0.77.1`/`0.77.2`).** Settings > Video > "Video record mode"
  offers "Direct SBS" as an alternative to the two-stream path: the app waits
  ~1s for the lenses to settle, estimates the alignment + color match once from
  a settled frame pair, then composes the finished side-by-side video live and
  saves one `*_sbs.mp4` — no separate videos, no wait after stopping. CPU
  composition caps the per-eye size at 1440x1080 (a bigger record size records
  smaller in direct mode only), and a busy scene may drop frames. Any direct
  failure falls back to the two-stream path. Since `0.79.0` the GPU mode is
  the default and Direct is the fallback; two-stream is the last resort.
  `0.77.0`/`0.77.1` crashed during warm-up (the direct path allocated OpenCV
  Mats before the native library was loaded — an `UnsatisfiedLinkError`,
  found from the crash log in one read); `0.77.2` loads OpenCV first and
  catches native errors so warm-up can only fall back, never crash. A crash
  log is written to `Documents/StereoCapture/crash-<timestamp>.txt` if the
  app ever dies again.
- **GPU side-by-side preview (`0.78.0-moto1`).** The preview is now a
  `GLSurfaceView` (`preview/SbsPreviewView.kt`) instead of a TextureView:
  each lens feeds its own SurfaceTexture (a GPU external-OES texture) and
  an OpenGL ES 2.0 shader samples both eyes into two half-viewports, so no
  frame ever leaves the GPU and the preview runs at camera rate. Cross /
  parallel follows the SBS view setting and flips live with no camera
  restart. `0.78.1` fixes the two things found in on-device testing: the
  shader now rotates the sampling coordinates by the display rotation
  (the SurfaceTexture transform matrix does not rotate — the 0.78.0
  preview was sideways, the same bug as 0.76.5), and the ultrawide eye
  is warped into the wide's geometry, sampled from its central 66%
  (the same overlap the photo aligner falls back to).
  `0.78.2`: the preview is now live color-matched — the ultra eye gets
  a per-channel gain from the lenses' own AWB gains + exposure, updated
  twice a second (gain-only, so close to, not identical to, the photos'
  full mean/std match). It also silences the spurious "Wide camera
  disconnected" toast: that was the HAL reporting the app's own close
  on pause as a disconnect. And `onSurfaceCreated` now releases the
  previous SurfaceTexture pair, fixing a BufferQueue leak across
  pause/resume. Device testing showed all three issues still occurring on
  0.78.2, and four crash logs showed the launch crash was a
  different thing entirely — see `0.79.0` below.
- **GPU SBS video (`0.79.0-moto1`, reworked in `0.79.1`).** Settings >
  Video > "Video record mode" now offers "GPU SBS" (the default): the
  live GL preview is recorded straight into the video encoder, so
  there is no CPU compose, no ~1s warm-up and no post-stitch wait;
  the file is one finished `*_sbs.mp4` at 1600x600 (2x the 800x600
  preview). Fallback chain is GPU -> Direct -> two-stream. Direct SBS
  stays available for when the GPU path cannot start. `0.79.0` drew
  twice on the view's own GL thread and that froze the ultra eye when
  recording started; `0.79.1` renders the encoder on its own thread
  in a second EGL context sharing the view's (handle from
  `EGL14.eglGetCurrentContext()` on the GL thread — the EGL10
  `EGLContext` type cannot be cast to it), and the screen config is
  back to GLSurfaceView's default. The dual open is also serialized
  in `0.79.1`: the wide session streams first, then the ultra opens,
  and a device that disconnects mid-use is reopened on its own while
  the other eye keeps streaming.
- **Abandoned-surface crash fix (`0.79.2-moto1`).** A 0.79.1
  crash log showed `OutputConfiguration` throwing "Surface was
  abandoned": the GL view released its old SurfaceTexture pair in
  `onSurfaceCreated` while a camera open was still in flight with the
  old preview surface. All eight session builders now build their
  output lists through `safeOutputConfigs` (an abandoned surface
  retries instead of crashing), and the old texture pair is released
  only after the first frame lands in the new textures.
- **Ultra freeze on record + black preview (`0.79.3-moto1`).**
  `startRecording` closed the still readers before the GPU branch,
  but the ultra preview session's repeating request targets the ultra
  reader surface — closing it tore the ultra stream down the moment
  recording started. The readers are now closed only when falling
  through to Direct / two-stream (which build their own). The black
  preview was a stale preview retry closing a newer working open;
  retries are now generation-guarded.
- **Audio/video desync + fps setting ignored (`0.79.4-moto1`).**
  The audio feed loop waited for a codec input buffer before
  reading the mic and skipped the read when none was free, so while
  the muxer was busy with video ~40% of the mic samples were
  silently dropped and the audio track came out ~0.6x as long as
  the video. The mic is now read on its own thread into a bounded
  PCM queue, so a slow codec can delay the audio but never lose
  samples. The GPU recorder also drew every preview frame (30fps)
  regardless of the requested record fps; it now thins draws to
  the requested rate.
- **14fps instead of 24, toast noise, black screen (`0.79.5-moto1`).**
  The 24fps throttle drew every other preview frame (~15fps); it
  now uses a target-time accumulator to thin 30fps to the requested
  rate. Preview retries are silent now (no toast) with a budget of
  4 and an 800ms delay, closing the failed devices first so the HAL
  can release them. When the retries are spent, the activity
  restarts the camera itself (bounded x2) instead of leaving a
  black preview and asking her to reopen the app.
- **"Wide disconnected" toast + black screen after Settings
  (`0.79.6-moto1`).** The wide dying before the ultra opened
  toasted with no recovery; it now routes through the same
  bounded, silent retry, as does an async wide-session configure
  failure. The Settings black screen was a stale-texture race: the
  camera could start with the old texture pair before the GL
  surface was recreated on resume. The activity now remembers
  which pair the camera was started with and restarts it when the
  listener delivers a new one.
- **Ultra eye swimming when wiggling (`0.79.7-moto1`).** The two
  lenses have no hardware sync, so one eye could lag the other by
  a frame. New setting (Settings > Preview > "Pair frames",
  default ON): the preview draws only once both eyes have a fresh
  frame, so they stay in step. A short wait keeps a stalled eye
  from freezing the preview. It bounds the shear to the cameras'
  phase difference; it can't eliminate it.
- **Pairing black flicker (`0.79.8-moto1`).** 0.79.7 gated the draw
  inside `onDrawFrame`, but GLSurfaceView swaps even when nothing
  was drawn, so a skipped draw showed up as a black frame. The gate
  now lives in the frame-available listeners instead: a draw is only
  requested once both eyes are fresh.
- **Color setting did nothing (`0.79.9-moto1`).** The "SBS video
  color match" option only drove Direct/stitched video, and the
  preview's AWB-gain match did nothing (the lenses report near-equal
  gains). The preview now measures its own colors from the
  framebuffer every 2 s (mean/std per eye) and works out the gain +
  offset that matches the corrected eye to the reference — the same
  transfer the photo/stitch path uses. The setting now drives the
  live preview and GPU video too; the GPU recording inherits the
  preview's colors through the render state.
- **Color match jumped every 2 s (`0.79.10-moto1`).** 0.79.9 jumped
  straight to each new correction and measured one small central
  patch per eye, so the colors stepped visibly and sometimes landed
  wrong. Now the statistics pool three spread-out patches per eye,
  the measurement runs every 4 s, and the gain/offset move only 30%
  of the way toward the target each time, so the colors drift gently
  and never step.
- **Launch-crash fix (`0.79.0-moto1`).** Four crash logs all
  showed the same thing: `createCaptureSessionByOutputConfigurations`
  throwing `CAMERA_DISCONNECTED` (x3) or `CAMERA_ERROR (-38)` on the
  CameraThread while building the dual preview sessions — the wide
  disconnecting while the ultra opens — and nothing caught the throw,
  so it killed the thread and the app. All eight session-creation call
  sites now go through `createSessionSafe`; a failed preview session
  retries the open (bounded, 2 attempts), and stale-device disconnects
  are ignored by identity instead of toasting. If it still crashes,
  read the crash log in `Documents/StereoCapture/crash-<timestamp>.txt`.
  If the phone refuses the second preview stream, the preview falls back
  to the wide lens alone with a toast and photos/video keep working.
  Settings > Preview has an "SBS preview" switch (default on). The
  rotation direction was derived from the old TextureView recipe, not
  measured — if it is upside-down on-device, the signs flip in one line.

**First photo test passed (2026-11-21).** A 2048x768 SBS from the phone captured and
aligned correctly on both lenses, and the matched photos were confirmed good.
Still untested: the stitched video and depth direction — check depth with a near
object; if left/right look inverted, flip which image is SBS-left in `StereoSbs`
(or which ID is treated as left). Expect a larger photo sync delta than Pixel
(Samsung-class, ~20–30ms+ is plausible), and a risk that opening the second camera
disconnects the first on some attempts.
See `DEVELOPMENT_LOG.md` (2026-11-21 entries) for the full build/test notes.

### Where to find the APK

Check the latest release [here](../../releases).

### How to use it
- Pixels: hold the phone in **portrait**.
- Samsung: hold the phone in **landscape**.
- Press the PHOTO or RECORD buttons to take photos or video.
- Use 1x/2x toggle to zoom (2x uses wide sensor crop when a device supports/exposes it).
- Outputs are stored in `Pictures/StereoCapture`.

### Known issues

- Video recording doesn't work on Samsung S22.
- Not working at all on Samsung A or FE models.
- RAW shooting doesn't work on any Samsung device.
- Alignment not working on Samsung Fold5.
- Left-Right photo sync is ~30ms+ on Samsung.
- Left-Right photo sync is ~20ms+ on Pixel 8 / Pixel 8 Pro.

### How to assess sync between left and right photos

Method 1 (easiest):
1. Enable "Photo sync toast" in the settings.
2. A brief message will be displayed.
3. `Δstart` is the difference in sensor timestamps (wide - ultra).
4. `overlap` is the duration where both lenses were integrating light at the same time.

Method 2 (advanced / more detail):
1. Enable "Stereo photo JSON log" in the settings.
2. Take a photo. Then check your `Documents/StereoCapture` folder for the json output.

The JSON file contains several fields, such as:

- `sensorTimestampMs`: start time of exposure (milliseconds, monotonic sensor clock)
- `exposureTimeMs`: how long the sensor integrated light (milliseconds)
- `deltaStartMs`: difference in sensor timestamps (wide - ultra)
- `overlapMs`: duration where both lenses were integrating light at the same time
- `nonOverlapMs`: duration where exactly one lens was integrating (wide+ultra - 2*overlap)
- `idleGapMs`: if exposures are disjoint, time when neither lens was integrating (else 0)
- `unionMs`: total span from earliest start to latest end (overlap + nonOverlap + idleGap)
- `overlapPctOfShorter`: % of shorter exposure was that was simultaneous
- `overlapPctOfLonger`: % of longer exposure was that was simultaneous

### How to assess sync between left and right videos

1. Enable "Stereo video JSON log" in the settings.
2. Optional: Enable "frames only" for less logging detail.
3. Take a video. Then check your `Documents/StereoCapture` folder for the json output.

The JSON file contains several fields, such as:
- `frameNumber`: `TotalCaptureResult.frameNumber` for the capture request.
- `sensorTimestampNs`: `CaptureResult.SENSOR_TIMESTAMP` (nanoseconds).
This is the **start of exposure** of the first sensor row.
- `exposureTimeNs` (full mode): `CaptureResult.SENSOR_EXPOSURE_TIME`.
- `codecPresentationTimeUs` (full mode): `MediaCodec.BufferInfo.presentationTimeUs`.
- `muxerPresentationTimeUs` (full mode): the PTS written to `MediaMuxer` (per-track normalized to
start at 0 in this app).

### Implementation Details

This app uses the Android **Camera2** API to capture from the rear **logical** camera and route output
buffers to the **wide** and **ultrawide** physical cameras simultaneously (current IDs
are discovered at runtime via `LensDiscovery.kt`).

High-level flow:
- **MainActivity** owns the UI (preview, PHOTO/RECORD buttons, RAW toggle, torch, bitrate/size/EIS
  settings) and delegates camera work to `StereoCameraController`.
- **StereoCameraController.kt** is the core:
    - Opens the logical rear camera and creates two capture sessions:
        - **Preview session**: wide preview surface + ImageReaders for stereo still capture.
        - **Recording session**: wide + ultrawide `MediaCodec` input surfaces + preview surface.
    - Uses `createCaptureSessionByOutputConfigurations()` and
      `OutputConfiguration.setPhysicalCameraId()` so each output surface is bound to the intended
      physical lens.
    - Requests fixed FPS (`CONTROL_AE_TARGET_FPS_RANGE = 30..30`) and minimizes hidden cropping when
      stabilization is OFF (full `SCALER_CROP_REGION`).
    - Optionally restricts ultrawide 3A (AF/AE/AWB) to a centered region that roughly overlaps the
      wide view by setting physical `CONTROL_*_REGIONS` for the ultrawide (this does **not** crop saved
      images; it only affects metering/AF decisions).

Encoding / muxing:
- **VideoEncoder.kt**: one H.264 encoder per lens (two independent bitstreams). Uses a `Surface`
  input from the camera session. Logs both the codec output PTS and the normalized muxer PTS when
  enabled.
- **AudioEncoder.kt**: AAC encoder fed by `AudioRecord`. The same encoded audio samples are written
  into **both** MP4 files so each stereo video is self-contained.
- **Mp4Muxer.kt**: a thin synchronized wrapper around `MediaMuxer`:
    - waits to start until both audio + video tracks are added,
    - buffers samples briefly before `muxer.start()`,
    - writes samples to one MP4 file (one muxer per output video).

Optional per-recording JSON logging:
- **StereoRecordingLogger.kt** writes one `*_stereo.json` per recording (when enabled).
- Two modes exist:
    - **framesOnly**: logs only `{frameNumber, sensorTimestampNs}` per lens.
    - **full**: also logs exposure time and encoder/muxer timestamps.

Output files:
- Photos/videos are saved to `Pictures/StereoCapture` with timestamped filenames:
  - `*_wide.*`, `*_ultrawide.*`, `*_sbs.jpg` (JPEG mode), and optional `*_stereo.json`.

### Development log

See [DEVELOPMENT_LOG.md](DEVELOPMENT_LOG.md) for chronological investigation notes, design
decisions, failed approaches, validation results, and links back to relevant Git history.

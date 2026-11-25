# Dual Lens 3D Camera

An android app (Pixel 7+ and newer Samsung S-series) for taking stereo photos and video using
the wide and ultrawide lenses.

It's currently tested and working on:
- Samsung S22 (photos only), S24 and Fold5.
- Pixel 7, 7a, 7 Pro, 8, 8 Pro, 9a, and 10.

So far, left-right image sync is best on Pixel 7 series (~1-4ms for photos, ~4ms for video).

Phones with ultrawide autofocus (e.g., Pixel 7 Pro) will provide the best image quality.

### Motorola Moto G Stylus 2026 (dual-device mode, `0.77.2-moto1`)

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
  carries only the ultra ImageReader (photo) or ultra encoder surface (video). Still
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
  failure falls back to the two-stream path. Two-stream stays the default.
  `0.77.0`/`0.77.1` crashed during warm-up (the direct path allocated OpenCV
  Mats before the native library was loaded — an `UnsatisfiedLinkError`,
  found from the crash log in one read); `0.77.2` loads OpenCV first and
  catches native errors so warm-up can only fall back, never crash. A crash
  log is written to `Documents/StereoCapture/crash-<timestamp>.txt` if the
  app ever dies again.

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

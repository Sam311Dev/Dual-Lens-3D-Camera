package com.example.duallens3dcamera.camera

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.media.ExifInterface as PlatformExif
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.example.duallens3dcamera.util.StereoSbs
import com.example.duallens3dcamera.encoding.AudioEncoder
import com.example.duallens3dcamera.encoding.DirectSbsRecorder
import com.example.duallens3dcamera.encoding.Mp4Muxer
import com.example.duallens3dcamera.encoding.StereoVideoStitcher
import com.example.duallens3dcamera.encoding.VideoEncoder
import com.example.duallens3dcamera.settings.AppSettings
import com.example.duallens3dcamera.image.toNv21
import com.example.duallens3dcamera.media.DngWriter
import com.example.duallens3dcamera.media.ImageWriters
import com.example.duallens3dcamera.media.MediaStoreUtils
import com.example.duallens3dcamera.util.SizeSelector
import com.example.duallens3dcamera.logging.StereoRecordingLogger
import com.example.duallens3dcamera.logging.StereoPhotoLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult



class StereoCameraController(
    private val context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onStatus(msg: String)
        fun onError(msg: String)
        fun onFallbackSizeUsed(msg: String)
        fun onRecordingStarted()
        fun onRecordingStopped()
        fun onPhotoCaptureDone()
        /** Background SBS stitch progress (0.76.5): stage text + frames done/total. */
        fun onStitchProgress(stage: String, done: Int, total: Int)
        /** Background SBS stitch finished: success + stitched frame count. */
        fun onStitchDone(success: Boolean, frames: Int)
        /** Direct SBS mode (0.77.0): the cameras are running and the lenses
         *  are settling before encoding starts. */
        fun onDirectWarmup() {}
    }

    data class PreviewConfig(
        val previewSize: Size,
        val sensorOrientation: Int,
        val recordSize: Size
    )

    data class VideoConfig(
        val requestedRecordSize: Size,
        val requestedRecordFps: Int,
        val videoBitrateBps: Int,
        val eisEnabled: Boolean
    )

    data class PreviewSettings(
        val requestedPreviewSize: Size,
        val previewTargetFps: Int
    )

    enum class StillResolutionMode(val prefValue: String) {
        /** Use the largest still size available to each lens independently. */
        LARGEST_PER_LENS("per_lens"),

        /** Use the largest still size that is supported by BOTH lenses (best for sync). */
        LARGEST_COMMON("common");

        companion object {
            fun fromPrefValue(value: String?): StillResolutionMode {
                return when (value) {
                    LARGEST_PER_LENS.prefValue -> LARGEST_PER_LENS
                    else -> LARGEST_COMMON
                }
            }
        }
    }

    data class PhotoTuning(
        val noiseReductionMode: Int,
        val distortionCorrectionMode: Int,
        val edgeMode: Int,
        val stillResolutionMode: StillResolutionMode = StillResolutionMode.LARGEST_COMMON
    )

    data class StartParams(
        val surfaceTexture: SurfaceTexture,
        val displayRotation: Int,
        val rawMode: Boolean,
        val torchOn: Boolean,
        val zoom2xEnabled: Boolean,
        val video: VideoConfig,
        val preview: PreviewSettings,
        val photo: PhotoTuning
    )

    companion object {
        private const val TAG = "StereoCameraController"

        // Pixel-based guidance (hardcoded)
        private const val LOGICAL_REAR_ID = "0"
        private const val WIDE_1X_PHYSICAL_ID = "2"
        // Google exposes the 2x crop of the main wide lens as another physical camera.
        // Non‑Pro Pixels: usually "4". Pro Pixels: usually "5".
        private const val WIDE_2X_PHYSICAL_ID = "4"
        private const val WIDE_2X_PHYSICAL_ID_PRO = "5"
        private const val ULTRA_PHYSICAL_ID = "3"


        private val PREFERRED_RECORD_SIZE = Size(1440, 1080)
        private const val TARGET_FPS = 30

        private const val IFRAME_INTERVAL_SEC = 1

        // Audio knobs
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNELS = 1
        private const val AUDIO_BITRATE_BPS = 128_000

        // ImageReader queue depth for still capture.
        // YUV frames are large (uncompressed), so keep this modest to avoid OOM when spamming.
        // Limit used to be 8 for jpg, but YUV is larger
        private const val YUV_MAX_IMAGES = 4
        private const val RAW_MAX_IMAGES = 3

        // Restrict ultrawide AE/AF/AWB decisions to the central 70% of its FoV (trim 30% total).
        // make it a default instead and overwrite based on lenses found
        private const val ULTRA_3A_FRACTION_DEFAULT = 0.70f
        // Computed per device from logical CONTROL_ZOOM_RATIO_RANGE if available; else default.
        private var ultra3aFraction: Float = ULTRA_3A_FRACTION_DEFAULT

        // Debugging defaults (actual toggles live in Settings)
        private const val DEFAULT_ENABLE_STEREO_RECORDING_LOG = false // disables all video logs
        private const val DEFAULT_STEREO_LOG_FRAMES_ONLY = false // frameNumber and SENSOR_TIMESTAMP only for video logs
        private const val DEFAULT_ENABLE_PHOTO_JSON_LOG = false
        private const val DEFAULT_ENABLE_PHOTO_SYNC_TOAST = false

    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // threads so photos can output during shutter spamming
    // two threads instead for jpg/dng output
    private val ioExecutor = Executors.newFixedThreadPool(2)
    private val encodingExecutor = Executors.newSingleThreadExecutor()
    // Separate thread for SBS alignment/encoding so it never blocks photo IO.
    private val sbsExecutor = Executors.newSingleThreadExecutor()

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    // MOTO dual-device mode (no logical camera): a second, standalone CameraDevice +
    // capture session for the ultrawide. cameraDevice/session above are the WIDE ones;
    // ultraCameraDevice/ultraSession are the ULTRA ones. Null in logical mode.
    private var ultraCameraDevice: CameraDevice? = null
    private var ultraSession: CameraCaptureSession? = null
    private var ultraRepeatingBuilder: CaptureRequest.Builder? = null
    // True once the dual-device rig was discovered (Moto G Stylus 2026 and friends).
    private var isDualDevice: Boolean = false
    // True while the ultra device is still opening (wide opened first, ultra second).
    private var dualUltraOpening: Boolean = false

    private var surfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null

    private var logicalChars: CameraCharacteristics? = null
    private var wideChars: CameraCharacteristics? = null
    private var ultraChars: CameraCharacteristics? = null

    // Resolved per-device lens rig (Pixel/Samsung etc) from LensDiscovery.
    private var logicalRearId: String = LOGICAL_REAR_ID
    private var wide1xPhysicalId: String = WIDE_1X_PHYSICAL_ID
    private var wide2xPhysicalId: String? = null
    private var ultraPhysicalId: String = ULTRA_PHYSICAL_ID

    // Is the phone a Pixel or not (assumed Samsung if not, for now)
    private var phoneIsAPixel: Boolean = true

    // Active wide physical ID for the current zoom mode.
    private var widePhysicalId: String = WIDE_1X_PHYSICAL_ID


    @Volatile private var zoom2xEnabled: Boolean = false
    private var hasWide2xPhysical: Boolean = false

    /**
     * Digital crop zoom for dual-device mode (0.76.5): 1.0 = full sensor,
     * 2.0 = centered half-size window. The SAME relative crop is applied to
     * both lenses, so the stereo geometry is unchanged (the SBS aligner
     * must therefore NOT do its extra ultra crop — see the photo path).
     */
    @Volatile private var zoomCropFactor: Float = 1.0f


    private var wideMap: StreamConfigurationMap? = null
    private var ultraMap: StreamConfigurationMap? = null

    private var sensorOrientation: Int = 90
    private var displayRotation: Int = Surface.ROTATION_0

    private var recordSize: Size = PREFERRED_RECORD_SIZE
    //    private var previewSize: Size = Size(1280, 960)
    // Default fallback (overridden by settings)
    private var previewSize: Size = Size(800, 600)

    private var yuvWideSize: Size = PREFERRED_RECORD_SIZE
    private var yuvUltraSize: Size = PREFERRED_RECORD_SIZE

    private var rawWideSize: Size? = null
    private var rawUltraSize: Size? = null

    private var isRawMode: Boolean = false
    private var torchOn: Boolean = false
    private var isRecording: Boolean = false
    // Timestamp stem of the current recording ("yyyy-MM-dd-HH-mm-ss-SSS");
    // used to name the stitched *_sbs.mp4 after stop. Null when not recording.
    private var currentRecordingId: String? = null

    // mutable video config (set from UI, persisted in Activity)
    private var requestedRecordSize: Size = Size(1440, 1080)  // default corresponds to upright 1080x1440
    private var requestedRecordFps: Int = TARGET_FPS
    private var videoBitrateBps: Int = 50_000_000             // default 50 Mbps
    private var eisEnabled: Boolean = false                   // default OFF

    // preview config (settings)
    private var requestedPreviewSize: Size = Size(800, 600)
    private var previewTargetFps: Int = TARGET_FPS

    // photo tuning (settings)
    private var photoNoiseReductionMode: Int = CaptureRequest.NOISE_REDUCTION_MODE_OFF
    private var photoDistortionCorrectionMode: Int =
        CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
    private var photoEdgeMode: Int = CaptureRequest.EDGE_MODE_OFF
    private var photoStillResolutionMode: StillResolutionMode = StillResolutionMode.LARGEST_COMMON

    // video processing (settings) -- applied to RECORDING only; preview is forced OFF.
    private var videoNoiseReductionMode: Int = CaptureRequest.NOISE_REDUCTION_MODE_OFF
    private var videoDistortionCorrectionMode: Int = CaptureRequest.DISTORTION_CORRECTION_MODE_FAST
    private var videoEdgeMode: Int = CaptureRequest.EDGE_MODE_OFF

    // Debugging (Advanced)
    private var enableStereoRecordingLog: Boolean = DEFAULT_ENABLE_STEREO_RECORDING_LOG
    private var stereoLogFramesOnly: Boolean = DEFAULT_STEREO_LOG_FRAMES_ONLY
    private var enablePhotoJsonLog: Boolean = DEFAULT_ENABLE_PHOTO_JSON_LOG
    private var enablePhotoSyncToast: Boolean = DEFAULT_ENABLE_PHOTO_SYNC_TOAST

    @Volatile private var saveIndividualLensImages: Boolean = false


    // Ultrawide priming (settings)
    // OFF by default. When enabled, we fire one ultrawide-only discarded capture when the
    // preview session becomes active. This is a lightweight workaround for some Pixel phones
    // with ultrawide autofocus that can produce a blurry first ultrawide frame otherwise.
    @Volatile private var primeUwOnActiveEnabled: Boolean = false
    private var primeUwPending: Boolean = false
    private var primeUwInFlight: Boolean = false
    private val primeUwRunnable = Runnable { runPrimeUwIfNeeded() }


    // Preview repeating builder for current mode.
    private var repeatingBuilder: CaptureRequest.Builder? = null

    // Still outputs for current photo mode (JPEG or RAW)
    private var wideReader: ImageReader? = null
    private var ultraReader: ImageReader? = null

    // Recording pipeline
    private var wideMuxer: Mp4Muxer? = null
    private var ultraMuxer: Mp4Muxer? = null
    private var wideEncoder: VideoEncoder? = null
    private var ultraEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null

    private var wideVideoUri: Uri? = null
    private var ultraVideoUri: Uri? = null

    // Direct SBS recording (0.77.0): one finished *_sbs.mp4 recorded live
    // from both lenses. No per-lens encoders/muxers in this mode.
    private var isDirectRecording: Boolean = false
    private var directRecorder: DirectSbsRecorder? = null
    private var directVideoUri: Uri? = null
    private var directRecordingId: String? = null
    private var dualDirectWideReady: Boolean = false
    private var dualDirectUltraReady: Boolean = false

    // Photo capture coordination
    private data class PhotoCapture(
        val isRaw: Boolean,
        val captureId: String,
        val wallTimeMs: Long,
        val wideUri: Uri?,
        val ultraUri: Uri?,
        // Color-matched SBS variants (built from the aligned SBS bytes held in
        // memory): both eyes synced to the wide's colors, and both eyes synced
        // to the ultra's colors. The plain unmatched SBS is NOT saved anymore
        // (the tester only wants the two matched versions). Null in RAW mode
        // (no SBS at all there).
        val sbsMatchedWideUri: Uri? = null,
        val sbsMatchedUltraUri: Uri? = null,
        val logUri: Uri? = null,
        val exifDateTimeOriginal: String,
        val rotationDegrees: Int,
        @Volatile var wideImage: Image? = null,
        @Volatile var ultraImage: Image? = null,
        @Volatile var totalResult: TotalCaptureResult? = null,
        // Dual-device mode: each camera delivers its own TotalCaptureResult (there is
        // no logical result with physicalCameraResults). Null in logical mode.
        @Volatile var wideResult: TotalCaptureResult? = null,
        @Volatile var ultraResult: TotalCaptureResult? = null,
        @Volatile var finished: Boolean = false
    )

    @Volatile private var currentPhoto: PhotoCapture? = null

    // for logging:
    private var recordingLogger: StereoRecordingLogger? = null
    private var recordingLogUri: Uri? = null
    // Keep the recording capture callback so updateTorchOnRepeating() can reuse it while recording.
    private var recordCaptureCallback: CameraCaptureSession.CaptureCallback? = null
    // end logging vars

    fun start(params: StartParams): PreviewConfig {
        this.surfaceTexture = params.surfaceTexture
        this.displayRotation = params.displayRotation
        this.isRawMode = params.rawMode
        this.torchOn = params.torchOn

        // video config
        this.requestedRecordSize = params.video.requestedRecordSize
        this.requestedRecordFps = params.video.requestedRecordFps
        this.videoBitrateBps = params.video.videoBitrateBps
        this.eisEnabled = params.video.eisEnabled

        // preview config
        this.requestedPreviewSize = params.preview.requestedPreviewSize
        this.previewTargetFps = params.preview.previewTargetFps

        // photo tuning
        this.photoNoiseReductionMode = params.photo.noiseReductionMode
        this.photoDistortionCorrectionMode = params.photo.distortionCorrectionMode
        this.photoEdgeMode = params.photo.edgeMode
        this.photoStillResolutionMode = params.photo.stillResolutionMode

        this.zoom2xEnabled = params.zoom2xEnabled
        // We'll pick the correct wide physical ID in selectCharacteristicsAndSizes() after LensDiscovery runs.
        this.widePhysicalId = WIDE_1X_PHYSICAL_ID

        startThreads()
        selectCharacteristicsAndSizes()

        params.surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(params.surfaceTexture)

        openCamera()

        return PreviewConfig(previewSize = previewSize, sensorOrientation = sensorOrientation, recordSize = recordSize)
    }

    fun setVideoConfig(
        requestedRecordSize: Size,
        requestedRecordFps: Int,
        videoBitrateBps: Int,
        eisEnabled: Boolean
    ) {
        cameraHandler?.post {
            this.requestedRecordSize = requestedRecordSize
            this.requestedRecordFps = requestedRecordFps
            this.videoBitrateBps = videoBitrateBps
            this.eisEnabled = eisEnabled
//            callback.onStatus("Video config set: ${requestedRecordSize.width}x${requestedRecordSize.height} @${requestedRecordFps}fps, ${videoBitrateBps/1_000_000}Mbps, EIS=${eisEnabled}")
            // TODO: instead of showing the user, just log it
        }
    }

    fun setVideoProcessingModes(
        noiseReductionMode: Int,
        distortionCorrectionMode: Int,
        edgeMode: Int
    ) {
        this.videoNoiseReductionMode = noiseReductionMode
        this.videoDistortionCorrectionMode = distortionCorrectionMode
        this.videoEdgeMode = edgeMode
    }

    fun setDebugConfig(
        enableStereoRecordingLog: Boolean,
        stereoLogFramesOnly: Boolean,
        enablePhotoJsonLog: Boolean,
        enablePhotoSyncToast: Boolean
    ) {
        this.enableStereoRecordingLog = enableStereoRecordingLog
        this.stereoLogFramesOnly = stereoLogFramesOnly
        this.enablePhotoJsonLog = enablePhotoJsonLog
        this.enablePhotoSyncToast = enablePhotoSyncToast
    }

    fun setPhotoSaveIndividualLensImages(enabled: Boolean) {
        this.saveIndividualLensImages = enabled
    }

    fun setPrimeUwOnActive(enabled: Boolean) {
        // Called from UI thread. Marshal to the camera thread to avoid races with session state.
        val handler = cameraHandler
        if (handler != null) {
            handler.post {
                primeUwOnActiveEnabled = enabled
                if (!enabled) {
                    cancelPrimeUwLocked()
                    return@post
                }

                // Trigger once as soon as possible (either on the current session, or the next session start).
                primeUwPending = true
                handler.removeCallbacks(primeUwRunnable)
                handler.post(primeUwRunnable)
            }
        } else {
            primeUwOnActiveEnabled = enabled
            primeUwPending = enabled
        }
    }

    private fun recomputeRecordSizeLocked() {
        val wMap = wideMap ?: return
        val uMap = ultraMap ?: return

        // MOTO dual-device: mandatory CONCURRENT combos cap each camera at 1920x1440
        // while both are open, even though each camera alone lists up to 4000x3000.
        val capW = if (isDualDevice) minOf(requestedRecordSize.width, 1920) else requestedRecordSize.width
        val capH = if (isDualDevice) minOf(requestedRecordSize.height, 1440) else requestedRecordSize.height

        val (chosenRecord, fallbackMsg) = SizeSelector.chooseCommonPrivateSizeAtFps(
            wideMap = wMap,
            ultraMap = uMap,
            preferred = requestedRecordSize,
            maxW = capW,
            maxH = capH,
            targetFps = requestedRecordFps
        )
        recordSize = chosenRecord
        fallbackMsg?.let { callback.onFallbackSizeUsed(it) }

        Log.i(
            TAG,
            "VideoConfig requested=${requestedRecordSize.width}x${requestedRecordSize.height}@${requestedRecordFps} chosen=${recordSize.width}x${recordSize.height} bitrate=$videoBitrateBps eis=$eisEnabled"
        )
    }

    private fun recomputePreviewSizeLocked() {
        val wMap = wideMap ?: return

        val sizes = (wMap.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()).toList()
        if (sizes.isEmpty()) {
            previewSize = requestedPreviewSize
            return
        }

        val exact = sizes.firstOrNull {
            it.width == requestedPreviewSize.width && it.height == requestedPreviewSize.height
        }
        if (exact != null) {
            previewSize = exact
            return
        }

        val preferred = requestedPreviewSize
        val preferredIs43 = SizeSelector.isFourByThree(preferred)

        val candidates = if (preferredIs43) {
            val fourByThree = sizes.filter { SizeSelector.isFourByThree(it) }
            if (fourByThree.isNotEmpty()) fourByThree else sizes
        } else {
            sizes
        }

        val prefArea = preferred.width.toLong() * preferred.height.toLong()
        val chosen = candidates.minByOrNull { s ->
            val area = s.width.toLong() * s.height.toLong()
            kotlin.math.abs(area - prefArea)
        } ?: candidates.first()

        previewSize = chosen
        callback.onFallbackSizeUsed(
            "Preview size ${preferred.width}x${preferred.height} not available; using ${chosen.width}x${chosen.height}."
        )
    }

    private fun recomputeStillSizesLocked() {
        val wMap = wideMap ?: return
        val uMap = ultraMap ?: return

        when (photoStillResolutionMode) {
            StillResolutionMode.LARGEST_COMMON -> {
                // MOTO dual-device: cap the common still size at 1920x1440 (mandatory
                // concurrent limit). Largest common YUV 4:3 on the Moto is 4000x3000 —
                // do NOT use it while both cameras are open.
                val commonYuv = if (isDualDevice) {
                    SizeSelector.chooseLargestCommonYuvFourByThreeCapped(wMap, uMap, 1920, 1440)
                } else {
                    SizeSelector.chooseLargestCommonYuvFourByThree(wMap, uMap)
                }
                if (commonYuv != null) {
                    yuvWideSize = commonYuv
                    yuvUltraSize = commonYuv
                } else {
                    // If there is no intersection, we have no choice but to fall back to per-lens.
                    yuvWideSize = SizeSelector.chooseLargestYuvFourByThree(wMap)
                    yuvUltraSize = SizeSelector.chooseLargestYuvFourByThree(uMap)
                    callback.onFallbackSizeUsed(
                        "No common still YUV size found; using largest-per-lens still sizes instead."
                    )
                }

                // RAW sizes differ per lens on the Moto (wide 4096x3072, ultra 4160x3120),
                // so a common RAW size does not exist — use per-lens RAW in dual mode.
                if (isDualDevice) {
                    rawWideSize = SizeSelector.chooseLargestRaw(wMap)
                    rawUltraSize = SizeSelector.chooseLargestRaw(uMap)
                } else {
                    val commonRaw = SizeSelector.chooseLargestCommonRaw(wMap, uMap)
                    if (commonRaw != null) {
                        rawWideSize = commonRaw
                        rawUltraSize = commonRaw
                    } else {
                        rawWideSize = SizeSelector.chooseLargestRaw(wMap)
                        rawUltraSize = SizeSelector.chooseLargestRaw(uMap)
                    }
                }
            }

            StillResolutionMode.LARGEST_PER_LENS -> {
                // In dual-device mode the per-lens sizes are also capped at the
                // concurrent limit (1920x1440); uncapped they would be 4096x3072/4160x3120.
                if (isDualDevice) {
                    yuvWideSize = SizeSelector.chooseLargestCommonYuvFourByThreeCapped(wMap, wMap, 1920, 1440)
                        ?: Size(1920, 1440)
                    yuvUltraSize = SizeSelector.chooseLargestCommonYuvFourByThreeCapped(uMap, uMap, 1920, 1440)
                        ?: Size(1920, 1440)
                } else {
                    yuvWideSize = SizeSelector.chooseLargestYuvFourByThree(wMap)
                    yuvUltraSize = SizeSelector.chooseLargestYuvFourByThree(uMap)
                }
                rawWideSize = SizeSelector.chooseLargestRaw(wMap)
                rawUltraSize = SizeSelector.chooseLargestRaw(uMap)
            }
        }

        Log.i(
            TAG,
            "Still sizes (${photoStillResolutionMode.name}): YUV wide=$yuvWideSize ultra=$yuvUltraSize; RAW wide=$rawWideSize ultra=$rawUltraSize"
        )
    }

    fun stop() {
        val handler = cameraHandler ?: return

        val latch = CountDownLatch(1)
        handler.post {
            cancelPrimeUwLocked()

            try {
                safeStopRecordingInternal(deleteOnFailure = false)
            } catch (_: Exception) {
            }

            try { session?.close() } catch (_: Exception) {}
            session = null
            // Dual-device: close the ultrawide session/device too.
            try { ultraSession?.close() } catch (_: Exception) {}
            ultraSession = null
            ultraRepeatingBuilder = null
            dualUltraOpening = false

            try { cameraDevice?.close() } catch (_: Exception) {}
            cameraDevice = null
            try { ultraCameraDevice?.close() } catch (_: Exception) {}
            ultraCameraDevice = null

            closeReaders()

            try { previewSurface?.release() } catch (_: Exception) {}
            previewSurface = null
            surfaceTexture = null

            latch.countDown()
        }
        latch.await()

        stopThreads()
    }

    fun setTorch(enabled: Boolean) {
        torchOn = enabled
        val handler = cameraHandler ?: return
        handler.post { updateTorchOnRepeating() }
    }

    /**
     * The activity calls this when the device rotation changes (0.76.5: the
     * Moto activity runs in sensorLandscape). The value feeds
     * computeOrientationHint() and the per-frame rotation, so photos and
     * videos come out upright in both landscape directions.
     */
    fun setDisplayRotation(rotation: Int) {
        displayRotation = rotation
    }

    fun setPhotoOutputRaw(raw: Boolean) {
        val handler = cameraHandler ?: return
        handler.post {
            // don't change photo settings while recording video
            if (isRecording) return@post
            // RAW currently fails on Samsung so
            // don't allow it on them for now (force jpg instead).
            // MOTO dual-device is the exception: both Moto lenses are LEVEL_3 with
            // RAW_SENSOR, so RAW is allowed there (per-lens RAW sizes).
            if (!phoneIsAPixel && !isDualDevice && raw) {
                if (isRawMode) isRawMode = false
                callback.onStatus("RAW/DNG is Pixel-only for now (Samsung uses JPG only).")
                return@post
            }
            // otherwise, toggle RAW mode
            isRawMode = raw
            if (cameraDevice == null || previewSurface == null) return@post
            createPreviewSession()
        }
    }

    /**
     * Toggle between:
     *  - 1x: wide physical ID "2" + ultrawide physical ID "3"
     *  - 2x: wide physical ID "4" (remosaic crop of wide) + ultrawide ID "3" with a 50% centered crop.
     */
    fun setZoom2x(enabled: Boolean) {
        val handler = cameraHandler ?: run {
            callback.onError("Camera thread not running.")
            return
        }

        handler.post {
            if (isDualDevice) {
                // MOTO dual-device (0.76.5): no 2x physical camera, so zoom
                // is a digital crop — the SAME centered crop on both lenses,
                // so the stereo geometry (and the SBS alignment) is unchanged.
                if (zoom2xEnabled == enabled) return@post
                zoom2xEnabled = enabled
                zoomCropFactor = if (enabled) 2.0f else 1.0f
                // The cached affine is in pixel space of the uncropped
                // frame; a crop changes that mapping, so drop it.
                StereoSbs.resetLastGoodAffine()
                reapplyZoomCropToRepeating()
                return@post
            }
            if (isRecording) {
                callback.onStatus("Can't change zoom while recording.")
                return@post
            }

            if (zoom2xEnabled == enabled) return@post

            val logical = logicalChars
            if (logical == null) {
                callback.onError("Camera not ready.")
                return@post
            }

            // Validate 2x availability on this device.
            val physicalIds = logical.physicalCameraIds
            // Prefer the discovered wide2x ID (works for Samsung too); fall back to Pixel ID heuristic.
            val wide2xId = wide2xPhysicalId ?: resolveWide2xPhysicalId(physicalIds)

            if (enabled && wide2xId == null) {
                zoom2xEnabled = false
                widePhysicalId = wide1xPhysicalId
                callback.onStatus("2x mode not available (no wide 2x physical camera under logical $logicalRearId).")
                return@post
            }

            zoom2xEnabled = enabled
            widePhysicalId = if (enabled) wide2xId!! else wide1xPhysicalId

            // prevents the 1x affine from being reused right after a zoom-mode flip
            StereoSbs.resetLastGoodAffine()

            // Refresh characteristics/maps for the new wide physical camera.
            try {
                wideChars = cameraManager.getCameraCharacteristics(widePhysicalId)
                // Ultrawide stays fixed, but keep this non-null.
                if (ultraChars == null) {
                    ultraChars = cameraManager.getCameraCharacteristics(ultraPhysicalId)
                }
            } catch (e: Exception) {
                callback.onError("Failed switching zoom: ${e.message}")
                zoom2xEnabled = false
                widePhysicalId = wide1xPhysicalId
                wideChars = cameraManager.getCameraCharacteristics(widePhysicalId)
            }

            val wide = wideChars
            val ultra = ultraChars
            if (wide == null || ultra == null) {
                callback.onError("Camera characteristics missing after zoom switch.")
                return@post
            }

            sensorOrientation = wide.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: sensorOrientation

            wideMap = wide.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ultraMap = ultra.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            val wMap = wideMap
            val uMap = ultraMap

            if (wMap != null && uMap != null) {
                recomputeRecordSizeLocked()
                recomputePreviewSizeLocked()
                recomputeStillSizesLocked()
            }

            if (cameraDevice != null && previewSurface != null) {
                createPreviewSession()
            }
        }
    }

    /**
     * Digital crop zoom for dual-device mode (0.76.5). The crop window is
     * the same RELATIVE window on both sensors (centered, 1/factor of each
     * side), so both lenses see the same narrower field of view and the
     * stereo geometry is unchanged. At 1x the full active array is set
     * explicitly so the state is never left over from a previous 2x.
     */
    private fun zoomCropRect(chars: CameraCharacteristics?): Rect? {
        val active = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val f = zoomCropFactor
        if (f <= 1.0f) return Rect(active)
        val w = (active.width() / f).toInt().coerceAtLeast(2)
        val h = (active.height() / f).toInt().coerceAtLeast(2)
        val left = active.left + (active.width() - w) / 2
        val top = active.top + (active.height() - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /** Apply the current crop zoom to a request builder (dual-device only). */
    private fun applyZoomCrop(builder: CaptureRequest.Builder, chars: CameraCharacteristics?) {
        if (!isDualDevice) return
        val rect = zoomCropRect(chars) ?: return
        try { builder.set(CaptureRequest.SCALER_CROP_REGION, rect) } catch (_: Exception) {}
    }

    /** Re-issue the live preview repeating requests with the current crop. */
    private fun reapplyZoomCropToRepeating() {
        val handler = cameraHandler ?: return
        handler.post {
            try {
                val rb = repeatingBuilder
                val sess = session
                if (rb != null && sess != null) {
                    applyZoomCrop(rb, wideChars)
                    sess.setRepeatingRequest(rb.build(), null, handler)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reapply zoom crop (wide) failed: ${e.message}")
            }
            try {
                val urb = ultraRepeatingBuilder
                val usess = ultraSession
                if (urb != null && usess != null) {
                    applyZoomCrop(urb, ultraChars)
                    usess.setRepeatingRequest(urb.build(), null, handler)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reapply zoom crop (ultra) failed: ${e.message}")
            }
        }
    }

    fun captureStereoPhoto(timestampForFilename: String, raw: Boolean) {
        val handler = cameraHandler ?: run {
            callback.onError("Camera thread not running.")
            callback.onPhotoCaptureDone()
            return
        }
        handler.post {
            if (isRecording) {
                callback.onError("Cannot take photos while recording.")
                callback.onPhotoCaptureDone()
                return@post
            }
            if (cameraDevice == null || session == null) {
                callback.onError("Camera not ready.")
                callback.onPhotoCaptureDone()
                return@post
            }
            if (isDualDevice && (ultraCameraDevice == null || ultraSession == null)) {
                callback.onError("Ultrawide camera not ready (Moto dual-device).")
                callback.onPhotoCaptureDone()
                return@post
            }

            // Don't allow RAW on Samsung (fails there). Pixel and Moto dual-device
            // (both lenses LEVEL_3 + RAW) may use it.
            val effectiveRaw = raw && (phoneIsAPixel || isDualDevice)
            if (raw && !phoneIsAPixel && !isDualDevice) {
                callback.onStatus("RAW is Pixel-only for now (Samsung uses YUV).")
            }
            if (effectiveRaw != isRawMode) {
                isRawMode = effectiveRaw
                createPreviewSession()
            }

            val nowMs = System.currentTimeMillis()
            val exifDt = exifDateTimeOriginal(nowMs)
            val rotationDegrees = computeJpegOrientation()

            try {
                val saveIndividuals = saveIndividualLensImages || effectiveRaw

                val wideName = if (effectiveRaw) "${timestampForFilename}_wide.dng" else "${timestampForFilename}_wide.jpg"
                val ultraName = if (effectiveRaw) "${timestampForFilename}_ultrawide.dng" else "${timestampForFilename}_ultrawide.jpg"
                val wideMime = if (effectiveRaw) "image/x-adobe-dng" else "image/jpeg"
                val ultraMime = if (effectiveRaw) "image/x-adobe-dng" else "image/jpeg"

                val wideUri: Uri? = if (saveIndividuals) {
                    MediaStoreUtils.createPendingImage(context, wideName, wideMime, nowMs)
                } else null

                val ultraUri: Uri? = if (saveIndividuals) {
                    MediaStoreUtils.createPendingImage(context, ultraName, ultraMime, nowMs)
                } else null

                // Color-matched SBS variants (same alignment, colors synced to
                // one eye or the other). The plain unmatched SBS is not saved.
                val sbsMatchedWideUri = if (!effectiveRaw) {
                    val name = "${timestampForFilename}_sbs_matched_wide.jpg"
                    MediaStoreUtils.createPendingImage(context, name, "image/jpeg", nowMs)
                } else null

                val sbsMatchedUltraUri = if (!effectiveRaw) {
                    val name = "${timestampForFilename}_sbs_matched_ultra.jpg"
                    MediaStoreUtils.createPendingImage(context, name, "image/jpeg", nowMs)
                } else null

                val logUri = if (enablePhotoJsonLog) {
                    val logName = "${timestampForFilename}_photo.json"
                    MediaStoreUtils.createPendingJson(context, logName, nowMs)
                } else null

                currentPhoto = PhotoCapture(
                    isRaw = effectiveRaw,
                    captureId = timestampForFilename,
                    wallTimeMs = nowMs,
                    wideUri = wideUri,
                    ultraUri = ultraUri,
                    sbsMatchedWideUri = sbsMatchedWideUri,
                    sbsMatchedUltraUri = sbsMatchedUltraUri,
                    logUri = logUri,
                    exifDateTimeOriginal = exifDt,
                    rotationDegrees = rotationDegrees
                )

                doStillCapture(effectiveRaw)
            } catch (e: Exception) {
                callback.onError("Photo capture setup failed: ${e.message}")
                callback.onPhotoCaptureDone()
            }

        }
    }

    fun startRecording(timestampForFilename: String) {
        val handler = cameraHandler ?: run {
            callback.onError("Camera thread not running.")
            return
        }

        handler.post {
            cancelPrimeUwLocked()

            if (isRecording) {
                callback.onError("Already recording.")
                return@post
            }
            if (cameraDevice == null || previewSurface == null) {
                callback.onError("Camera not ready.")
                return@post
            }
            if (isDualDevice && ultraCameraDevice == null) {
                callback.onError("Ultrawide camera not ready (Moto dual-device).")
                return@post
            }

            isRecording = true
            currentRecordingId = timestampForFilename
            closeReaders() // reduce load during recording

            // 0.77.0: direct SBS mode (Moto dual-device only). If it can't
            // start, fall through to the two-stream path with the same id.
            if (AppSettings.isDirectRecordMode(context) && isDualDevice) {
                if (tryStartDirectRecordingLocked(timestampForFilename)) return@post
                callback.onStatus("Direct SBS couldn't start — using the two-stream mode")
            }

            startStitchedRecordingLocked(timestampForFilename)
        }
    }

    /**
     * Two-stream recording (the original path): one encoder + muxer per
     * lens, stitched into an SBS video after stop. Assumes isRecording and
     * currentRecordingId are already set and the photo readers are closed.
     */
    private fun startStitchedRecordingLocked(timestampForFilename: String) {
            val nowMs = System.currentTimeMillis()
            val wideName = "${timestampForFilename}_wide.mp4"
            val ultraName = "${timestampForFilename}_ultrawide.mp4"

            try {
                wideVideoUri = MediaStoreUtils.createPendingVideo(context, wideName, nowMs)
                ultraVideoUri = MediaStoreUtils.createPendingVideo(context, ultraName, nowMs)

                val widePfd =
                    context.contentResolver.openFileDescriptor(requireNotNull(wideVideoUri), "rw")
                        ?: throw IllegalStateException("Failed to open wide video fd")
                val ultraPfd =
                    context.contentResolver.openFileDescriptor(requireNotNull(ultraVideoUri), "rw")
                        ?: throw IllegalStateException("Failed to open ultra video fd")

                val orient = computeOrientationHint()

                // Create muxers (one per output file)
                wideMuxer = Mp4Muxer("WideMuxer", widePfd, orient)
                ultraMuxer = Mp4Muxer("UltraMuxer", ultraPfd, orient)

                // Start audio codec first (adds audio track via INFO_OUTPUT_FORMAT_CHANGED).
                audioEncoder = AudioEncoder(
                    tag = "AudioEncoder",
                    muxers = listOf(requireNotNull(wideMuxer), requireNotNull(ultraMuxer)),
                    sampleRate = AUDIO_SAMPLE_RATE,
                    channelCount = AUDIO_CHANNELS,
                    bitrateBps = AUDIO_BITRATE_BPS
                ).also { it.start() }

                // Start two independent video encoders (two independent H.264 bitstreams)
                wideEncoder = VideoEncoder(
                    tag = "WideVideoEncoder",
                    width = recordSize.width,
                    height = recordSize.height,
                    fps = requestedRecordFps,
                    bitrateBps = videoBitrateBps,
                    iFrameIntervalSec = IFRAME_INTERVAL_SEC,
                    muxer = requireNotNull(wideMuxer)
                ).also { it.start() }

                ultraEncoder = VideoEncoder(
                    tag = "UltraVideoEncoder",
                    width = recordSize.width,
                    height = recordSize.height,
                    fps = requestedRecordFps,
                    bitrateBps = videoBitrateBps,
                    iFrameIntervalSec = IFRAME_INTERVAL_SEC,
                    muxer = requireNotNull(ultraMuxer)
                ).also { it.start() }

                if (enableStereoRecordingLog) {
                    // for logging, use exact same timestamp used in filenames
                    // (match wide/ultra/video/log easily)
                    val logName = "${timestampForFilename}_record.json"
                    recordingLogUri = MediaStoreUtils.createPendingJson(
                        context,
                        logName,
                        nowMs
                    )
                }

                // Create logger only if logging enabled
                if (enableStereoRecordingLog) {
                    val wideVideoUriLocal = requireNotNull(wideVideoUri)
                    val ultraVideoUriLocal = requireNotNull(ultraVideoUri)

                    val sensorTimestampSource =
                        logicalChars?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
                    val sensorSyncType =
                        logicalChars?.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE)

                    recordingLogger = StereoRecordingLogger(
                        recordingId = timestampForFilename,
                        logicalCameraId = logicalRearId,
                        widePhysicalId = widePhysicalId,
                        ultraPhysicalId = ultraPhysicalId,
                        recordSize = recordSize,
                        targetFps = requestedRecordFps,
                        videoBitrateBps = videoBitrateBps,
                        eisEnabled = eisEnabled,
                        orientationHintDegrees = orient,
                        sensorTimestampSource = sensorTimestampSource,
                        sensorSyncType = sensorSyncType,
                        wideVideoUri = wideVideoUriLocal,
                        ultraVideoUri = ultraVideoUriLocal,
                        framesOnly = stereoLogFramesOnly
                    )
                }

                // Connect encoders to logger
                if (enableStereoRecordingLog) {
                    val loggerLocal = recordingLogger
                    val wEncLocal = wideEncoder
                    val uEncLocal = ultraEncoder
                    val aEncLocal = audioEncoder

                    if (loggerLocal != null && wEncLocal != null && uEncLocal != null && aEncLocal != null) {
                        // Only run encoder callback hookups in full logging mode.
                        // Frames-only mode ignores muxer/sample events entirely.
                        if (!stereoLogFramesOnly) {
                            wEncLocal.onEncodedSample = { codecPtsUs, muxerPtsUs, sizeBytes, flags, writeDurationNs ->
                                loggerLocal.onWideVideoSample(codecPtsUs, muxerPtsUs, sizeBytes, flags, writeDurationNs)
                            }
                            uEncLocal.onEncodedSample = { codecPtsUs, muxerPtsUs, sizeBytes, flags, writeDurationNs ->
                                loggerLocal.onUltraVideoSample(codecPtsUs, muxerPtsUs, sizeBytes, flags, writeDurationNs)
                            }
                            aEncLocal.onEncodedSample = { codecPtsUs, muxerPtsUs, sizeBytes, flags, writeDurationNs ->
                                loggerLocal.onAudioSample(codecPtsUs, muxerPtsUs, sizeBytes, flags, writeDurationNs)
                            }
                        } else {
                            // frames-only: no-op assignments just to be explicit
                            wEncLocal.onEncodedSample = null
                            uEncLocal.onEncodedSample = null
                            aEncLocal.onEncodedSample = null
                        }
                    }
                } else {
                    // no logging; clear callbacks
                    wideEncoder?.onEncodedSample = null
                    ultraEncoder?.onEncodedSample = null
                    audioEncoder?.onEncodedSample = null
                }

                createRecordingSession()
            } catch (e: Exception) {
                callback.onError("Start recording failed: ${e.message}")
                safeStopRecordingInternal(deleteOnFailure = true)
                isRecording = false
            }
    }

    // ---------------------------------------------------------------------------------------------
    // Direct SBS recording (0.77.0)
    // ---------------------------------------------------------------------------------------------

    /**
     * Per-eye size for direct mode: the record size capped at 1440x1080,
     * aspect preserved, even dimensions (YUV420). CPU composition can't
     * sustain full 1920x1440 at 30fps, so a bigger record size records
     * smaller in direct mode — the two-stream path keeps full size.
     */
    private fun directEyeSize(): Size {
        val w = recordSize.width
        val h = recordSize.height
        val scale = minOf(1440.0 / w, 1080.0 / h, 1.0)
        var ew = (w * scale).toInt()
        var eh = (h * scale).toInt()
        if (ew % 2 != 0) ew -= 1
        if (eh % 2 != 0) eh -= 1
        return Size(ew.coerceAtLeast(320), eh.coerceAtLeast(240))
    }

    /**
     * Start the direct SBS flow. Returns true once the direct record
     * sessions are being created (warm-up + encoding follow asynchronously);
     * false if direct can't start at all (caller falls through to the
     * two-stream path). Assumes isRecording and currentRecordingId are set.
     */
    private fun tryStartDirectRecordingLocked(timestampForFilename: String): Boolean {
        // The direct wide session is preview (PRIVATE) + a YUV reader, which
        // the phone's mandatory concurrent combinations only allow with the
        // preview at <=1280x720 (combo [5]).
        if (previewSize.width > 1280 || previewSize.height > 720) return false
        val eyeSize = directEyeSize()
        val orient = computeOrientationHint()
        return try {
            val sbsUri = MediaStoreUtils.createPendingVideo(
                context, "${timestampForFilename}_sbs.mp4", System.currentTimeMillis()
            )
            directVideoUri = sbsUri
            directRecordingId = timestampForFilename
            directRecorder = DirectSbsRecorder(
                context = context,
                outputUri = sbsUri,
                eyeSize = eyeSize,
                fps = requestedRecordFps,
                bitrateBps = videoBitrateBps * 2,
                orientationHint = orient,
                rotationDegrees = computeJpegOrientation(),
                sbsView = StereoSbs.SbsView.fromSetting(AppSettings.getSbsView(context)),
                colorMatch = StereoVideoStitcher.colorMatchFromSetting(
                    AppSettings.getVideoColorMatch(context)
                ),
                listenerHandler = requireNotNull(cameraHandler)
            )
            isDirectRecording = true
            createDirectRecordingSessions()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Direct SBS setup failed: ${e.message}", e)
            releaseDirectState()
            false
        }
    }

    /**
     * MOTO dual-device direct recording: two sessions, one per camera.
     *  - Wide session: preview + wide YUV reader.
     *  - Ultra session: ultra YUV reader only.
     * Both repeating requests are started back-to-back once both sessions
     * are configured; the warm-up clock starts then, and encoding starts
     * once the settle pair has been estimated (~1s later).
     */
    private fun createDirectRecordingSessions() {
        surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)

        val wideCamera = cameraDevice ?: return
        val ultraCamera = ultraCameraDevice ?: return
        val handler = requireNotNull(cameraHandler)
        val preview = previewSurface ?: return
        val recorder = directRecorder ?: return

        try { session?.close() } catch (_: Exception) {}
        session = null
        repeatingBuilder = null
        try { ultraSession?.close() } catch (_: Exception) {}
        ultraSession = null
        ultraRepeatingBuilder = null
        dualDirectWideReady = false
        dualDirectUltraReady = false

        fun maybeStartDirectWarmup() {
            if (!dualDirectWideReady || !dualDirectUltraReady) return
            val wideSess = session ?: return
            val ultraSess = ultraSession ?: return
            val wideBuilder = repeatingBuilder ?: return
            val ultraBuilder = ultraRepeatingBuilder ?: return
            try {
                // Start wide first, ultra immediately after, on the same handler thread.
                wideSess.setRepeatingRequest(wideBuilder.build(), null, handler)
                ultraSess.setRepeatingRequest(ultraBuilder.build(), null, handler)
            } catch (e: Exception) {
                callback.onError("Direct record repeating request failed: ${e.message}")
                fallbackDirectToStitched()
                return
            }
            recorder.beginWarmup()
            callback.onDirectWarmup()
            ioExecutor.execute {
                // 0.77.2: catch Throwable here too — an Error escaping this
                // pool thread kills the whole app; a failure must fall back
                // to the two-stream path instead.
                try {
                    val ok = recorder.estimateParams()
                    cameraHandler?.post {
                        if (!isDirectRecording) return@post
                        if (ok && recorder.startEncoding()) {
                            callback.onRecordingStarted()
                        } else {
                            fallbackDirectToStitched()
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Direct warm-up failed: ${t.message}", t)
                    cameraHandler?.post {
                        if (isDirectRecording) fallbackDirectToStitched()
                    }
                }
            }
        }

        // --- Wide direct session: preview + wide YUV reader ---
        val wideOutputs = ArrayList<OutputConfiguration>(2).apply {
            add(OutputConfiguration(preview))
            add(OutputConfiguration(recorder.wideReader.surface))
        }
        wideCamera.createCaptureSessionByOutputConfigurations(
            wideOutputs,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    session = sess
                    try {
                        val builder = wideCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(preview)
                            addTarget(recorder.wideReader.surface)
                            applyCommonNoCropNoStab(this, isVideo = true, targetFps = requestedRecordFps)
                            applyZoomCrop(this, wideChars)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            try { set(CaptureRequest.NOISE_REDUCTION_MODE, videoNoiseReductionMode) } catch (_: Exception) {}
                            try { set(CaptureRequest.DISTORTION_CORRECTION_MODE, videoDistortionCorrectionMode) } catch (_: Exception) {}
                            try { set(CaptureRequest.EDGE_MODE, videoEdgeMode) } catch (_: Exception) {}
                            applyTorch(this)
                        }
                        repeatingBuilder = builder
                        dualDirectWideReady = true
                        maybeStartDirectWarmup()
                    } catch (e: Exception) {
                        callback.onError("Wide direct record request failed: ${e.message}")
                        fallbackDirectToStitched()
                    }
                }

                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    callback.onError("Wide direct recording session configure failed (Moto dual-device).")
                    fallbackDirectToStitched()
                }
            },
            handler
        )

        // --- Ultra direct session: ultra YUV reader only ---
        val ultraOutputs = ArrayList<OutputConfiguration>(1).apply {
            add(OutputConfiguration(recorder.ultraReader.surface))
        }
        ultraCamera.createCaptureSessionByOutputConfigurations(
            ultraOutputs,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    ultraSession = sess
                    try {
                        val builder = ultraCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(recorder.ultraReader.surface)
                            applyCommonNoCropNoStab(this, isVideo = true, targetFps = requestedRecordFps, forUltraDevice = true)
                            applyZoomCrop(this, ultraChars)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            try { set(CaptureRequest.NOISE_REDUCTION_MODE, videoNoiseReductionMode) } catch (_: Exception) {}
                            try { set(CaptureRequest.DISTORTION_CORRECTION_MODE, videoDistortionCorrectionMode) } catch (_: Exception) {}
                            try { set(CaptureRequest.EDGE_MODE, videoEdgeMode) } catch (_: Exception) {}
                            // No torch on the ultra builder (shared flash, driven by wide).
                        }
                        ultraRepeatingBuilder = builder
                        dualDirectUltraReady = true
                        maybeStartDirectWarmup()
                    } catch (e: Exception) {
                        callback.onError("Ultra direct record request failed: ${e.message}")
                        fallbackDirectToStitched()
                    }
                }

                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    callback.onError("Ultra direct recording session configure failed (Moto dual-device).")
                    fallbackDirectToStitched()
                }
            },
            handler
        )
    }

    /**
     * Direct setup failed mid-flight (session configure / warm-up /
     * encoder start): release direct state and run the two-stream path
     * with the same recording id. Must run on the camera handler thread.
     * Safe to call when already stopped — it no-ops then.
     */
    private fun fallbackDirectToStitched() {
        if (!isDirectRecording) return
        releaseDirectState()
        callback.onStatus("Direct SBS couldn't start — using the two-stream mode")
        val id = currentRecordingId ?: return
        startStitchedRecordingLocked(id)
    }

    /**
     * Release direct sessions + recorder (deleting the partial SBS file).
     * Keeps isRecording and currentRecordingId — the fallback needs them.
     */
    private fun releaseDirectState() {
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { ultraSession?.stopRepeating() } catch (_: Exception) {}
        try { ultraSession?.abortCaptures() } catch (_: Exception) {}
        try { ultraSession?.close() } catch (_: Exception) {}
        session = null
        repeatingBuilder = null
        ultraSession = null
        ultraRepeatingBuilder = null
        dualDirectWideReady = false
        dualDirectUltraReady = false
        recordCaptureCallback = null
        val recorder = directRecorder
        directRecorder = null
        directVideoUri = null
        directRecordingId = null
        isDirectRecording = false
        try { recorder?.cancelWarmup() } catch (_: Exception) {}
        try { recorder?.releaseOnFailure() } catch (_: Exception) {}
    }

    /**
     * Stop a direct recording: finalize the single SBS file on a background
     * thread, restore the preview session, report the frame count.
     */
    private fun stopDirectRecordingLocked() {
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { ultraSession?.stopRepeating() } catch (_: Exception) {}
        try { ultraSession?.abortCaptures() } catch (_: Exception) {}
        try { ultraSession?.close() } catch (_: Exception) {}
        session = null
        repeatingBuilder = null
        ultraSession = null
        ultraRepeatingBuilder = null
        dualDirectWideReady = false
        dualDirectUltraReady = false
        recordCaptureCallback = null

        val recorder = directRecorder
        directRecorder = null
        directVideoUri = null
        directRecordingId = null
        isDirectRecording = false
        isRecording = false

        encodingExecutor.execute {
            val frames = try {
                recorder?.cancelWarmup()
                recorder?.stopAndFinalize() ?: 0
            } catch (e: Exception) {
                Log.w(TAG, "Direct SBS finalize failed: ${e.message}", e)
                0
            }
            if (frames > 0) {
                callback.onStatus("3D video saved ($frames frames)")
            } else {
                callback.onStatus("Direct recording saved no frames — check the video")
            }
            cameraHandler?.post {
                if (cameraDevice != null && previewSurface != null) {
                    createPreviewSession()
                }
                callback.onRecordingStopped()
            }
        }
    }

    fun stopRecording() {

        val handler = cameraHandler ?: run {
            callback.onError("Camera thread not running.")
            return
        }

        handler.post {
            if (!isRecording) {
                callback.onRecordingStopped()
                return@post
            }

            // 0.77.0: direct SBS mode has its own stop path (single file, no stitch).
            if (isDirectRecording) {
                stopDirectRecordingLocked()
                return@post
            }

            // Stop camera outputs first.
            try { session?.stopRepeating() } catch (_: Exception) {}
            try { session?.abortCaptures() } catch (_: Exception) {}
            try { session?.close() } catch (_: Exception) {}
            // Dual-device: stop the ultrawide session too.
            try { ultraSession?.stopRepeating() } catch (_: Exception) {}
            try { ultraSession?.abortCaptures() } catch (_: Exception) {}
            try { ultraSession?.close() } catch (_: Exception) {}

            session = null
            repeatingBuilder = null
            ultraSession = null
            ultraRepeatingBuilder = null
            recordCaptureCallback = null

            val resolver = context.contentResolver

            // Move refs locally for the async stop/release path.
            val wideUriLocal = wideVideoUri
            val ultraUriLocal = ultraVideoUri
            val recordingIdLocal = currentRecordingId
            val stitchBitrateBps = videoBitrateBps * 2

            val wEnc = wideEncoder
            val uEnc = ultraEncoder
            val aEnc = audioEncoder

            val wMux = wideMuxer
            val uMux = ultraMuxer

            wideVideoUri = null
            ultraVideoUri = null
            currentRecordingId = null
            wideEncoder = null
            ultraEncoder = null
            audioEncoder = null
            wideMuxer = null
            ultraMuxer = null

            isRecording = false

            // stop logging and write/finalize
            recordingLogger?.markStopped()

            val logUriLocal = recordingLogUri
            val loggerLocal = recordingLogger

            encodingExecutor.execute {
                try { wEnc?.signalEndOfInputStream() } catch (_: Exception) {}
                try { uEnc?.signalEndOfInputStream() } catch (_: Exception) {}

                // Stop audio (queues EOS + drains)
                try { aEnc?.stopAndRelease() } catch (_: Exception) {}

                // Stop video encoders (drain + release)
                try { wEnc?.stopAndRelease() } catch (_: Exception) {}
                try { uEnc?.stopAndRelease() } catch (_: Exception) {}

                // Stop muxers last
                try { wMux?.stopAndRelease() } catch (_: Exception) {}
                try { uMux?.stopAndRelease() } catch (_: Exception) {}

                // Make files visible in MediaStore
                if (wideUriLocal != null) MediaStoreUtils.finalizePending(resolver, wideUriLocal)
                if (ultraUriLocal != null) MediaStoreUtils.finalizePending(resolver, ultraUriLocal)

                // Stitched SBS video (background; the two separate MP4s are
                // already saved, so a stitch failure costs nothing). Colors
                // default to the wide eye (warmer); see Settings > Video.
                if (AppSettings.getVideoStitchSbsEnabled(context) &&
                    wideUriLocal != null && ultraUriLocal != null && recordingIdLocal != null
                ) {
                    val stitchWideUri = wideUriLocal
                    val stitchUltraUri = ultraUriLocal
                    val stitchRecordId = recordingIdLocal
                    val stitchMatch =
                        StereoVideoStitcher.colorMatchFromSetting(AppSettings.getVideoColorMatch(context))
                    ioExecutor.execute {
                        try {
                            val result = StereoVideoStitcher.stitch(
                                context = context,
                                wideUri = stitchWideUri,
                                ultraUri = stitchUltraUri,
                                outputName = "${stitchRecordId}_sbs.mp4",
                                timestampMillis = System.currentTimeMillis(),
                                colorMatch = stitchMatch,
                                bitrateBps = stitchBitrateBps,
                                sbsView = StereoSbs.SbsView.fromSetting(
                                    AppSettings.getSbsView(context)
                                ),
                                onProgress = { stage, done, total ->
                                    callback.onStitchProgress(stage, done, total)
                                }
                            )
                            if (result != null) {
                                callback.onStitchDone(true, result.frames)
                                callback.onStatus("SBS video saved (${result.frames} frames)")
                            } else {
                                Log.w(TAG, "SBS video stitching failed; separate videos kept")
                                callback.onStitchDone(false, 0)
                                callback.onStatus("SBS video couldn't be stitched — the two separate videos are saved")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "SBS video stitching failed: ${e.message}", e)
                            callback.onStitchDone(false, 0)
                            callback.onStatus("SBS video couldn't be stitched — the two separate videos are saved")
                        }
                    }
                }

                // Write + finalize the stereo JSON log
                if (loggerLocal != null && logUriLocal != null) {
                    try {
                        loggerLocal.writeToUri(context, logUriLocal)
                        MediaStoreUtils.finalizePending(resolver, logUriLocal)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed writing/finalizing JSON log: ${e.message}", e)
                        MediaStoreUtils.delete(resolver, logUriLocal)
                    }
                }
                // Clear logger refs after we are fully done
                recordingLogger = null
                recordingLogUri = null

                cameraHandler?.post {
                    if (cameraDevice != null && previewSurface != null) {
                        createPreviewSession()
                    }
                    callback.onRecordingStopped()
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Camera open / session management
    // ---------------------------------------------------------------------------------------------

    private fun startThreads() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("CameraThread").also { it.start() }
        cameraHandler = Handler(requireNotNull(cameraThread).looper)
    }

    private fun stopThreads() {
        cameraThread?.quitSafely()
        try { cameraThread?.join() } catch (_: Exception) {}
        cameraThread = null
        cameraHandler = null
    }

    private fun selectCharacteristicsAndSizes() {

        // Discover the rear logical camera + physical IDs (Pixel + Samsung),
        // or the hidden dual-device rig (Moto, no logical camera).
        val rig = LensDiscovery.discoverStereoRig(cameraManager)

        if (rig != null && rig.isDualDevice) {
            selectDualCharacteristicsAndSizes(rig)
            return
        }

        if (rig != null) {
            logicalRearId = rig.logicalRearId
            wide1xPhysicalId = rig.wide1xId
            ultraPhysicalId = rig.ultraId
            wide2xPhysicalId = rig.wide2xId
            phoneIsAPixel = rig.phoneIsAPixel

            // Override rotation behavior using the simple heuristic:
            // Pixel => portrait (ROTATION_0), Samsung => landscape (ROTATION_90)
            displayRotation = rig.assumedDisplayRotation
        } else {
            // Fallback to the prior Pixel hardcodes if discovery fails.
            logicalRearId = LOGICAL_REAR_ID
            wide1xPhysicalId = WIDE_1X_PHYSICAL_ID
            ultraPhysicalId = ULTRA_PHYSICAL_ID
            wide2xPhysicalId = null
            phoneIsAPixel = true
            displayRotation = Surface.ROTATION_0
        }

        logicalChars = cameraManager.getCameraCharacteristics(logicalRearId)

        val logical = requireNotNull(logicalChars)

        // Try to derive "wide inside ultrawide" scale from logical zoom-out capability.
        // On multi-camera devices, logical zoom range often includes < 1.0 for ultrawide.
        val zoomRange = logical.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        ultra3aFraction = if (zoomRange != null && zoomRange.lower < 1.0f) {
            // Slightly conservative so the region stays inside the true overlap.
            // Clamp to avoid weird vendor ranges.
            // (zoomRange.lower * 0.95f).coerceIn(0.45f, 0.90f)
            // less conservative
            zoomRange.lower.coerceIn(0.45f, 0.90f)
        } else {
            ULTRA_3A_FRACTION_DEFAULT
        }
        Log.i(TAG, "Ultrawide 3A fraction = $ultra3aFraction (logical zoomRange=$zoomRange)")

        val caps = logical.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
        if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
            callback.onError("Rear camera is not LOGICAL_MULTI_CAMERA. This app requires a logical multi-camera (wide+ultrawide).")
            return
        }

        val physicalIds = logical.physicalCameraIds
        if (!physicalIds.contains(wide1xPhysicalId) || !physicalIds.contains(ultraPhysicalId)) {
            callback.onError(
                "Expected physical IDs $wide1xPhysicalId (wide) and $ultraPhysicalId (ultrawide) not found under logical $logicalRearId. Found: $physicalIds"
            )
            return
        }

        // Decide which physical ID (if any) represents wide 2x crop.
        val wide2xId = wide2xPhysicalId ?: resolveWide2xPhysicalId(physicalIds)
        wide2xPhysicalId = wide2xId // cache it for setZoom2x()
        hasWide2xPhysical = wide2xId != null

        if (zoom2xEnabled && wide2xId == null) {
            zoom2xEnabled = false
            callback.onStatus("2x mode not available on this device; falling back to 1x.")
        }

        // Pick active wide physical camera for current zoom mode.
        widePhysicalId = if (zoom2xEnabled && wide2xId != null) wide2xId else wide1xPhysicalId

        wideChars = cameraManager.getCameraCharacteristics(widePhysicalId)
        ultraChars = cameraManager.getCameraCharacteristics(ultraPhysicalId)

        val wide = requireNotNull(wideChars)
        val ultra = requireNotNull(ultraChars)

        sensorOrientation = wide.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        wideMap = wide.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ultraMap = ultra.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        // Ensure the stream configuration maps exist. The app cannot operate without them.
        requireNotNull(wideMap)
        requireNotNull(ultraMap)

        recomputeRecordSizeLocked()
        recomputePreviewSizeLocked()

        recomputeStillSizesLocked()

    }

    /**
     * MOTO dual-device setup (no logical camera). The two rig IDs are standalone
     * camera IDs opened as two separate CameraDevices. logicalChars is set to the
     * WIDE characteristics so code that reads the timestamp source from it keeps
     * working; the LOGICAL_MULTI_CAMERA capability check is skipped entirely.
     *
     * Size policy: everything is capped at the mandatory CONCURRENT limit
     * (1920x1440) — see recomputeStillSizesLocked()/recomputeRecordSizeLocked().
     * 2x does not exist on the Moto: wide2x is always null.
     */
    private fun selectDualCharacteristicsAndSizes(rig: LensDiscovery.StereoRig) {
        isDualDevice = true
        logicalRearId = rig.wide1xId
        wide1xPhysicalId = rig.wide1xId
        ultraPhysicalId = rig.ultraId
        wide2xPhysicalId = null
        hasWide2xPhysical = false
        phoneIsAPixel = false
        // Lenses are stacked vertically on the back: landscape, like Samsung.
        // displayRotation is NOT forced here anymore (0.76.5): the activity
        // runs in sensorLandscape and passes the real rotation (90 or 270),
        // which is what the JPEG/MP4 hints and per-frame rotation use.
        // Zoom is crop zoom now, so a persisted 2x stays on.
        widePhysicalId = wide1xPhysicalId

        wideChars = cameraManager.getCameraCharacteristics(widePhysicalId)
        ultraChars = cameraManager.getCameraCharacteristics(ultraPhysicalId)
        // Stand-in for "logical" characteristics (timestamp source, etc.).
        logicalChars = wideChars

        val wide = requireNotNull(wideChars)
        val ultra = requireNotNull(ultraChars)

        sensorOrientation = wide.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        // Ultrawide 3A fraction: how much of the ultrawide frame the wide view covers.
        // scale = (f_wide / f_ultra) * (sensorW_ultra / sensorW_wide); fraction = 1/scale.
        // Moto G Stylus 2026: (5.56/2.13) * (4.6592/8.224) ≈ 1.48 → fraction ≈ 0.68.
        // This matches the stock fallback (0.66) and the FOV ratio
        // tan(73°/2)/tan(95.1°/2) ≈ 0.68, so the existing SBS alignment scale (1/0.66
        // ≈ 1.51x) already matches this phone — no aligner change needed.
        ultra3aFraction = try {
            val wFocal = rig.wideFocalMm ?: 5.56f
            val uFocal = rig.ultraFocalMm ?: 2.13f
            val wSize = wide.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val uSize = ultra.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (wFocal > 0f && uFocal > 0f && wSize != null && uSize != null &&
                wSize.width > 0f && uSize.width > 0f
            ) {
                val scale = (wFocal / uFocal) * (uSize.width / wSize.width)
                (1.0f / scale).coerceIn(0.45f, 0.90f)
            } else {
                ULTRA_3A_FRACTION_DEFAULT
            }
        } catch (_: Exception) {
            ULTRA_3A_FRACTION_DEFAULT
        }
        Log.i(TAG, "Moto dual-device rig: wide=$widePhysicalId ultra=$ultraPhysicalId " +
            "ultra3aFraction=$ultra3aFraction sensorOrientation=$sensorOrientation")

        wideMap = wide.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ultraMap = ultra.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        requireNotNull(wideMap)
        requireNotNull(ultraMap)

        recomputeRecordSizeLocked()
        recomputePreviewSizeLocked()
        recomputeStillSizesLocked()
    }

    private fun openCamera() {
        val handler = requireNotNull(cameraHandler)
        if (isDualDevice) {
            openDualCameras(handler)
            return
        }
        try {
            cameraManager.openCamera(logicalRearId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    try { camera.close() } catch (_: Exception) {}
                    cameraDevice = null
                    callback.onError("Camera disconnected.")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    try { camera.close() } catch (_: Exception) {}
                    cameraDevice = null
                    callback.onError("Camera error: $error")
                }
            }, handler)
        } catch (se: SecurityException) {
            callback.onError("Missing CAMERA permission.")
        } catch (e: Exception) {
            callback.onError("openCamera failed: ${e.message}")
        }
    }

    /**
     * MOTO dual-device open: wide first, then ultrawide. The two cameras are
     * independent HAL pipelines — if the second open disconnects the first (some
     * phones refuse concurrent opens despite the direct-open test passing), the
     * error surfaces here and the user gets a clear message instead of a hang.
     * Preview sessions are created only once BOTH devices are open.
     */
    private fun openDualCameras(handler: Handler) {
        try {
            dualUltraOpening = false
            cameraManager.openCamera(widePhysicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    Log.i(TAG, "Moto dual: wide camera $widePhysicalId opened, opening ultra $ultraPhysicalId")
                    dualUltraOpening = true
                    try {
                        cameraManager.openCamera(ultraPhysicalId, object : CameraDevice.StateCallback() {
                            override fun onOpened(ultraCamera: CameraDevice) {
                                dualUltraOpening = false
                                ultraCameraDevice = ultraCamera
                                Log.i(TAG, "Moto dual: ultra camera $ultraPhysicalId opened")
                                createPreviewSession()
                            }

                            override fun onDisconnected(ultraCamera: CameraDevice) {
                                dualUltraOpening = false
                                try { ultraCamera.close() } catch (_: Exception) {}
                                ultraCameraDevice = null
                                callback.onError("Ultrawide camera disconnected (Moto dual-device).")
                            }

                            override fun onError(ultraCamera: CameraDevice, error: Int) {
                                dualUltraOpening = false
                                try { ultraCamera.close() } catch (_: Exception) {}
                                ultraCameraDevice = null
                                callback.onError("Ultrawide camera error: $error (Moto dual-device).")
                            }
                        }, handler)
                    } catch (e: Exception) {
                        dualUltraOpening = false
                        callback.onError("Opening ultrawide camera failed: ${e.message}")
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    try { camera.close() } catch (_: Exception) {}
                    cameraDevice = null
                    callback.onError("Wide camera disconnected (Moto dual-device).")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    try { camera.close() } catch (_: Exception) {}
                    cameraDevice = null
                    callback.onError("Wide camera error: $error (Moto dual-device).")
                }
            }, handler)
        } catch (se: SecurityException) {
            callback.onError("Missing CAMERA permission.")
        } catch (e: Exception) {
            callback.onError("openCamera failed: ${e.message}")
        }
    }

    private fun createPreviewSession() {

        // make sure preview aspect doesn't get messed up
        surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)

        val camera = cameraDevice ?: return
        val handler = requireNotNull(cameraHandler)
        val preview = previewSurface ?: return

        if (isDualDevice) {
            createDualPreviewSessions()
            return
        }

        try { session?.close() } catch (_: Exception) {}
        session = null
        repeatingBuilder = null
        recordCaptureCallback = null

        cancelPrimeUwLocked()

        closeReaders()

        val raw = if (!phoneIsAPixel && !isDualDevice && isRawMode) {
            callback.onStatus("RAW/DNG disabled on Samsung for now (YUV only).")
            isRawMode = false
            false
        } else {
            isRawMode
        }

        val (format, wideSize, ultraSize) = if (raw) {
            val w = rawWideSize
            val u = rawUltraSize
            if (w != null && u != null) {
                Triple(ImageFormat.RAW_SENSOR, w, u)
            } else {
                callback.onStatus("RAW not supported; using YUV.")
                isRawMode = false
                Triple(ImageFormat.YUV_420_888, yuvWideSize, yuvUltraSize)
            }
        } else {
            Triple(ImageFormat.YUV_420_888, yuvWideSize, yuvUltraSize)
        }

        val maxImages = if (format == ImageFormat.RAW_SENSOR) RAW_MAX_IMAGES else YUV_MAX_IMAGES

        wideReader = ImageReader.newInstance(wideSize.width, wideSize.height, format, maxImages).apply {
            setOnImageAvailableListener({ reader -> onStillImageAvailable(isWide = true, reader = reader) }, handler)
        }
        ultraReader = ImageReader.newInstance(ultraSize.width, ultraSize.height, format, maxImages).apply {
            setOnImageAvailableListener({ reader -> onStillImageAvailable(isWide = false, reader = reader) }, handler)
        }

        val outputs = ArrayList<OutputConfiguration>(3).apply {
            add(OutputConfiguration(preview).apply { setPhysicalCameraId(widePhysicalId) })
            add(OutputConfiguration(requireNotNull(wideReader).surface).apply { setPhysicalCameraId(widePhysicalId) })
            add(OutputConfiguration(requireNotNull(ultraReader).surface).apply { setPhysicalCameraId(ultraPhysicalId) })
        }

        camera.createCaptureSessionByOutputConfigurations(
            outputs,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    session = sess
                    try {
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

                            applyCommonNoCropNoStab(
                                this,
                                isVideo = false,
                                targetFps = previewTargetFps,
                                applyFpsRange = false
                            )

                            // Preview post-processing forced OFF (performance + reduce ISP variability).
                            try { set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF) } catch (_: Exception) {}
                            try { set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF) } catch (_: Exception) {}
                            try { set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF) } catch (_: Exception) {}

                            // (Physical keys are optional; set wide as best-effort.)
                            setPhysical(this, widePhysicalId, CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                            setPhysical(this, widePhysicalId, CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
                            setPhysical(this, widePhysicalId, CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)

                            applyTorch(this)
                        }
                        repeatingBuilder = builder
                        sess.setRepeatingRequest(builder.build(), null, handler)
                        // One-time ultrawide priming (optional)
                        if (primeUwOnActiveEnabled) {
                            primeUwPending = true
                            handler.removeCallbacks(primeUwRunnable)
                            handler.post(primeUwRunnable)
                        }
                    } catch (e: Exception) {
                        callback.onError("Preview repeating request failed: ${e.message}")
                        Log.e(TAG, "Preview repeating request failed: ${e.message}", e)
                    }
                }

                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    callback.onError("Preview session configure failed.")
                }
            },
            handler
        )
    }


    /**
     * MOTO dual-device preview: two independent sessions.
     *  - Wide session: preview Surface + wide ImageReader. Repeating request is the
     *    normal preview (preview target only).
     *  - Ultra session: ultra ImageReader only. Its repeating request targets the
     *    reader surface so the ultrawide keeps running continuous AF/AE while idle
     *    (otherwise the first still capture would be slow / out of focus). Frames
     *    arriving with no photo in flight are discarded by onStillImageAvailable().
     * No setPhysicalCameraId() anywhere — these are standalone devices.
     */
    private fun createDualPreviewSessions() {
        val wideCamera = cameraDevice ?: return
        val ultraCamera = ultraCameraDevice ?: return
        val handler = requireNotNull(cameraHandler)
        val preview = previewSurface ?: return

        try { session?.close() } catch (_: Exception) {}
        session = null
        repeatingBuilder = null
        recordCaptureCallback = null
        try { ultraSession?.close() } catch (_: Exception) {}
        ultraSession = null
        ultraRepeatingBuilder = null

        cancelPrimeUwLocked()
        closeReaders()

        val raw = isRawMode
        val (format, wideSize, ultraSize) = if (raw) {
            val w = rawWideSize
            val u = rawUltraSize
            if (w != null && u != null) {
                Triple(ImageFormat.RAW_SENSOR, w, u)
            } else {
                callback.onStatus("RAW not supported; using YUV.")
                isRawMode = false
                Triple(ImageFormat.YUV_420_888, yuvWideSize, yuvUltraSize)
            }
        } else {
            Triple(ImageFormat.YUV_420_888, yuvWideSize, yuvUltraSize)
        }

        val maxImages = if (format == ImageFormat.RAW_SENSOR) RAW_MAX_IMAGES else YUV_MAX_IMAGES

        wideReader = ImageReader.newInstance(wideSize.width, wideSize.height, format, maxImages).apply {
            setOnImageAvailableListener({ reader -> onStillImageAvailable(isWide = true, reader = reader) }, handler)
        }
        ultraReader = ImageReader.newInstance(ultraSize.width, ultraSize.height, format, maxImages).apply {
            setOnImageAvailableListener({ reader -> onStillImageAvailable(isWide = false, reader = reader) }, handler)
        }

        // --- Wide session: preview + wide still reader ---
        val wideOutputs = ArrayList<OutputConfiguration>(2).apply {
            add(OutputConfiguration(preview))
            add(OutputConfiguration(requireNotNull(wideReader).surface))
        }
        wideCamera.createCaptureSessionByOutputConfigurations(
            wideOutputs,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    session = sess
                    try {
                        val builder = wideCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            applyCommonNoCropNoStab(this, isVideo = false, targetFps = previewTargetFps, applyFpsRange = false)
                            applyZoomCrop(this, wideChars)
                            try { set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF) } catch (_: Exception) {}
                            try { set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF) } catch (_: Exception) {}
                            try { set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF) } catch (_: Exception) {}
                            applyTorch(this)
                        }
                        repeatingBuilder = builder
                        sess.setRepeatingRequest(builder.build(), null, handler)
                        if (primeUwOnActiveEnabled) {
                            primeUwPending = true
                            handler.removeCallbacks(primeUwRunnable)
                            handler.post(primeUwRunnable)
                        }
                    } catch (e: Exception) {
                        callback.onError("Wide preview repeating request failed: ${e.message}")
                        Log.e(TAG, "Wide preview repeating request failed: ${e.message}", e)
                    }
                }

                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    callback.onError("Wide preview session configure failed (Moto dual-device).")
                }
            },
            handler
        )

        // --- Ultra session: ultra still reader only ---
        val ultraOutputs = ArrayList<OutputConfiguration>(1).apply {
            add(OutputConfiguration(requireNotNull(ultraReader).surface))
        }
        ultraCamera.createCaptureSessionByOutputConfigurations(
            ultraOutputs,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    ultraSession = sess
                    try {
                        val ultraSurface = requireNotNull(ultraReader).surface
                        val builder = ultraCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(ultraSurface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            applyCommonNoCropNoStab(this, isVideo = false, targetFps = previewTargetFps, applyFpsRange = false, forUltraDevice = true)
                            applyZoomCrop(this, ultraChars)
                            try { set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF) } catch (_: Exception) {}
                            try { set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF) } catch (_: Exception) {}
                            try { set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF) } catch (_: Exception) {}
                            // No torch on the ultra builder: the flash is shared with the
                            // wide camera and is already driven from the wide session.
                        }
                        ultraRepeatingBuilder = builder
                        sess.setRepeatingRequest(builder.build(), null, handler)
                    } catch (e: Exception) {
                        callback.onError("Ultra preview repeating request failed: ${e.message}")
                        Log.e(TAG, "Ultra preview repeating request failed: ${e.message}", e)
                    }
                }

                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    callback.onError("Ultra preview session configure failed (Moto dual-device).")
                }
            },
            handler
        )
    }


    // ---------------------------------------------------------------------------------------------
    // Ultrawide priming (optional)
    // ---------------------------------------------------------------------------------------------

    private fun cancelPrimeUwLocked() {
        val handler = cameraHandler ?: return
        handler.removeCallbacks(primeUwRunnable)
        primeUwPending = false
        primeUwInFlight = false
    }

    private fun runPrimeUwIfNeeded() {
        val handler = cameraHandler ?: return
        handler.removeCallbacks(primeUwRunnable)

        if (!primeUwOnActiveEnabled) {
            primeUwPending = false
            primeUwInFlight = false
            return
        }

        if (!primeUwPending) return
        if (isRecording) {
            // Recording sessions do not include the still outputs; we'll prime next time the preview session starts.
            return
        }

        if (cameraDevice == null || session == null) {
            handler.postDelayed(primeUwRunnable, 200L)
            return
        }
        // Dual-device: the prime capture runs on the ultra device/session.
        if (isDualDevice && (ultraCameraDevice == null || ultraSession == null)) {
            handler.postDelayed(primeUwRunnable, 200L)
            return
        }

        // Don't interfere with real still capture.
        if (currentPhoto != null || primeUwInFlight) {
            handler.postDelayed(primeUwRunnable, 200L)
            return
        }

        primeUwPending = false
        doUltrawidePrimeCaptureLocked()
    }

    private fun doUltrawidePrimeCaptureLocked() {
        // Dual-device: prime on the standalone ultra camera/session; logical mode
        // primes through the logical camera/session with the ultra reader as target.
        val camera = if (isDualDevice) ultraCameraDevice ?: return else cameraDevice ?: return
        val sess = if (isDualDevice) ultraSession ?: return else session ?: return
        val handler = cameraHandler ?: return

        // Target the existing ultrawide still reader. Image is discarded because currentPhoto == null.
        val targetSurface = ultraReader?.surface ?: return

        try {
            primeUwInFlight = true

            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(targetSurface)

                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                // Try to avoid a visible WB "pulse" in the main preview when priming runs.
                // (On some devices, AWB is shared across the logical camera.)
                try { set(CaptureRequest.CONTROL_AWB_LOCK, true) } catch (_: Exception) {}

                // Keep this as close to a still capture as possible, but don't constrain FPS here.
                applyCommonNoCropNoStab(this, isVideo = false, targetFps = previewTargetFps, applyFpsRange = false)

                // Best-effort: avoid HDR scene modes.
                set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

                applyTorch(this)
            }

            sess.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                        primeUwInFlight = false
                    }

                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        primeUwInFlight = false
                        Log.w(TAG, "Ultrawide prime capture failed: $failure")
                    }

                    override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                        primeUwInFlight = false
                    }
                },
                handler
            )
        } catch (e: Exception) {
            primeUwInFlight = false
            Log.w(TAG, "Ultrawide prime capture exception: ${e.message}", e)
        }
    }


    private fun createRecordingSession() {

        // make sure preview aspect doesn't get messed up
        surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)

        if (isDualDevice) {
            createDualRecordingSessions()
            return
        }

        val camera = cameraDevice ?: return
        val handler = requireNotNull(cameraHandler)
        val preview = previewSurface ?: return
        val wideEnc = wideEncoder ?: return
        val ultraEnc = ultraEncoder ?: return

        try { session?.close() } catch (_: Exception) {}
        session = null
        repeatingBuilder = null

        val outputs = ArrayList<OutputConfiguration>(3).apply {
            add(OutputConfiguration(preview).apply { setPhysicalCameraId(widePhysicalId) })
            add(OutputConfiguration(wideEnc.inputSurface).apply { setPhysicalCameraId(widePhysicalId) })
            add(OutputConfiguration(ultraEnc.inputSurface).apply { setPhysicalCameraId(ultraPhysicalId) })
        }

        camera.createCaptureSessionByOutputConfigurations(
            outputs,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    session = sess
                    try {
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(preview)
                            addTarget(wideEnc.inputSurface)
                            addTarget(ultraEnc.inputSurface)

                            applyCommonNoCropNoStab(this, isVideo = true, targetFps = requestedRecordFps)

                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

                            // Video post-processing (settings; default: NR OFF, Edge OFF, Distortion FAST)
                            try { set(CaptureRequest.NOISE_REDUCTION_MODE, videoNoiseReductionMode) } catch (_: Exception) {}
                            try { set(CaptureRequest.DISTORTION_CORRECTION_MODE, videoDistortionCorrectionMode) } catch (_: Exception) {}
                            try { set(CaptureRequest.EDGE_MODE, videoEdgeMode) } catch (_: Exception) {}

                            setPhysical(this, widePhysicalId, CaptureRequest.NOISE_REDUCTION_MODE, videoNoiseReductionMode)
                            setPhysical(this, widePhysicalId, CaptureRequest.DISTORTION_CORRECTION_MODE, videoDistortionCorrectionMode)
                            setPhysical(this, widePhysicalId, CaptureRequest.EDGE_MODE, videoEdgeMode)

                            setPhysical(this, ultraPhysicalId, CaptureRequest.NOISE_REDUCTION_MODE, videoNoiseReductionMode)
                            setPhysical(this, ultraPhysicalId, CaptureRequest.DISTORTION_CORRECTION_MODE, videoDistortionCorrectionMode)
                            setPhysical(this, ultraPhysicalId, CaptureRequest.EDGE_MODE, videoEdgeMode)

                            applyTorch(this)
                        }

                        // NEW: for logging
                        repeatingBuilder = builder

                        val captureCallback: CameraCaptureSession.CaptureCallback? =
                            if (enableStereoRecordingLog) {

                                val wideEncSurface = wideEnc.inputSurface
                                val ultraEncSurface = ultraEnc.inputSurface
                                val previewSurfaceLocal = preview

                                object : CameraCaptureSession.CaptureCallback() {

                                    override fun onCaptureCompleted(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult
                                    ) {
                                        val logger = recordingLogger ?: return
                                        val frameNo = result.frameNumber

                                        val phys = result.physicalCameraResults
                                        if (phys.isNotEmpty()) {
                                            phys[widePhysicalId]?.let {
                                                logger.onPhysicalCaptureResult(widePhysicalId, frameNo, it)
                                            }
                                            phys[ultraPhysicalId]?.let {
                                                logger.onPhysicalCaptureResult(ultraPhysicalId, frameNo, it)
                                            }
                                        } else {
                                            // Fallback: at least log the logical result under "wide"
                                            logger.onPhysicalCaptureResult(widePhysicalId, frameNo, result)
                                        }
                                    }

                                    override fun onCaptureBufferLost(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        target: Surface,
                                        frameNumber: Long
                                    ) {
                                        val logger = recordingLogger ?: return
                                        when {
                                            target === wideEncSurface ->
                                                logger.onBufferLost(widePhysicalId, "wideEncoder", frameNumber)

                                            target === ultraEncSurface ->
                                                logger.onBufferLost(ultraPhysicalId, "ultraEncoder", frameNumber)

                                            target === previewSurfaceLocal ->
                                                logger.onBufferLost(widePhysicalId, "preview", frameNumber)

                                            else ->
                                                logger.onBufferLost("unknown", "unknown", frameNumber)
                                        }
                                    }
                                }
                            } else {
                                null
                            }

                        // Store it so torch toggles can keep using it while recording.
                        recordCaptureCallback = captureCallback

                        sess.setRepeatingRequest(builder.build(), captureCallback, handler)
                        // end NEW for logging

                        // Start mic capture as close to the start of video frames as possible.
                        try {
                            audioEncoder?.startCapturing()
                        } catch (e: Exception) {
                            callback.onError("Audio start failed: ${e.message}")
                        }

                        callback.onRecordingStarted()
                    } catch (e: Exception) {
                        callback.onError("Record repeating request failed: ${e.message}")
                        safeStopRecordingInternal(deleteOnFailure = true)
                        isRecording = false
                    }
                }

                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    callback.onError("Recording session configure failed.")
                    safeStopRecordingInternal(deleteOnFailure = true)
                    isRecording = false
                }
            },
            handler
        )
    }

    /**
     * MOTO dual-device recording: two independent recording sessions, one per camera.
     *  - Wide session: preview + wide encoder surface.
     *  - Ultra session: ultra encoder surface only.
     * Both repeating requests are started back-to-back once both sessions are
     * configured, so the two streams start as close together as the HAL allows.
     * There is no hardware frame sync between the two pipelines — the per-frame
     * sensor timestamps in the optional JSON log are how you measure the drift.
     * Audio capture starts after both repeating requests are running.
     */
    private var dualRecordWideReady: Boolean = false
    private var dualRecordUltraReady: Boolean = false

    private fun createDualRecordingSessions() {
        surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)

        val wideCamera = cameraDevice ?: return
        val ultraCamera = ultraCameraDevice ?: return
        val handler = requireNotNull(cameraHandler)
        val preview = previewSurface ?: return
        val wideEnc = wideEncoder ?: return
        val ultraEnc = ultraEncoder ?: return

        try { session?.close() } catch (_: Exception) {}
        session = null
        repeatingBuilder = null
        try { ultraSession?.close() } catch (_: Exception) {}
        ultraSession = null
        ultraRepeatingBuilder = null
        dualRecordWideReady = false
        dualRecordUltraReady = false

        fun recordingCallbackFor(isWide: Boolean): CameraCaptureSession.CaptureCallback? {
            if (!enableStereoRecordingLog) return null
            val physicalId = if (isWide) widePhysicalId else ultraPhysicalId
            val encSurface = if (isWide) wideEnc.inputSurface else ultraEnc.inputSurface
            val label = if (isWide) "wideEncoder" else "ultraEncoder"
            return object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    recordingLogger?.onPhysicalCaptureResult(physicalId, result.frameNumber, result)
                }

                override fun onCaptureBufferLost(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    target: Surface,
                    frameNumber: Long
                ) {
                    recordingLogger?.onBufferLost(
                        physicalId, if (target === encSurface) label else "unknown", frameNumber
                    )
                }
            }
        }

        fun maybeStartDualRecording() {
            if (!dualRecordWideReady || !dualRecordUltraReady) return
            val wideSess = session ?: return
            val ultraSess = ultraSession ?: return
            val wideBuilder = repeatingBuilder ?: return
            val ultraBuilder = ultraRepeatingBuilder ?: return
            try {
                val wideCb = recordingCallbackFor(isWide = true)
                val ultraCb = recordingCallbackFor(isWide = false)
                recordCaptureCallback = wideCb
                // Start wide first, ultra immediately after, on the same handler thread.
                wideSess.setRepeatingRequest(wideBuilder.build(), wideCb, handler)
                ultraSess.setRepeatingRequest(ultraBuilder.build(), ultraCb, handler)
                try {
                    audioEncoder?.startCapturing()
                } catch (e: Exception) {
                    callback.onError("Audio start failed: ${e.message}")
                }
                callback.onRecordingStarted()
            } catch (e: Exception) {
                callback.onError("Record repeating request failed: ${e.message}")
                safeStopRecordingInternal(deleteOnFailure = true)
                isRecording = false
            }
        }

        // --- Wide recording session ---
        val wideOutputs = ArrayList<OutputConfiguration>(2).apply {
            add(OutputConfiguration(preview))
            add(OutputConfiguration(wideEnc.inputSurface))
        }
        wideCamera.createCaptureSessionByOutputConfigurations(
            wideOutputs,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    session = sess
                    try {
                        val builder = wideCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(preview)
                            addTarget(wideEnc.inputSurface)
                            applyCommonNoCropNoStab(this, isVideo = true, targetFps = requestedRecordFps)
                            applyZoomCrop(this, wideChars)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            try { set(CaptureRequest.NOISE_REDUCTION_MODE, videoNoiseReductionMode) } catch (_: Exception) {}
                            try { set(CaptureRequest.DISTORTION_CORRECTION_MODE, videoDistortionCorrectionMode) } catch (_: Exception) {}
                            try { set(CaptureRequest.EDGE_MODE, videoEdgeMode) } catch (_: Exception) {}
                            applyTorch(this)
                        }
                        repeatingBuilder = builder
                        dualRecordWideReady = true
                        maybeStartDualRecording()
                    } catch (e: Exception) {
                        callback.onError("Wide record repeating request failed: ${e.message}")
                        safeStopRecordingInternal(deleteOnFailure = true)
                        isRecording = false
                    }
                }

                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    callback.onError("Wide recording session configure failed (Moto dual-device).")
                    safeStopRecordingInternal(deleteOnFailure = true)
                    isRecording = false
                }
            },
            handler
        )

        // --- Ultra recording session ---
        val ultraOutputs = ArrayList<OutputConfiguration>(1).apply {
            add(OutputConfiguration(ultraEnc.inputSurface))
        }
        ultraCamera.createCaptureSessionByOutputConfigurations(
            ultraOutputs,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    ultraSession = sess
                    try {
                        val builder = ultraCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(ultraEnc.inputSurface)
                            applyCommonNoCropNoStab(this, isVideo = true, targetFps = requestedRecordFps, forUltraDevice = true)
                            applyZoomCrop(this, ultraChars)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            try { set(CaptureRequest.NOISE_REDUCTION_MODE, videoNoiseReductionMode) } catch (_: Exception) {}
                            try { set(CaptureRequest.DISTORTION_CORRECTION_MODE, videoDistortionCorrectionMode) } catch (_: Exception) {}
                            try { set(CaptureRequest.EDGE_MODE, videoEdgeMode) } catch (_: Exception) {}
                            // No torch on the ultra builder (shared flash, driven by wide).
                        }
                        ultraRepeatingBuilder = builder
                        dualRecordUltraReady = true
                        maybeStartDualRecording()
                    } catch (e: Exception) {
                        callback.onError("Ultra record repeating request failed: ${e.message}")
                        safeStopRecordingInternal(deleteOnFailure = true)
                        isRecording = false
                    }
                }

                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    callback.onError("Ultra recording session configure failed (Moto dual-device).")
                    safeStopRecordingInternal(deleteOnFailure = true)
                    isRecording = false
                }
            },
            handler
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Still capture
    // ---------------------------------------------------------------------------------------------

    /**
     * MOTO dual-device still capture: one TEMPLATE_STILL_CAPTURE per camera, submitted
     * back-to-back on the camera thread so the two exposures start as close together
     * as the two HAL pipelines allow. Each callback stores its own TotalCaptureResult
     * (cap.wideResult / cap.ultraResult); tryFinalizePhotoIfReady() pairs them up.
     * There is no hardware sync here — the JSON log / toast report the real
     * sensor-timestamp delta so you can see it per shot.
     */
    private fun doStillCaptureDual(raw: Boolean) {
        val wideCamera = cameraDevice ?: return
        val ultraCamera = ultraCameraDevice ?: return
        val wideSess = session ?: return
        val ultraSess = ultraSession ?: return
        val handler = requireNotNull(cameraHandler)

        val wideR = wideReader ?: run {
            callback.onError("Wide ImageReader not ready.")
            callback.onPhotoCaptureDone()
            return
        }
        val ultraR = ultraReader ?: run {
            callback.onError("Ultra ImageReader not ready.")
            callback.onPhotoCaptureDone()
            return
        }

        val preview = previewSurface
        val capAtStart = currentPhoto

        fun buildStillBuilder(
            camera: CameraDevice,
            targetReader: ImageReader,
            includePreview: Boolean,
            forUltra: Boolean
        ): CaptureRequest.Builder {
            return camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                if (includePreview && preview != null) addTarget(preview)
                addTarget(targetReader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                try { set(CaptureRequest.DISTORTION_CORRECTION_MODE, photoDistortionCorrectionMode) } catch (_: Exception) {}
                try { set(CaptureRequest.NOISE_REDUCTION_MODE, photoNoiseReductionMode) } catch (_: Exception) {}
                try { set(CaptureRequest.EDGE_MODE, photoEdgeMode) } catch (_: Exception) {}
                if (raw) {
                    try {
                        set(
                            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
                        )
                    } catch (_: Exception) {}
                }
                set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                applyCommonNoCropNoStab(this, isVideo = false, targetFps = previewTargetFps, applyFpsRange = false, forUltraDevice = forUltra)
                // Dual-device crop zoom: same relative crop on both lenses.
                applyZoomCrop(this, if (forUltra) ultraChars else wideChars)
                // Torch only from the wide builder (shared flash).
                if (!forUltra) applyTorch(this)
            }
        }

        fun resumeRepeating(sess: CameraCaptureSession, builder: CaptureRequest.Builder?) {
            try {
                val rb = builder ?: return
                sess.setRepeatingRequest(rb.build(), null, handler)
            } catch (_: Exception) {}
        }

        try {
            try { wideSess.stopRepeating() } catch (_: Exception) {}
            try { ultraSess.stopRepeating() } catch (_: Exception) {}

            val wideBuilder = buildStillBuilder(wideCamera, wideR, includePreview = true, forUltra = false)
            val ultraBuilder = buildStillBuilder(ultraCamera, ultraR, includePreview = false, forUltra = true)

            wideSess.capture(
                wideBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val cap = currentPhoto
                        if (cap != null) {
                            cap.wideResult = result
                            cap.totalResult = result
                            tryFinalizePhotoIfReady()
                        }
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        callback.onError("Wide still capture failed: $failure")
                        failAndCleanupCurrentPhoto()
                    }

                    override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                        resumeRepeating(session, repeatingBuilder)
                    }
                },
                handler
            )

            // Back-to-back: submit the ultra capture immediately after the wide one.
            ultraSess.capture(
                ultraBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val cap = currentPhoto
                        if (cap != null) {
                            cap.ultraResult = result
                            tryFinalizePhotoIfReady()
                        }
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        callback.onError("Ultra still capture failed: $failure")
                        failAndCleanupCurrentPhoto()
                    }

                    override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                        resumeRepeating(session, ultraRepeatingBuilder)
                    }
                },
                handler
            )

            // Watchdog: never leave UI stuck if one camera fails to deliver.
            handler.postDelayed({
                val capNow = currentPhoto
                if (capNow != null && capNow === capAtStart && !capNow.finished) {
                    Log.e(
                        TAG,
                        "Moto dual still timeout: wideReady=${capNow.wideImage != null}, ultraReady=${capNow.ultraImage != null}"
                    )
                    callback.onError("Still capture timed out on this device.")
                    failAndCleanupCurrentPhoto()
                    try { resumeRepeating(wideSess, repeatingBuilder) } catch (_: Exception) {}
                    try { resumeRepeating(ultraSess, ultraRepeatingBuilder) } catch (_: Exception) {}
                }
            }, 2500L)
        } catch (e: Exception) {
            callback.onError("Still capture exception: ${e.message}")
            failAndCleanupCurrentPhoto()
        }
    }

    private fun doStillCapture(raw: Boolean) {
        if (isDualDevice) {
            doStillCaptureDual(raw)
            return
        }
        val camera = cameraDevice ?: return
        val sess = session ?: return
        val handler = requireNotNull(cameraHandler)

        val wideR = wideReader ?: run {
            callback.onError("Wide ImageReader not ready.")
            callback.onPhotoCaptureDone()
            return
        }
        val ultraR = ultraReader ?: run {
            callback.onError("Ultra ImageReader not ready.")
            callback.onPhotoCaptureDone()
            return
        }

        val samsungMode = !phoneIsAPixel
        val preview = previewSurface

        // For watchdog to avoid nuking a later capture
        val capAtStart = currentPhoto

        try {
            try { sess.stopRepeating() } catch (_: Exception) {}

            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {

                // Critical: Samsung workaround - add the preview as an extra target if present
                // for some unknown, reason, this makes photos work (removes crash) for samsung
                if (samsungMode && preview != null) {
                    // force a 3-target request.
                    addTarget(preview)
                }

                addTarget(wideR.surface)
                addTarget(ultraR.surface)

                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

//                set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
//
//                // attempt to lessen noise reduction
//                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                try { set(CaptureRequest.DISTORTION_CORRECTION_MODE, photoDistortionCorrectionMode) } catch (_: Exception) {}

                // attempt to lessen noise reduction
                try { set(CaptureRequest.NOISE_REDUCTION_MODE, photoNoiseReductionMode) } catch (_: Exception) {}
                // NOTE: NOISE_REDUCTION_MODE_OFF looks GREAT (sharper) in good light, and even
                //   adds a nice non-smeary texture in somewhat lower light.
                // NOTE: NOISE_REDUCTION_MODE_MINIMAL causes crash.
                //   - Don't allow it as an option in UI settings when implemented.
                // NOISE_REDUCTION_MODE_HIGH_QUALITY, NOISE_REDUCTION_MODE_FAST,
                // NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG, and
                // NOISE_REDUCTION_MODE_OFF all seem to work fine on Pixels.

                try { set(CaptureRequest.EDGE_MODE, photoEdgeMode) } catch (_: Exception) {}

                // START: relevant to RAW only
                // RAW quality: request lens shading map so DngCreator can embed it (if supported).
                if (raw) {
                    try {
                        set(
                            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
                        )
                    } catch (_: Exception) {
                    }
                    setPhysical(
                        this,
                        widePhysicalId,
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
                    )
                    setPhysical(
                        this,
                        ultraPhysicalId,
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
                    )
                }
                // END: relevant to RAW only

                // Best-effort: ZSL/HDR off
                // NOTE: don't think I need the following line
                // try { set(CaptureRequest.CONTROL_ENABLE_ZSL, false) } catch (_: Exception) {}
                // turn off CONTROL_SCENE_MODE to be sure and avoid hdr (CONTROL_SCENE_MODE_HDR)
                set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                // set 3A to be controlled automatically
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

                applyCommonNoCropNoStab(this, isVideo = false, targetFps = previewTargetFps, applyFpsRange = false)
                applyTorch(this)

            }

            sess.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val cap = currentPhoto
                        if (cap != null) {
                            cap.totalResult = result
                            tryFinalizePhotoIfReady()
                        }
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        callback.onError("Still capture failed: $failure")
                        failAndCleanupCurrentPhoto()
                    }

                    override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                        try {
                            val rb = repeatingBuilder
                            if (rb != null) {
                                session.setRepeatingRequest(rb.build(), null, handler)
                            }
                        } catch (_: Exception) {}
                    }
                },
                handler
            )

            // Watchdog: never leave UI stuck if Samsung fails to deliver one stream.
            if (samsungMode) {
                handler.postDelayed({
                    val capNow = currentPhoto
                    if (capNow != null && capNow === capAtStart && !capNow.finished) {
                        Log.e(
                            TAG,
                            "Samsung still timeout: wideReady=${capNow.wideImage != null}, ultraReady=${capNow.ultraImage != null}"
                        )
                        callback.onError("Still capture timed out on this device.")
                        failAndCleanupCurrentPhoto()

                        // Best-effort resume repeating
                        try {
                            val rb = repeatingBuilder
                            if (rb != null) sess.setRepeatingRequest(rb.build(), null, handler)
                        } catch (_: Exception) {}
                    }
                }, 2500L)
            }

        } catch (e: Exception) {
            callback.onError("Still capture exception: ${e.message}")
            failAndCleanupCurrentPhoto()
        }
    }


    private fun onStillImageAvailable(isWide: Boolean, reader: ImageReader) {
        val image = try { reader.acquireNextImage() } catch (_: Exception) { null } ?: return

        val cap = currentPhoto
        if (cap == null || cap.finished) {
            image.close()
            return
        }

        if (isWide) {
            cap.wideImage?.close()
            cap.wideImage = image
        } else {
            cap.ultraImage?.close()
            cap.ultraImage = image
        }

        tryFinalizePhotoIfReady()
    }

    private fun tryFinalizePhotoIfReady() {
        val cap = currentPhoto ?: return
        if (cap.finished) return

        val wideImgNullable = cap.wideImage
        val ultraImgNullable = cap.ultraImage
        val totalResultNullable = cap.totalResult

        // For RAW, we always need the TotalCaptureResult to build DNG.
        // For YUV, we only need the TotalCaptureResult if we want exposure-time-based sync metrics
        // (photo JSON log / photo sync toast).
        // Dual-device: each camera delivers its own result, so both must be present.
        val needResult = cap.isRaw || cap.logUri != null || enablePhotoSyncToast
        val ready = if (needResult) {
            if (isDualDevice) {
                (wideImgNullable != null && ultraImgNullable != null &&
                    cap.wideResult != null && cap.ultraResult != null)
            } else {
                (wideImgNullable != null && ultraImgNullable != null && totalResultNullable != null)
            }
        } else {
            (wideImgNullable != null && ultraImgNullable != null)
        }

        if (!ready) return

        // Non-null locals now that we're ready.
        val wideImg = requireNotNull(wideImgNullable)
        val ultraImg = requireNotNull(ultraImgNullable)

        // Detach this capture so UI/camera can proceed immediately.
        cap.finished = true
        currentPhoto = null

        val wideUri = cap.wideUri
        val ultraUri = cap.ultraUri
        val sbsMatchedWideUri = cap.sbsMatchedWideUri
        val sbsMatchedUltraUri = cap.sbsMatchedUltraUri
        val photoLogUri = cap.logUri

        val captureId = cap.captureId
        val wallTimeMs = cap.wallTimeMs
        val exifDt = cap.exifDateTimeOriginal
        val isRaw = cap.isRaw
        val rotationDegrees = cap.rotationDegrees
        val zoom2xAtCapture = zoom2xEnabled

        // Photo sync metrics (for optional toast + optional per-photo JSON log)
        val wideImageTimestampNs: Long? = try { wideImg.timestamp } catch (_: Exception) { null }
        val ultraImageTimestampNs: Long? = try { ultraImg.timestamp } catch (_: Exception) { null }

        val physicalForMetrics: Map<String, CaptureResult> = try {
            totalResultNullable?.physicalCameraResults ?: emptyMap()
        } catch (_: Throwable) {
            emptyMap()
        }

        // Dual-device: per-lens results win; logical mode: physical results from the
        // logical TotalCaptureResult, falling back to the logical result itself.
        val wideResultForMetrics: CaptureResult? = cap.wideResult
            ?: physicalForMetrics[widePhysicalId] ?: totalResultNullable
        val ultraResultForMetrics: CaptureResult? = cap.ultraResult
            ?: physicalForMetrics[ultraPhysicalId] ?: totalResultNullable

        val wideSensorTimestampNs = wideResultForMetrics?.get(CaptureResult.SENSOR_TIMESTAMP)
            ?: (wideImageTimestampNs ?: 0L)
        val ultraSensorTimestampNs = ultraResultForMetrics?.get(CaptureResult.SENSOR_TIMESTAMP)
            ?: (ultraImageTimestampNs ?: 0L)

        val wideExposureTimeNs = wideResultForMetrics?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val ultraExposureTimeNs = ultraResultForMetrics?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L

        val syncMetrics = StereoPhotoLogger.computeMetrics(
            wideSensorTimestampNs = wideSensorTimestampNs,
            ultraSensorTimestampNs = ultraSensorTimestampNs,
            wideExposureTimeNs = wideExposureTimeNs,
            ultraExposureTimeNs = ultraExposureTimeNs
        )

        if (enablePhotoSyncToast) {
            callback.onStatus(StereoPhotoLogger.formatToast(syncMetrics))
        }

        // IMPORTANT: free UI immediately (saving continues in background)
        callback.onPhotoCaptureDone()

        val resolver = context.contentResolver

        if (!isRaw) {
            // --- YUV path ---
            // Convert to NV21 right away so we can close the ImageReader buffers quickly.
            val wideW = wideImg.width
            val wideH = wideImg.height
            val ultraW = ultraImg.width
            val ultraH = ultraImg.height

            val wideNv21 = try { wideImg.toNv21() } finally {
                try { wideImg.close() } catch (_: Exception) {}
            }
            val ultraNv21 = try { ultraImg.toNv21() } finally {
                try { ultraImg.close() } catch (_: Exception) {}
            }

            val wideFrame = StereoSbs.Nv21Frame(
                nv21 = wideNv21,
                width = wideW,
                height = wideH,
                rotationDegrees = rotationDegrees
            )
            val ultraFrame = StereoSbs.Nv21Frame(
                nv21 = ultraNv21,
                width = ultraW,
                height = ultraH,
                rotationDegrees = rotationDegrees
            )

            // Optional individual lens outputs (OFF by default)
            if (wideUri != null && ultraUri != null) {
                val wideOutUri = wideUri
                val ultraOutUri = ultraUri
                val logOutUri: Uri? = photoLogUri

                ioExecutor.execute {
                    try {
                        val wideJpeg = StereoSbs.yuvToJpegBytes(wideFrame, jpegQuality = 100)
                            ?: throw IllegalStateException("Wide YUV->JPEG encode failed")
                        val ultraJpeg = StereoSbs.yuvToJpegBytes(ultraFrame, jpegQuality = 100)
                            ?: throw IllegalStateException("Ultra YUV->JPEG encode failed")

                        ImageWriters.writeJpegWithExif(
                            context = context,
                            uri = wideOutUri,
                            bytes = wideJpeg,
                            exifDateTimeOriginal = exifDt,
                            exifOrientation = PlatformExif.ORIENTATION_NORMAL
                        )
                        ImageWriters.writeJpegWithExif(
                            context = context,
                            uri = ultraOutUri,
                            bytes = ultraJpeg,
                            exifDateTimeOriginal = exifDt,
                            exifOrientation = PlatformExif.ORIENTATION_NORMAL
                        )
                        MediaStoreUtils.finalizePending(resolver, wideOutUri)
                        MediaStoreUtils.finalizePending(resolver, ultraOutUri)

                        if (logOutUri != null) {
                            StereoPhotoLogger.writeJson(
                                context = context,
                                uri = logOutUri,
                                captureId = captureId,
                                wallTimeMs = wallTimeMs,
                                isRaw = false,
                                zoom2xEnabled = zoom2xAtCapture,
                                widePhysicalId = widePhysicalId,
                                ultraPhysicalId = ultraPhysicalId,
                                wideResult = wideResultForMetrics,
                                ultraResult = ultraResultForMetrics,
                                metrics = syncMetrics
                            )
                            MediaStoreUtils.finalizePending(resolver, logOutUri)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Saving individual lens JPEGs failed: ${e.message}", e)
                        // Don't leave dangling pending entries
                        MediaStoreUtils.delete(resolver, wideOutUri)
                        MediaStoreUtils.delete(resolver, ultraOutUri)
                        logOutUri?.let { MediaStoreUtils.delete(resolver, it) }
                        callback.onError("Saving individual lens JPEGs failed: ${e.message}")
                    }
                }
            } else {
                // Still write JSON log if enabled, even when not saving individuals
                if (photoLogUri != null) {
                    val logOutUri = photoLogUri
                    ioExecutor.execute {
                        try {
                            StereoPhotoLogger.writeJson(
                                context = context,
                                uri = logOutUri,
                                captureId = captureId,
                                wallTimeMs = wallTimeMs,
                                isRaw = false,
                                zoom2xEnabled = zoom2xAtCapture,
                                widePhysicalId = widePhysicalId,
                                ultraPhysicalId = ultraPhysicalId,
                                wideResult = wideResultForMetrics,
                                ultraResult = ultraResultForMetrics,
                                metrics = syncMetrics
                            )
                            MediaStoreUtils.finalizePending(resolver, logOutUri)
                        } catch (e: Exception) {
                            Log.e(TAG, "Writing photo JSON log failed: ${e.message}", e)
                            MediaStoreUtils.delete(resolver, logOutUri)
                        }
                    }
                }
            }

            // Produce only the two color-matched SBS versions (the plain
            // unmatched SBS is not saved anymore). The aligned SBS bytes are
            // still built in memory first; the matched variants are derived
            // from them with identical alignment, only colors changed.
            if (sbsMatchedWideUri != null || sbsMatchedUltraUri != null) {
                sbsExecutor.execute {
                    try {
                        val zoom2xForThisSbs = zoom2xAtCapture
                        val sbsView =
                            StereoSbs.SbsView.fromSetting(AppSettings.getSbsView(context))
                        val sbsBytes = StereoSbs.alignAndCreateSbsJpegBytes(
                            wideFrame = wideFrame,
                            ultraFrame = ultraFrame,
                            // Dual-device crop zoom already cropped BOTH lenses
                            // identically, so the aligner must NOT do its extra
                            // ultra crop (that crop is only for the logical-mode
                            // physical 2x wide, where the ultra stays full).
                            zoom2xEnabled = zoom2xForThisSbs && !isDualDevice,
                            fallbackUltraOverlapFraction = ultra3aFraction,
                            sbsView = sbsView
                        )

                        if (sbsBytes == null) {
                            Log.w(TAG, "SBS creation failed; deleting matched sbs pending URIs")
                            sbsMatchedWideUri?.let { MediaStoreUtils.delete(resolver, it) }
                            sbsMatchedUltraUri?.let { MediaStoreUtils.delete(resolver, it) }
                            return@execute
                        }

                        val (matchedToWide, matchedToUltra) =
                            StereoSbs.createColorMatchedSbsVariants(sbsBytes, sbsView)

                        if (sbsMatchedWideUri != null) {
                            if (matchedToWide != null) {
                                ImageWriters.writeJpegWithExif(
                                    context = context,
                                    uri = sbsMatchedWideUri,
                                    bytes = matchedToWide,
                                    exifDateTimeOriginal = exifDt,
                                    exifOrientation = PlatformExif.ORIENTATION_NORMAL
                                )
                                MediaStoreUtils.finalizePending(resolver, sbsMatchedWideUri)
                            } else {
                                MediaStoreUtils.delete(resolver, sbsMatchedWideUri)
                            }
                        }

                        if (sbsMatchedUltraUri != null) {
                            if (matchedToUltra != null) {
                                ImageWriters.writeJpegWithExif(
                                    context = context,
                                    uri = sbsMatchedUltraUri,
                                    bytes = matchedToUltra,
                                    exifDateTimeOriginal = exifDt,
                                    exifOrientation = PlatformExif.ORIENTATION_NORMAL
                                )
                                MediaStoreUtils.finalizePending(resolver, sbsMatchedUltraUri)
                            } else {
                                MediaStoreUtils.delete(resolver, sbsMatchedUltraUri)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Color-matched SBS creation failed: ${e.message}", e)
                        sbsMatchedWideUri?.let { MediaStoreUtils.delete(resolver, it) }
                        sbsMatchedUltraUri?.let { MediaStoreUtils.delete(resolver, it) }
                    }
                }
            }
        } else {
            // --- RAW path: keep Images open for background DNG write ---
            val wideOutUri = requireNotNull(wideUri) { "Raw capture requires wideUri" }
            val ultraOutUri = requireNotNull(ultraUri) { "Raw capture requires ultraUri" }
            val total = requireNotNull(totalResultNullable)
            // Use the per-physical CaptureResult when available so each DNG gets correct AWB/CCM/etc.
            // Fall back to the logical TotalCaptureResult if physical results are missing or incomplete.
            val wideResultForDng = wideResultForMetrics ?: total
            val ultraResultForDng = ultraResultForMetrics ?: total
            val dngOrientation = exifOrientationFromDegrees(rotationDegrees)

            ioExecutor.execute {
                try {
                    val wideCharacteristics = requireNotNull(wideChars)
                    val ultraCharacteristics = requireNotNull(ultraChars)

                    // Write wide DNG, then close wide Image immediately.
                    try {
                        try {
                            DngWriter.writeDng(
                                context = context,
                                uri = wideOutUri,
                                characteristics = wideCharacteristics,
                                result = wideResultForDng,
                                image = wideImg,
                                orientation = dngOrientation
                            )
                        } catch (e: Exception) {
                            // Some devices may omit keys in physical results; retry with logical metadata.
                            // Dual-device: there is no logical metadata, and `total` is the
                            // WIDE result — never use it for the ultra DNG. No retry there.
                            if (!isDualDevice && wideResultForDng !== total) {
                                Log.w(TAG, "Wide DNG write failed with physical metadata; retrying with logical metadata: ${e.message}")
                                DngWriter.writeDng(
                                    context = context,
                                    uri = wideOutUri,
                                    characteristics = wideCharacteristics,
                                    result = total,
                                    image = wideImg,
                                    orientation = dngOrientation
                                )
                            } else {
                                throw e
                            }
                        }
                    } finally {
                        try { wideImg.close() } catch (_: Exception) {}
                    }

                    // Write ultra DNG, then close ultra Image immediately.
                    try {
                        try {
                            DngWriter.writeDng(
                                context = context,
                                uri = ultraOutUri,
                                characteristics = ultraCharacteristics,
                                result = ultraResultForDng,
                                image = ultraImg,
                                orientation = dngOrientation
                            )
                        } catch (e: Exception) {
                            // Some devices may omit keys in physical results; retry with logical metadata.
                            // Dual-device: no logical metadata exists — never retry the ultra
                            // DNG with the wide result. No retry there.
                            if (!isDualDevice && ultraResultForDng !== total) {
                                Log.w(TAG, "Ultra DNG write failed with physical metadata; retrying with logical metadata: ${e.message}")
                                DngWriter.writeDng(
                                    context = context,
                                    uri = ultraOutUri,
                                    characteristics = ultraCharacteristics,
                                    result = total,
                                    image = ultraImg,
                                    orientation = dngOrientation
                                )
                            } else {
                                throw e
                            }
                        }
                    } finally {
                        try { ultraImg.close() } catch (_: Exception) {}
                    }

                    MediaStoreUtils.finalizePending(resolver, wideOutUri)
                    MediaStoreUtils.finalizePending(resolver, ultraOutUri)

                    // Optional: per-photo JSON log (one per still capture)
                    if (photoLogUri != null) {
                        val logOutUri = photoLogUri
                        try {
                            StereoPhotoLogger.writeJson(
                                context = context,
                                uri = logOutUri,
                                captureId = captureId,
                                wallTimeMs = wallTimeMs,
                                isRaw = true,
                                zoom2xEnabled = zoom2xAtCapture,
                                widePhysicalId = widePhysicalId,
                                ultraPhysicalId = ultraPhysicalId,
                                wideResult = wideResultForMetrics,
                                ultraResult = ultraResultForMetrics,
                                metrics = syncMetrics
                            )
                            MediaStoreUtils.finalizePending(resolver, logOutUri)
                        } catch (e: Exception) {
                            Log.e(TAG, "Photo JSON log write failed: ${e.message}", e)
                            MediaStoreUtils.delete(resolver, logOutUri)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "DNG save failed: ${e.message}", e)
                    // Ensure images aren't leaked if an exception happens before the finally blocks above.
                    try { wideImg.close() } catch (_: Exception) {}
                    try { ultraImg.close() } catch (_: Exception) {}

                    MediaStoreUtils.delete(resolver, wideOutUri)
                    MediaStoreUtils.delete(resolver, ultraOutUri)
                    photoLogUri?.let { MediaStoreUtils.delete(resolver, it) }
                    callback.onError("DNG save failed: ${e.message}")
                }
            }
        }
    }

    private fun failAndCleanupCurrentPhoto() {
        val cap = currentPhoto ?: return
        currentPhoto = null
        cap.finished = true
        try { cap.wideImage?.close() } catch (_: Exception) {}
        try { cap.ultraImage?.close() } catch (_: Exception) {}

        val resolver = context.contentResolver
        cap.wideUri?.let { MediaStoreUtils.delete(resolver, it) }
        cap.ultraUri?.let { MediaStoreUtils.delete(resolver, it) }

        // so we don’t leave dangling pending _sbs_matched_*.jpg entries
        cap.sbsMatchedWideUri?.let { MediaStoreUtils.delete(resolver, it) }
        cap.sbsMatchedUltraUri?.let { MediaStoreUtils.delete(resolver, it) }

        // so we don’t leave a dangling pending _photo.json entry
        cap.logUri?.let { MediaStoreUtils.delete(resolver, it) }

        callback.onPhotoCaptureDone()
    }

    // ---------------------------------------------------------------------------------------------
    // Request settings (no stabilization/crop, torch, etc.)
    // ---------------------------------------------------------------------------------------------

    private fun applyTorch(builder: CaptureRequest.Builder) {
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
    }

    private fun updateTorchOnRepeating() {
        val sess = session ?: return
        val handler = cameraHandler ?: return
        val rb = repeatingBuilder ?: return
        applyTorch(rb)
        try {
            //sess.setRepeatingRequest(rb.build(), null, handler)
            val cb = if (isRecording) recordCaptureCallback else null
            sess.setRepeatingRequest(rb.build(), cb, handler)
        } catch (e: Exception) {
            Log.w(TAG, "updateTorchOnRepeating failed: ${e.message}")
        }
    }

    private fun centeredRect(active: Rect, fraction: Float): Rect {

        // Creates a new Rect that is centered within the active rectangle and
        // scaled by the given fraction.

        val f = fraction.coerceIn(0.05f, 1.0f)
        val w = active.width()
        val h = active.height()

        val newW = (w * f).toInt().coerceAtLeast(1)
        val newH = (h * f).toInt().coerceAtLeast(1)

        val left = active.left + (w - newW) / 2
        val top = active.top + (h - newH) / 2

        val r = Rect(left, top, left + newW, top + newH)
        r.intersect(active) // safety clamp
        return r
    }

    private fun applyUltrawide3ARegions(builder: CaptureRequest.Builder, forUltraDevice: Boolean = false) {

        // constrain ultrawide 3A to overlap-ish center region

        val ultra = ultraChars ?: return

        // Only set regions if supported for that physical camera.
        val maxAf = ultra.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAe = ultra.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        val maxAwb = ultra.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB) ?: 0

        if (maxAf <= 0 && maxAe <= 0 && maxAwb <= 0) return

        val active = ultra.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val rect = centeredRect(active, ultra3aFraction)

        // In 2x mode, the aligner will software-crop the ultrawide to the centered inner 50%.
        // Keep ultrawide 3A (AE/AF/AWB metering) inside that same effective region.
        if (zoom2xEnabled) {
            rect.intersect(centeredRect(active, 0.50f))
        }

        val region = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
        val regions = arrayOf(region)

        if (isDualDevice) {
            // Standalone ultra device: set the regions directly on its own builder.
            // On a WIDE builder in dual mode, do nothing — the wide metering must
            // stay full-frame.
            if (!forUltraDevice) return
            if (maxAf > 0) {
                try { builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions) } catch (_: Exception) {}
            }
            if (maxAe > 0) {
                try { builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions) } catch (_: Exception) {}
            }
            if (maxAwb > 0) {
                try { builder.set(CaptureRequest.CONTROL_AWB_REGIONS, regions) } catch (_: Exception) {}
            }
            return
        }

        // set the AF/AE/AWB regions if supported
        // (number of regions > 0 since we want to use 1 region)
        if (maxAf > 0) {
            setPhysical(builder, ultraPhysicalId, CaptureRequest.CONTROL_AF_REGIONS, regions)
        }
        if (maxAe > 0) {
            setPhysical(builder, ultraPhysicalId, CaptureRequest.CONTROL_AE_REGIONS, regions)
        }
        if (maxAwb > 0) {
            setPhysical(builder, ultraPhysicalId, CaptureRequest.CONTROL_AWB_REGIONS, regions)
        }
    }

    private fun chooseAeFpsRangeFromList(ranges: List<Range<Int>>, desiredFps: Int): Range<Int>? {
        // Prefer a locked range if the device exposes it.
        ranges.firstOrNull { it.lower == desiredFps && it.upper == desiredFps }?.let { return it }

        val candidates = ranges.filter { it.lower <= desiredFps && it.upper >= desiredFps }
        if (candidates.isEmpty()) {
            // Fallback: pick the highest available range.
            return ranges.maxByOrNull { it.upper }
        }

        // Choose the tightest range that still contains desiredFps.
        return candidates.minWithOrNull(
            compareBy<Range<Int>> { it.upper - it.lower }.thenBy { it.lower }
        ) ?: candidates.first()
    }

    private fun chooseAeFpsRange(chars: CameraCharacteristics?, desiredFps: Int): Range<Int>? {
        val ranges =
            chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()
                ?: return null

        return chooseAeFpsRangeFromList(ranges, desiredFps)
    }

    /**
     * Choose a single AE FPS range that is supported by BOTH the active wide physical camera
     * and the active ultrawide physical camera.
     *
     * This is important when streaming two physical cameras at once (recording), since
     * requesting different AE FPS ranges per physical camera can create conflicting constraints.
     */
    private fun chooseCommonAeFpsRange(desiredFps: Int): Range<Int>? {
        val wideRanges =
            wideChars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()
                ?: return null
        val ultraRanges =
            ultraChars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()
                ?: return null

        val ultraSet = ultraRanges.map { it.lower to it.upper }.toSet()
        val common = wideRanges.filter { ultraSet.contains(it.lower to it.upper) }
        if (common.isEmpty()) return null

        return chooseAeFpsRangeFromList(common, desiredFps)
    }


    private fun supportsOis(chars: CameraCharacteristics?): Boolean {
        val modes = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: return false
        return modes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
    }

    private fun applyOisAlwaysOnIfSupported(builder: CaptureRequest.Builder, forUltraDevice: Boolean = false) {
        // OIS: always request ON (where supported).
        // Note: some lenses (e.g., Pixel 7 Pro ultrawide) have no OIS and will remain off.

        if (isDualDevice) {
            // Standalone device: enable OIS only if THIS device's lens supports it.
            // (Moto G Stylus 2026 reports no OIS modes on either lens via Camera2,
            // so this is a no-op there — the plain set() is never attempted.)
            val chars = if (forUltraDevice) ultraChars else wideChars
            if (supportsOis(chars)) {
                try {
                    builder.set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    )
                } catch (_: Exception) {
                }
            }
            return
        }

        try {
            if (supportsOis(logicalChars)) {
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
            }
        } catch (_: Exception) {
        }

        if (supportsOis(wideChars)) {
            setPhysical(
                builder,
                widePhysicalId,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
        }
        if (supportsOis(ultraChars)) {
            setPhysical(
                builder,
                ultraPhysicalId,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
        }
    }

    private fun applyCommonNoCropNoStab(builder: CaptureRequest.Builder, isVideo: Boolean, targetFps: Int, applyFpsRange: Boolean = true, forUltraDevice: Boolean = false) {

        // EIS toggle (video only)
        if (isVideo && eisEnabled) {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
        } else {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }

        // OIS is always requested ON (where supported).
        applyOisAlwaysOnIfSupported(builder, forUltraDevice)

        if (applyFpsRange) {
            // AE target FPS range.
            //
            // For dual-physical streaming (recording), pick a SINGLE range that both lenses support.
            // For preview (single physical stream), prefer the active wide lens.
            val fpsRange =
                if (isVideo) {
                    chooseCommonAeFpsRange(targetFps)
                        ?: chooseAeFpsRange(logicalChars, targetFps)
                } else if (isDualDevice && forUltraDevice) {
                    // Dual-device ultra preview: the ultra device needs its own range.
                    chooseAeFpsRange(ultraChars, targetFps)
                        ?: chooseAeFpsRange(wideChars, targetFps)
                } else {
                    chooseAeFpsRange(wideChars, targetFps)
                        ?: chooseAeFpsRange(logicalChars, targetFps)
                        ?: chooseAeFpsRange(ultraChars, targetFps)
                }

            if (fpsRange != null) {
                try {
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                } catch (_: Exception) {
                }
            }
        }

        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        // constrain ultrawide 3A to overlap-ish center region
        applyUltrawide3ARegions(builder, forUltraDevice)
    }

    private fun <T> setPhysical(builder: CaptureRequest.Builder, physicalId: String, key: CaptureRequest.Key<T>, value: T) {
        try {
            // Correct order: (key, value, physicalCameraId)
            builder.setPhysicalCameraKey(key, value, physicalId)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun resolveWide2xPhysicalId(physicalIds: Set<String>): String? {
        // Prefer Pro mapping first (because Pro devices also *contain* "4", but it's tele there).
        return when {
            physicalIds.contains(WIDE_2X_PHYSICAL_ID_PRO) -> WIDE_2X_PHYSICAL_ID_PRO
            physicalIds.contains(WIDE_2X_PHYSICAL_ID) -> WIDE_2X_PHYSICAL_ID
            else -> null
        }
    }

     private fun closeReaders() {
        try { wideReader?.close() } catch (_: Exception) {}
        try { ultraReader?.close() } catch (_: Exception) {}
        wideReader = null
        ultraReader = null
    }

    private fun computeOrientationHint(): Int {
        val rotationDegrees = when (displayRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        // Back camera: (sensor - device + 360) % 360
        return (sensorOrientation - rotationDegrees + 360) % 360
    }

    private fun computeJpegOrientation(): Int = computeOrientationHint()

    private fun exifOrientationFromDegrees(deg: Int): Int = when (deg) {
        0 -> PlatformExif.ORIENTATION_NORMAL
        90 -> PlatformExif.ORIENTATION_ROTATE_90
        180 -> PlatformExif.ORIENTATION_ROTATE_180
        270 -> PlatformExif.ORIENTATION_ROTATE_270
        else -> PlatformExif.ORIENTATION_UNDEFINED
    }


    private fun exifDateTimeOriginal(timestampMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        return sdf.format(Date(timestampMillis))
    }

    private fun safeStopRecordingInternal(deleteOnFailure: Boolean) {
        // Direct mode (0.77.0): stop the recorder and drop its partial file.
        // releaseDirectState also closes the sessions; the closes below are
        // harmless repeats for the two-stream path.
        if (isDirectRecording) {
            releaseDirectState()
        }

        // Stop session output
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        // Dual-device: stop the ultrawide session too.
        try { ultraSession?.stopRepeating() } catch (_: Exception) {}
        try { ultraSession?.abortCaptures() } catch (_: Exception) {}
        try { ultraSession?.close() } catch (_: Exception) {}

        session = null
        repeatingBuilder = null
        ultraSession = null
        ultraRepeatingBuilder = null
        dualRecordWideReady = false
        dualRecordUltraReady = false
        recordCaptureCallback = null

        val resolver = context.contentResolver

        // Stop encoders
        val wEnc = wideEncoder
        val uEnc = ultraEncoder
        val aEnc = audioEncoder
        val wMux = wideMuxer
        val uMux = ultraMuxer

        wideEncoder = null
        ultraEncoder = null
        audioEncoder = null
        wideMuxer = null
        ultraMuxer = null

        try { wEnc?.signalEndOfInputStream() } catch (_: Exception) {}
        try { uEnc?.signalEndOfInputStream() } catch (_: Exception) {}

        try { aEnc?.stopAndRelease() } catch (_: Exception) {}
        try { wEnc?.stopAndRelease() } catch (_: Exception) {}
        try { uEnc?.stopAndRelease() } catch (_: Exception) {}

        try { wMux?.stopAndRelease() } catch (_: Exception) {}
        try { uMux?.stopAndRelease() } catch (_: Exception) {}

        val wUri = wideVideoUri
        val uUri = ultraVideoUri
        wideVideoUri = null
        ultraVideoUri = null
        currentRecordingId = null

        if (wUri != null && uUri != null) {
            if (deleteOnFailure) {
                MediaStoreUtils.delete(resolver, wUri)
                MediaStoreUtils.delete(resolver, uUri)
            } else {
                MediaStoreUtils.finalizePending(resolver, wUri)
                MediaStoreUtils.finalizePending(resolver, uUri)
            }
        }

        // NEW logging stuff
        val logUri = recordingLogUri
        recordingLogUri = null

        // If we created a log entry but failed, delete it so it doesn't remain pending.
        if (logUri != null) {
            if (deleteOnFailure) {
                MediaStoreUtils.delete(resolver, logUri)
            } else {
                // Best-effort: write whatever we have and finalize
                try {
                    recordingLogger?.markStopped()
                    recordingLogger?.writeToUri(context, logUri)
                    MediaStoreUtils.finalizePending(resolver, logUri)
                } catch (_: Exception) {
                    MediaStoreUtils.delete(resolver, logUri)
                }
            }
        }
        recordingLogger = null
        // end NEW logging stuff
    }
}

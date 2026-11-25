package com.example.duallens3dcamera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Paint
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.example.duallens3dcamera.camera.LensDiscovery
import com.example.duallens3dcamera.preview.SbsPreviewView
import com.example.duallens3dcamera.settings.AppSettings
import com.example.duallens3dcamera.settings.SettingsActivity
import com.example.duallens3dcamera.camera.StereoCameraController
import com.example.duallens3dcamera.util.CrashLog
import com.example.duallens3dcamera.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), StereoCameraController.Callback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: StereoCameraController

    // ---- persisted settings ----
    private val prefs by lazy { getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE) }

    private var eisEnabled: Boolean = false // default OFF
    private var rawMode: Boolean = false // default JPG
    private var torchOn: Boolean = false // not persisted (you didn't ask)
    private var zoom2xEnabled: Boolean = false // default 1x

    // Settings screen (loaded from SharedPreferences)
    private var videoResolution: Size = Size(1440, 1080)
    private var videoFps: Int = 30
    private var videoBitrateBps: Int = 50_000_000

    private var previewResolution: Size = Size(800, 600)
    private var previewFps: Int = 30

    private var photoNoiseReductionMode: Int = CaptureRequest.NOISE_REDUCTION_MODE_OFF
    private var photoDistortionCorrectionMode: Int =
        CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
    private var photoEdgeMode: Int = CaptureRequest.EDGE_MODE_OFF
    private var photoStillResolutionMode: StereoCameraController.StillResolutionMode =
        StereoCameraController.StillResolutionMode.LARGEST_COMMON
    // Whether or not to save the two images separately on top of the sbs output
    // Note: Applies in JPG mode only (RAW mode always provides two dng files and not sbs)
    private var saveIndividualLensImages: Boolean = false


    // ---- UI state flags ----
    private var isRecording: Boolean = false
    private var isBusy: Boolean = false

    private var previewConfig: StereoCameraController.PreviewConfig? = null

    // 0.79.6: the texture pair the camera was started with. The GL
    // surface is recreated on every resume (EGL context not
    // preserved), so the view hands us a fresh pair — if the camera
    // was started with the old pair, it writes into textures nobody
    // draws and the preview stays black (the Settings-return bug).
    private var startedWideTexture: android.graphics.SurfaceTexture? = null
    private var startedUltraTexture: android.graphics.SurfaceTexture? = null

    // 0.79.5: how many times this foregrounding has already auto-restarted
    // the camera after the controller spent its preview retries. Bounded
    // (2) so a genuinely broken camera can't loop forever.
    private var previewAutoRestarts: Int = 0

    // GPU SBS preview (0.78.0): whether the setting wants SBS, whether the
    // current camera session is actually running it, and which eye is left.
    // The cross/parallel flip is GL-side, so it applies live with no restart.
    private var sbsPreviewEnabled: Boolean = true
    // 0.79.7: frame pairing (Settings > Preview > "Pair frames").
    private var pairFramesEnabled: Boolean = true
    // 0.79.9: color-match mode (Settings > Video > "SBS video color
    // match"): "wide" / "ultra" / "off".
    private var colorMatchMode: String = "wide"
    private var sbsPreviewActive: Boolean = false
    private var sbsViewCross: Boolean = true

    // 0.76.5: whether the currently inflated layout is the landscape one.
    // Set at inflation time in onCreate; onConfigurationChanged compares it
    // against the new orientation and recreates only when they disagree
    // (launching the Moto app while holding it portrait inflates the portrait
    // layout, then the sensorLandscape lock flips it to landscape).
    private var inflatedLandscape: Boolean = false


    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraOk = grants[Manifest.permission.CAMERA] == true
        val audioOk = grants[Manifest.permission.RECORD_AUDIO] == true
        if (cameraOk && audioOk) {
            maybeStartCamera()
        } else {
            toastOnUi("CAMERA + RECORD_AUDIO permissions are required.")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 0.77.1: catch uncaught crashes to a file (Documents/StereoCapture)
        // so a crash on her phone leaves a readable record behind.
        CrashLog.install(this)

        // 0.76.5: on the Moto the stereo pair is only correct in landscape
        // (the lenses are stacked vertically on the back), so the activity
        // follows the two landscape orientations and the UI text stays
        // upright. Other phones keep the manifest's portrait lock.
        if (LensDiscovery.isMotoStylusDevice()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        inflatedLandscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        controller = StereoCameraController(this, this)

        loadPrefs()
        updateTopBarUi()
        updateZoomButton()
        updateTorchButton()
        updateUi()

//        binding.btnVideoRes.setOnClickListener {
//            if (isRecording || isBusy) return@setOnClickListener
//            videoResIndex = (videoResIndex + 1) % videoVerticalOptions.size
//            savePrefs()
//            updateVideoResButton()
//            applyVideoConfigToController()
//        }
//
//        binding.btnBitrate.setOnClickListener {
//            if (isRecording || isBusy) return@setOnClickListener
//            bitrateIndex = (bitrateIndex + 1) % bitrateOptionsMbps.size
//            savePrefs()
//            updateBitrateButton()
//            applyVideoConfigToController()
//        }

        binding.btnSettings.setOnClickListener {
            if (isRecording || isBusy) return@setOnClickListener
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnEis.setOnClickListener {
            if (isRecording || isBusy) return@setOnClickListener
            eisEnabled = !eisEnabled
            savePrefs()
            updateEisButton()
            applyVideoConfigToController()
        }

        binding.btnFormat.setOnClickListener {
            if (isRecording || isBusy) return@setOnClickListener
            rawMode = !rawMode
            savePrefs()
            updateFormatButton()
            controller.setPhotoOutputRaw(rawMode)
        }

        binding.btnTorch.setOnClickListener {
            if (isRecording || isBusy) return@setOnClickListener
            torchOn = !torchOn
            updateTorchButton()
            controller.setTorch(torchOn)
        }

        binding.btnPhoto.setOnClickListener {
            if (isRecording || isBusy) return@setOnClickListener
            isBusy = true
            updateUi()
            val ts = timestampForFilename()
            controller.captureStereoPhoto(ts, rawMode)
        }

        binding.btnZoom.setOnClickListener {
            if (isRecording || isBusy) return@setOnClickListener
            zoom2xEnabled = !zoom2xEnabled
            savePrefs()
            updateZoomButton()
            controller.setZoom2x(zoom2xEnabled)
        }

        binding.btnRecord.setOnClickListener {
            if (isBusy) return@setOnClickListener

            if (!isRecording) {
                isRecording = true
                isBusy = true
                updateUi()

                // Ensure controller has current config (in case user changed it before camera started).
                applyVideoConfigToController()

                val ts = timestampForFilename()
                controller.startRecording(ts)
            } else {
                isBusy = true
                updateUi()
                controller.stopRecording()
            }
        }

        // 0.78.0: the GPU SBS preview owns both preview textures. They are
        // created on the GL thread when the surface is created (and again
        // after a pause, since the EGL context is not preserved), and the
        // camera starts once they exist.
        binding.sbsPreview.listener = object : SbsPreviewView.Listener {
            override fun onPreviewTexturesReady(
                wide: android.graphics.SurfaceTexture,
                ultra: android.graphics.SurfaceTexture
            ) {
                // 0.79.6: if the camera is already running but with a
                // different texture pair (the GL surface was recreated
                // on resume while the camera was started with the old
                // pair — the Settings-return black screen), restart it
                // with the new pair. Otherwise the camera writes into
                // textures nobody draws and the preview stays black.
                if (previewConfig != null &&
                    (wide !== startedWideTexture || ultra !== startedUltraTexture)
                ) {
                    Log.i("MainActivity", "Preview textures recreated — restarting camera with the new pair")
                    controller.stop()
                    previewConfig = null
                    sbsPreviewActive = false
                    startedWideTexture = null
                    startedUltraTexture = null
                }
                maybeStartCamera()
            }
        }
        binding.sbsPreview.setSbsMode(sbsPreviewEnabled, sbsViewCross)
        binding.sbsPreview.pairFrames = pairFramesEnabled
        binding.sbsPreview.applyColorMatchMode(colorMatchMode)
        binding.sbsPreview.setDisplayRotation(display?.rotation ?: Surface.ROTATION_0)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 0.76.5: the Moto activity runs in sensorLandscape. A 180° flip
        // (landscape <-> reverse landscape) keeps the same layout — just
        // tell the controller the new rotation and re-fit the preview.
        // A portrait <-> landscape flip means the wrong layout is inflated
        // (only happens at launch while held portrait); recreate once so
        // the right layout loads. This never fires mid-recording: the Moto
        // only flips landscape <-> landscape, and other phones are locked.
        val nowLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (nowLandscape != inflatedLandscape) {
            recreate()
            return
        }
        controller.setDisplayRotation(display?.rotation ?: Surface.ROTATION_0)
        // 0.78.1: the GL shader rotates the sampling coordinates itself
        // (the ST matrix does not), so a 180° landscape flip is a live
        // GL-side change — no camera restart, no transform to re-fit.
        binding.sbsPreview.setDisplayRotation(display?.rotation ?: Surface.ROTATION_0)
        applyPreviewAspectRatio()
    }

    override fun onResume() {
        super.onResume()
        // Resume the GL thread first: if the surface was recreated, the
        // textures listener fires and starts the camera from there.
        binding.sbsPreview.onResume()
        if (!hasAllPermissions()) {
            requestPermissions()
        } else {
            AppSettings.ensureCameraBasedDefaults(this)
            // If the SBS setting changed while we were in Settings, restart
            // the camera so the new preview mode takes effect. A cross /
            // parallel flip needs no restart (GL-side only).
            val wasSbsActive = sbsPreviewActive
            loadPrefs()
            binding.sbsPreview.setSbsMode(sbsPreviewEnabled, sbsViewCross)
            binding.sbsPreview.pairFrames = pairFramesEnabled
            binding.sbsPreview.applyColorMatchMode(colorMatchMode)
            binding.sbsPreview.setDisplayRotation(display?.rotation ?: Surface.ROTATION_0)
            updateTopBarUi()
            updateZoomButton()
            updateTorchButton()
            updateUi()
            if (previewConfig != null && sbsPreviewEnabled != wasSbsActive) {
                controller.stop()
                previewConfig = null
                sbsPreviewActive = false
            }
            previewAutoRestarts = 0
            maybeStartCamera()
        }
    }


    override fun onPause() {
        // Stop the camera BEFORE pausing the GL thread, so no session is
        // still writing into a texture whose EGL context is going away.
        controller.stop()
        previewConfig = null
        sbsPreviewActive = false
        startedWideTexture = null
        startedUltraTexture = null
        binding.sbsPreview.onPause()
        super.onPause()
    }

    private fun maybeStartCamera() {
        if (!hasAllPermissions()) return
        if (previewConfig != null) return
        // The GL textures may not exist yet on a cold start; the listener
        // calls us again once onSurfaceCreated has made them.
        val (wideSt, ultraSt) = binding.sbsPreview.surfaceTextures() ?: return
        startedWideTexture = wideSt
        startedUltraTexture = ultraSt
        val rotation = display?.rotation ?: Surface.ROTATION_0

//        previewConfig = controller.start(
//            surfaceTexture = st,
//            displayRotation = rotation,
//            initialRawMode = rawMode,
//            initialTorchOn = torchOn,
//            initialRequestedRecordSize = currentRequestedRecordSize(),
//            initialVideoBitrateBps = currentVideoBitrateBps(),
//            initialEisEnabled = eisEnabled,
//            initialZoom2x = zoom2xEnabled
//        )

        previewConfig = controller.start(
            StereoCameraController.StartParams(
                surfaceTexture = wideSt,
                ultraSurfaceTexture = ultraSt,
                sbsPreviewEnabled = sbsPreviewEnabled,
                displayRotation = rotation,
                rawMode = rawMode,
                torchOn = torchOn,
                zoom2xEnabled = zoom2xEnabled,
                video = StereoCameraController.VideoConfig(
                    requestedRecordSize = currentRequestedRecordSize(),
                    requestedRecordFps = currentVideoFps(),
                    videoBitrateBps = currentVideoBitrateBps(),
                    eisEnabled = eisEnabled
                ),
                preview = StereoCameraController.PreviewSettings(
                    requestedPreviewSize = previewResolution,
                    previewTargetFps = previewFps
                ),
                photo = StereoCameraController.PhotoTuning(
                    noiseReductionMode = photoNoiseReductionMode,
                    distortionCorrectionMode = photoDistortionCorrectionMode,
                    edgeMode = photoEdgeMode,
                    stillResolutionMode = photoStillResolutionMode
                )
            )
        )

        // Optimistic until the controller says otherwise: if the ultra
        // preview stream is refused, onSbsPreviewFallback switches us back.
        sbsPreviewActive = sbsPreviewEnabled
        binding.sbsPreview.post { applyPreviewAspectRatio() }
    }

    private fun hasAllPermissions(): Boolean {
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val aud = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return cam && aud
    }

    private fun requestPermissions() {
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    // ---------------- prefs ----------------

//    private fun loadPrefs() {
//        videoResIndex = prefs.getInt("videoResIndex", 0).coerceIn(0, videoVerticalOptions.size - 1)
//        bitrateIndex = prefs.getInt("bitrateIndex", 0).coerceIn(0, bitrateOptionsMbps.size - 1)
//        eisEnabled = prefs.getBoolean("eisEnabled", false)
//        rawMode = prefs.getBoolean("rawMode", false)
//        zoom2xEnabled = prefs.getBoolean("zoom2xEnabled", false)
//    }
//
//    private fun savePrefs() {
//        prefs.edit()
//            .putInt("videoResIndex", videoResIndex)
//            .putInt("bitrateIndex", bitrateIndex)
//            .putBoolean("eisEnabled", eisEnabled)
//            .putBoolean("rawMode", rawMode)
//            .putBoolean("zoom2xEnabled", zoom2xEnabled)
//            .apply()
//    }
//
//    // ---------------- top bar UI ----------------
//
    private fun updateZoomButton() {
        binding.btnZoom.text = if (zoom2xEnabled) "2x" else "1x"
    }
//
//    private fun updateTopBarUi() {
//        updateVideoResButton()
//        updateBitrateButton()
//        updateEisButton()
//        updateFormatButton()
//    }
//
//    private fun updateVideoResButton() {
//        binding.btnVideoRes.text = videoVerticalOptions[videoResIndex].toString()
//    }
//
//    private fun updateBitrateButton() {
//        binding.btnBitrate.text = "${bitrateOptionsMbps[bitrateIndex]}"
//    }
//
    private fun updateEisButton() {
        binding.btnEis.text = "EIS"
        val base = binding.btnEis.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        binding.btnEis.paintFlags = if (eisEnabled) base else (base or Paint.STRIKE_THRU_TEXT_FLAG)
    }

    private fun updateFormatButton() {
        binding.btnFormat.text = if (rawMode) "RAW" else "JPG"
    }

    private fun updateTorchButton() {
        if (torchOn) {
            binding.btnTorch.text = "Torch ON"
            binding.btnTorch.setTextColor(ContextCompat.getColor(this, android.R.color.black))
            binding.btnTorch.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.white)
        } else {
            binding.btnTorch.text = "Torch OFF"
            binding.btnTorch.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            binding.btnTorch.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.black)
        }
    }
//
//    private fun currentRequestedRecordSize(): Size {
//        val vertical = videoVerticalOptions[videoResIndex]      // e.g. 1440
//        val horizontal = (vertical * 3) / 4                    // e.g. 1080
//        // Use landscape 4:3 (w > h). Muxer orientationHint rotates to portrait.
//        return Size(vertical, horizontal)                       // e.g. 1440x1080
//    }
//
//    private fun currentVideoBitrateBps(): Int {
//        val mbps = bitrateOptionsMbps[bitrateIndex]
//        return mbps * 1_000_000
//    }
//
//    private fun applyVideoConfigToController() {
//        controller.setVideoConfig(
//            requestedRecordSize = currentRequestedRecordSize(),
//            videoBitrateBps = currentVideoBitrateBps(),
//            eisEnabled = eisEnabled
//        )
//    }

    private fun loadPrefs() {
        eisEnabled = prefs.getBoolean(AppSettings.KEY_EIS_ENABLED, false)
        rawMode = prefs.getBoolean(AppSettings.KEY_RAW_MODE, false)
        zoom2xEnabled = prefs.getBoolean(AppSettings.KEY_ZOOM_2X, false)

        videoResolution = AppSettings.getVideoResolution(this)
        videoFps = AppSettings.getVideoFps(this)
        videoBitrateBps = AppSettings.getVideoBitrateBps(this)

        previewResolution = AppSettings.getPreviewResolution(this)
        previewFps = AppSettings.getPreviewFps(this)
        sbsPreviewEnabled = AppSettings.isSbsPreviewEnabled(this)
        pairFramesEnabled = AppSettings.isPairFramesEnabled(this)
        colorMatchMode = AppSettings.getVideoColorMatch(this)
        sbsViewCross = !AppSettings.isSbsParallel(this)

        photoNoiseReductionMode = AppSettings.getPhotoNoiseReductionMode(this)
        photoDistortionCorrectionMode = AppSettings.getPhotoDistortionCorrectionMode(this)
        photoEdgeMode = AppSettings.getPhotoEdgeMode(this)
        photoStillResolutionMode = StereoCameraController.StillResolutionMode.fromPrefValue(
            AppSettings.getPhotoStillResolutionMode(this)
        )
        saveIndividualLensImages = AppSettings.getPhotoSaveIndividualImages(this)

        // Settings-only configs that the controller reads later (recording/stills),
        // so they don't need to be passed into controller.start(...).
        controller.setVideoProcessingModes(
            noiseReductionMode = AppSettings.getVideoNoiseReductionMode(this),
            distortionCorrectionMode = AppSettings.getVideoDistortionCorrectionMode(this),
            edgeMode = AppSettings.getVideoEdgeMode(this)
        )

        controller.setDebugConfig(
            enableStereoRecordingLog = AppSettings.getDebugVideoLogEnabled(this),
            stereoLogFramesOnly = AppSettings.getDebugVideoLogFramesOnly(this),
            enablePhotoJsonLog = AppSettings.getDebugPhotoJsonLogEnabled(this),
            enablePhotoSyncToast = AppSettings.getDebugPhotoSyncToastEnabled(this)
        )
        controller.setPhotoSaveIndividualLensImages(saveIndividualLensImages)
        controller.setPrimeUwOnActive(
            AppSettings.getPhotoPrimeUwOnActiveEnabled(this)
        )
    }

    private fun savePrefs() {
        prefs.edit()
            .putBoolean(AppSettings.KEY_EIS_ENABLED, eisEnabled)
            .putBoolean(AppSettings.KEY_RAW_MODE, rawMode)
            .putBoolean(AppSettings.KEY_ZOOM_2X, zoom2xEnabled)
            .apply()
    }

    private fun updateTopBarUi() {
        updateEisButton()
        updateFormatButton()
    }

    private fun currentRequestedRecordSize(): Size = videoResolution
    private fun currentVideoFps(): Int = videoFps
    private fun currentVideoBitrateBps(): Int = videoBitrateBps

    private fun applyVideoConfigToController() {
        controller.setVideoConfig(
            requestedRecordSize = currentRequestedRecordSize(),
            requestedRecordFps = currentVideoFps(),
            videoBitrateBps = currentVideoBitrateBps(),
            eisEnabled = eisEnabled
        )
    }

    // ---------------- main buttons enable/disable ----------------

    private fun updateUi() {
        // 0.76.5: neutral Moto-style palette — no loud green/red. The photo
        // shutter is white, everything else is dark charcoal, and the
        // recording state is a white STOP pill.
        val gray = ContextCompat.getColorStateList(this, R.color.control_disabled)

        fun setEnabledStyle(
            button: com.google.android.material.button.MaterialButton,
            enabled: Boolean,
            tint: Int,
            textColor: Int
        ) {
            button.isEnabled = enabled
            if (enabled) {
                button.backgroundTintList = ContextCompat.getColorStateList(this, tint)
                button.setTextColor(ContextCompat.getColor(this, textColor))
            } else {
                button.backgroundTintList = gray
                button.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            }
        }

        val topEnabled = (!isBusy && !isRecording)
//        setEnabledStyle(binding.btnVideoRes, topEnabled, android.R.color.black, android.R.color.white)
//        setEnabledStyle(binding.btnBitrate, topEnabled, android.R.color.black, android.R.color.white)
//        setEnabledStyle(binding.btnEis, topEnabled, android.R.color.black, android.R.color.white)
//        setEnabledStyle(binding.btnFormat, topEnabled, android.R.color.black, android.R.color.white)
//        setEnabledStyle(binding.btnZoom, topEnabled, android.R.color.black, android.R.color.white)
        setEnabledStyle(binding.btnSettings, topEnabled, R.color.control_dark, android.R.color.white)
        setEnabledStyle(binding.btnEis, topEnabled, R.color.control_dark, android.R.color.white)
        setEnabledStyle(binding.btnFormat, topEnabled, R.color.control_dark, android.R.color.white)
        setEnabledStyle(binding.btnZoom, topEnabled, R.color.control_dark, android.R.color.white)

        // Re-apply EIS strike state after enable/disable styling.
        updateEisButton()

        val torchEnabled = (!isBusy && !isRecording)
        binding.btnTorch.isEnabled = torchEnabled
        if (!torchEnabled) {
            binding.btnTorch.backgroundTintList = gray
            binding.btnTorch.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        } else {
            updateTorchButton()
        }

        // White shutter button, black text — like a real camera app.
        setEnabledStyle(binding.btnPhoto, enabled = (!isBusy && !isRecording), tint = R.color.shutter_white, textColor = android.R.color.black)

        val recordEnabled = !isBusy
        binding.btnRecord.isEnabled = recordEnabled
        binding.btnRecord.text = if (isRecording) "STOP" else "RECORD"
        if (!recordEnabled) {
            binding.btnRecord.backgroundTintList = gray
            binding.btnRecord.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        } else if (isRecording) {
            // Recording: white pill reading STOP (no loud red).
            binding.btnRecord.backgroundTintList = ContextCompat.getColorStateList(this, R.color.shutter_white)
            binding.btnRecord.setTextColor(ContextCompat.getColor(this, android.R.color.black))
        } else {
            binding.btnRecord.backgroundTintList = ContextCompat.getColorStateList(this, R.color.control_dark)
            binding.btnRecord.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        }

        // Muted recording indicator (0.76.5).
        binding.recIndicator.visibility = if (isRecording) View.VISIBLE else View.GONE
    }

    private fun timestampForFilename(): String {
        // ms needed to avoid collisions when shooting multiple times in the same second.
        val sdf = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
        return sdf.format(Date())
    }


    private fun toastOnUi(msg: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    private inline fun onUi(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else runOnUiThread { block() }
    }

    // ---------------- StereoCameraController.Callback (force UI thread) ----------------

    override fun onStatus(msg: String) = onUi { toastOnUi(msg) }

    override fun onError(msg: String) = onUi {
        toastOnUi(msg)
        isRecording = false
        isBusy = false
        binding.stitchProgressCard.visibility = View.GONE
        binding.stitchProgressBar.isIndeterminate = false
        updateUi()
    }

    override fun onFallbackSizeUsed(msg: String) = onUi { toastOnUi(msg) }

    override fun onRecordingStarted() = onUi {
        isBusy = false
        isRecording = true
        binding.stitchProgressCard.visibility = View.GONE
        binding.stitchProgressBar.isIndeterminate = false
        updateUi()
    }

    // 0.77.0: direct SBS mode — the lenses are settling before encoding
    // starts. The record button stays disabled until onRecordingStarted.
    override fun onDirectWarmup() = onUi {
        isBusy = true
        updateUi()
        binding.stitchProgressBar.isIndeterminate = true
        binding.stitchProgressText.text = "Warming up…"
        binding.stitchProgressCard.visibility = View.VISIBLE
    }

    override fun onRecordingStopped() = onUi {
        isBusy = false
        isRecording = false
        updateUi()
    }

    override fun onPhotoCaptureDone() = onUi {
        isBusy = false
        updateUi()
    }

    // 0.76.5: background SBS stitch progress — a slim progress card over
    // the preview, so the wait after recording isn't a mystery anymore.
    // The camera stays usable while the stitch runs in the background.
    override fun onStitchProgress(stage: String, done: Int, total: Int) = onUi {
        binding.stitchProgressCard.visibility = View.VISIBLE
        binding.stitchProgressBar.isIndeterminate = false
        val pct = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
        binding.stitchProgressBar.max = 100
        binding.stitchProgressBar.progress = pct
        binding.stitchProgressText.text = "$stage $pct%"
    }

    override fun onStitchDone(success: Boolean, frames: Int) = onUi {
        binding.stitchProgressBar.progress = 100
        binding.stitchProgressText.text = if (success) {
            "3D video saved ✓"
        } else {
            "Couldn't stitch — separate videos saved"
        }
        binding.stitchProgressCard.postDelayed(
            { binding.stitchProgressCard.visibility = View.GONE },
            3000
        )
    }

    // 0.78.2: live preview color match — the controller worked out the
    // ultra eye's gain from the lenses' own AWB + exposure; hand it to
    // the GL view (wide eye stays the un-gained reference).
    override fun onPreviewColorGains(r: Float, g: Float, b: Float) = onUi {
        binding.sbsPreview.setUltraColorGain(r, g, b)
    }

    // 0.79.1: GPU SBS video — hand the recorder the preview view's
    // shared EGL context, frame signal and latest render state, so it
    // can render the same SBS frame into the encoder on its own thread.
    override fun gpuRecordSource(): com.example.duallens3dcamera.preview.SbsPreviewView.GpuRecordSource =
        binding.sbsPreview.gpuRecordSource()

    // 0.79.5: the controller spent its preview retries and the preview
    // is black. Restart the camera ourselves (bounded) instead of
    // making her reopen the app — the launch race usually clears on
    // the next try. Only after the bound is spent do we show the error.
    override fun onPreviewStartFailed() = onUi {
        if (previewAutoRestarts < 2 && !isFinishing && !isDestroyed) {
            previewAutoRestarts++
            Log.i("MainActivity", "Preview start failed — auto-restarting camera ($previewAutoRestarts/2)")
            controller.stop()
            previewConfig = null
            sbsPreviewActive = false
            binding.sbsPreview.postDelayed({ maybeStartCamera() }, 500L)
        } else {
            toastOnUi("Preview couldn't start — please reopen the app.")
            isRecording = false
            isBusy = false
            binding.stitchProgressCard.visibility = View.GONE
            binding.stitchProgressBar.isIndeterminate = false
            updateUi()
        }
    }

    // 0.78.0: the phone refused the ultrawide preview stream. The
    // controller already retried the ultra session reader-only, so photos
    // and video still work — the preview just goes back to the wide lens
    // alone, and the setting stays on for next time (a retry may work
    // after a restart, and the user can also turn SBS preview off).
    override fun onSbsPreviewFallback() = onUi {
        sbsPreviewActive = false
        binding.sbsPreview.setSbsMode(false, sbsViewCross)
        applyPreviewAspectRatio()
        toastOnUi("SBS preview unavailable — showing wide lens only")
    }

    // ---------------- Preview aspect ----------------
    // 0.78.0: no TextureView transform anymore — the GL shader applies
    // each texture's own transform matrix per frame. This only sizes the
    // view so the shader's half-viewports don't stretch: SBS is two
    // 4:3 eyes side by side (8:3); single-wide is one 4:3 eye (4:3 in
    // landscape, 3:4 in portrait, from the real preview size).
    private fun applyPreviewAspectRatio() {
        val cfg = previewConfig
        val params =
            binding.sbsPreview.layoutParams as? ConstraintLayout.LayoutParams ?: return
        params.dimensionRatio = if (sbsPreviewActive) {
            "8:3"
        } else if (cfg == null) {
            "4:3"
        } else if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            "${cfg.previewSize.width}:${cfg.previewSize.height}"
        } else {
            "${cfg.previewSize.height}:${cfg.previewSize.width}"
        }
        binding.sbsPreview.layoutParams = params
    }
}

package com.example.duallens3dcamera.preview

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.view.Surface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GPU side-by-side preview (0.78.0).
 *
 * Why this exists: the old preview was one TextureView fed by the wide lens
 * only, and the ultrawide ran headless. A CPU SBS preview would copy every
 * frame off the GPU, warp it in OpenCV, and push it back — the same work
 * Direct SBS recording already does, and the reason it is capped and
 * choppy. This view does the SBS composite on the GPU instead: each lens
 * feeds its own SurfaceTexture, which *is* a GPU texture
 * (GL_TEXTURE_EXTERNAL_OES), and the fragment shader just samples the two
 * textures side by side. No frame ever leaves the GPU, no per-frame CPU
 * copy, so it runs at the camera's preview rate.
 *
 * Color match (0.78.2): the wide is warmer/darker than the ultrawide
 * (independent AWB/AE), and the controller now feeds the ultra eye a
 * live per-channel gain computed from the two lenses' own AWB gains +
 * exposure (see StereoCameraController.onPreviewCaptureResult). It is a
 * gain-only match — the full mean/std transfer the photos get is
 * heavier and needs CPU frames, so the preview will be close, not
 * identical, to a saved matched photo. If the camera reports no gains,
 * the gain stays 1.0 and the preview is un-matched, as before.
 *
 * GPU video recording (0.79.0, reworked in 0.79.1): the same SBS frame
 * is recorded straight into a video encoder — but NOT by drawing twice
 * on this view's GL thread. 0.79.0 did that (makeCurrent to the encoder
 * surface, draw, swap, restore) and it broke the preview: the encoder
 * draw could stall the GL thread and the ultra eye froze when
 * recording started. Since 0.79.1 the encoder is rendered on its own
 * thread in GpuSbsRecorder, in a second EGL context that *shares* this
 * view's context (see [gpuRecordSource] — the EGL14 handle is captured
 * in onSurfaceCreated), so the screen thread never touches the encoder
 * and a stuck encoder cannot freeze the preview. This view only publishes a per-frame
 * snapshot ([latestRenderState]) and a frame signal ([frameSignal]);
 * the recorder thread samples the same (shared) textures and draws
 * with the same shader. The screen config is back to GLSurfaceView's
 * default — 0.79.0's recordable config chooser could pick a
 * pbuffer-only config (it never asked for a window surface) and made
 * the preview itself chaotic.
 *
 * Warp (0.78.1): the ultrawide eye is warped into the wide's geometry
 * with a fixed centered scale — the ultra is sampled from its central
 * 66% (ULTRA_WARP), the same overlap fraction the photo aligner's
 * fallback uses (StereoSbs.fallbackUltraOverlapFraction = 0.66). It is
 * also what the sensor geometry says: wide tan(half-FOV) 0.74 vs ultra
 * 1.09, ratio 1.48, so the wide covers 1/1.48 = 0.68 of the ultra width.
 * A measured per-scene affine (Direct warm-up) would refine this by a
 * few percent plus a small translation; the fixed scale is the right
 * preview trade. The warp is isotropic, so it commutes with the
 * rotation below and can stay after the ST matrix.
 *
 * Rotation (0.78.1 — the 0.78.0 bug): a SurfaceTexture's transform
 * matrix does NOT rotate the image for the display — it only flips /
 * crops. The camera texture is portrait-effective (the same reason the
 * old TextureView code needed a manual postRotate), so 0.78.0 showed
 * both eyes sideways. The activity now passes the display rotation in
 * [setDisplayRotation], and the vertex shader rotates the sampling
 * coordinates by the same angle the old TextureView recipe used:
 * ROTATION_0 -> 0, ROTATION_90 -> -90, ROTATION_180 -> 180,
 * ROTATION_270 -> +90 (sampling coords rotate opposite to the image).
 * If the sign ever proves backwards on-device, the image comes out
 * upside-down (180 off), which is unmistakable — flip the signs.
 * The view itself must be aspect-matched by the activity (8:3 for SBS,
 * 4:3 / 3:4 for single-wide), or the two half-viewports stretch.
 *
 * Lifecycle: textures are created on the GL thread in onSurfaceCreated
 * and handed to the activity through [Listener.onPreviewTexturesReady].
 * The EGL context is NOT preserved across pause, so a resume recreates
 * the textures and the listener fires again — the activity restarts the
 * camera then, exactly like the old TextureView flow. The activity must
 * call [GLSurfaceView.onResume] / [GLSurfaceView.onPause] and must stop
 * the camera *before* onPause, so no session is still writing into a
 * texture whose GL context is going away.
 */
class SbsPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    interface Listener {
        fun onPreviewTexturesReady(wide: SurfaceTexture, ultra: SurfaceTexture)
    }

    private val renderer = SbsRenderer()

    /** Set by the activity before the camera starts; also the renderer's mode. */
    @Volatile var sbsEnabled: Boolean = true
    /** Cross-eyed (ultra left) vs parallel (wide left). GL-side only — the
     *  activity can flip this live with no camera restart. */
    @Volatile var sbsCross: Boolean = true
    /** Frame pairing (0.79.7): when true, the preview draws only once
     *  BOTH eyes have a fresh frame since the last draw, so the two
     *  eyes stay in step when the camera wiggles instead of one eye
     *  lagging the other by a frame. Set by the activity from the
     *  "Pair frames" setting; GL-side only, no camera restart. */
    @Volatile var pairFrames: Boolean = true
    /** Sampling-coordinate rotation, radians (see header). Set by the
     *  activity from the display rotation; GL-side only, no restart. */
    @Volatile var texRotationRad: Float = 0f
    /** Live color-match gain for the ultra eye, relative to the wide eye.
     *  0.79.9: measured from the preview framebuffer itself (see
     *  measureAndUpdateColorMatch), not from AWB gains — the lenses
     *  report near-equal AWB gains, and the visible warm/cool
     *  difference lives in the sensors' color science. */
    @Volatile var ultraColorGain: FloatArray = floatArrayOf(1f, 1f, 1f)
    /** Live color-match offset for the ultra eye (0–1 color space),
     *  paired with [ultraColorGain]: out = in * gain + offset. */
    @Volatile var ultraColorOffset: FloatArray = floatArrayOf(0f, 0f, 0f)
    /** Same pair for the wide eye — used only when the color-match
     *  mode is "ultra" (match the wide to the ultrawide); identity
     *  otherwise. */
    @Volatile var wideColorGain: FloatArray = floatArrayOf(1f, 1f, 1f)
    @Volatile var wideColorOffset: FloatArray = floatArrayOf(0f, 0f, 0f)
    /** Which lens the other is matched to ("wide" = match ultra to
     *  wide, "ultra" = match wide to ultra, "off" = no correction).
     *  Set by the activity from the "SBS video color match" setting;
     *  GL-side only, no camera restart. 0.79.9: the same setting that
     *  used to drive only Direct/stitched video now drives the live
     *  preview and GPU video too. */
    @Volatile var colorMatchMode: String = "wide"

    /** Set the color-match mode; resets all gains to identity when the
     *  mode changes so the measured feedback re-converges cleanly. */
    fun applyColorMatchMode(mode: String) {
        val m = mode.lowercase()
        if (m == colorMatchMode) return
        colorMatchMode = m
        ultraColorGain = floatArrayOf(1f, 1f, 1f)
        ultraColorOffset = floatArrayOf(0f, 0f, 0f)
        wideColorGain = floatArrayOf(1f, 1f, 1f)
        wideColorOffset = floatArrayOf(0f, 0f, 0f)
        requestRender()
    }

    var listener: Listener? = null

    init {
        setEGLContextClientVersion(2)
        // 0.79.1: the screen config is GLSurfaceView's default again.
        // 0.79.0 forced a recordable config here and it could pick a
        // pbuffer-only config (no window surface type was requested),
        // which made the preview itself chaotic.
        setRenderer(renderer)
        // WHEN_DIRTY: a frame is drawn only when a lens delivers one, via
        // the SurfaceTextures' OnFrameAvailableListeners -> requestRender().
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    // ---- GPU video recording source (0.79.1) ----
    /** One frame's worth of render state, published by the GL thread
     *  after each screen draw for the recorder thread to consume. The
     *  matrices are copies; the texture ids are valid in any context
     *  sharing this view's context. */
    data class RenderState(
        val wideTexId: Int,
        val ultraTexId: Int,
        val stMatrixWide: FloatArray,
        val stMatrixUltra: FloatArray,
        val rotationRad: Float,
        val ultraGain: FloatArray,
        /** 0.79.9: per-eye color-match offsets (0–1), paired with the
         *  gains — the GPU recorder applies the same correction. */
        val ultraOffset: FloatArray,
        val wideGain: FloatArray,
        val wideOffset: FloatArray,
        val sbsEnabled: Boolean,
        val sbsCross: Boolean,
        val surfaceW: Int,
        val surfaceH: Int,
        /** Wide frame's camera timestamp (ns), for encoder PTS. */
        val wideTsNs: Long
    )

    /** Everything GpuSbsRecorder needs to render the same SBS frame into
     *  the encoder on its own thread. */
    data class GpuRecordSource(
        /** The view's EGL context, as an EGL14 handle — captured on the
         *  GL thread with EGL14.eglGetCurrentContext() (see
         *  onSurfaceCreated). Null until the GL surface is created. */
        val sharedContext: android.opengl.EGLContext?,
        /** Signalled (notifyAll) after every screen draw. */
        val frameSignal: Any,
        /** Monotonic frame counter, bumped after every screen draw. */
        val frameSequence: () -> Long,
        /** The latest published [RenderState], or null before the first draw. */
        val latestState: () -> RenderState?
    )

    /** Per-channel (RGB, 0–255) mean/std of a framebuffer patch, for
     *  the measured color match. (A data class can't live inside the
     *  inner renderer class, so it sits here on the view.) */
    private data class ChannelStats(val mean: DoubleArray, val std: DoubleArray)

    /** The EGL14 handle of this view's context, captured on the GL
     *  thread in onSurfaceCreated (the context is current there).
     *  Null until the GL surface is created (and recreated on resume).
     *  Note: the javax EGL10 EGLContext type GLSurfaceView's factory
     *  returns is a *different* class from android.opengl.EGLContext
     *  and cannot be cast — the EGL14 current-context query is the
     *  only way to get the shareable handle. */
    @Volatile var sharedEglContext: android.opengl.EGLContext? = null
        private set

    /** Signalled after every screen draw; the recorder thread waits on it. */
    val frameSignal = Object()
    @Volatile private var frameSequence: Long = 0L
    /** Latest published render state (GL thread writes, recorder reads). */
    @Volatile var latestRenderState: RenderState? = null
        private set

    fun gpuRecordSource(): GpuRecordSource = GpuRecordSource(
        sharedContext = sharedEglContext,
        frameSignal = frameSignal,
        frameSequence = { frameSequence },
        latestState = { latestRenderState }
    )

    /** True once onSurfaceCreated has made the two SurfaceTextures. */
    val hasTextures: Boolean get() = renderer.wideSurfaceTexture != null

    /** The current textures, or null if the GL surface has not been created yet. */
    fun surfaceTextures(): Pair<SurfaceTexture, SurfaceTexture>? {
        val w = renderer.wideSurfaceTexture ?: return null
        val u = renderer.ultraSurfaceTexture ?: return null
        return w to u
    }

    /** Switch SBS on/off live (e.g. after the ultra-preview fallback). */
    fun setSbsMode(enabled: Boolean, cross: Boolean) {
        sbsEnabled = enabled
        sbsCross = cross
        requestRender()
    }

    /** Set the ultra eye's color-match gain (R, G, B), relative to the
     *  wide eye. Thread-safe; just feeds the next frame. */
    fun setUltraColorGain(r: Float, g: Float, b: Float) {
        ultraColorGain = floatArrayOf(r, g, b)
        requestRender()
    }

    /** Tell the view the display rotation (Surface.ROTATION_*). The
     *  sampling coordinates rotate the opposite way to the image, by the
     *  same angle the old TextureView recipe post-rotated: 0 / -90 / 180 /
     *  +90 degrees for rotations 0 / 90 / 180 / 270. */
    fun setDisplayRotation(rotation: Int) {
        texRotationRad = Math.toRadians(when (rotation) {
            Surface.ROTATION_0 -> 0.0
            Surface.ROTATION_90 -> -90.0
            Surface.ROTATION_180 -> 180.0
            Surface.ROTATION_270 -> 90.0
            else -> 0.0
        }).toFloat()
        requestRender()
    }

    private inner class SbsRenderer : Renderer {
        @Volatile var wideSurfaceTexture: SurfaceTexture? = null
        @Volatile var ultraSurfaceTexture: SurfaceTexture? = null

        private var wideTexId = 0
        private var ultraTexId = 0
        private var program = 0
        private var aPosition = 0
        private var aTexCoord = 0
        private var uStMatrix = 0
        private var uRotCos = 0
        private var uRotSin = 0
        private var uWarpMatrix = 0
        private var uColorGain = 0
        private var uColorOffset = 0

        private var surfaceW = 0
        private var surfaceH = 0

        // Set by the OnFrameAvailableListeners; consumed on the GL thread.
        @Volatile private var wideFrameAvailable = false
        @Volatile private var ultraFrameAvailable = false
        /** 0.79.8: pairing wait — a delayed render posted while waiting
         *  for the other eye's frame, so a stalled eye can't freeze the
         *  preview. At most one may be pending at a time; the runnable
         *  clears the flag when it runs. */
        @Volatile private var pairWaitPosted = false
        private val pairWaitRunnable = Runnable {
            pairWaitPosted = false
            requestRender()
        }

        /** Called by the frame-available listeners (any thread) when one
         *  eye has a fresh frame. 0.79.8: frame pairing lives HERE, not
         *  in onDrawFrame — 0.79.7 gated the draw inside onDrawFrame,
         *  but GLSurfaceView swaps after onDrawFrame even when nothing
         *  was drawn, and the back buffer is undefined after a swap, so
         *  a skipped draw showed up as a black frame (a black
         *  flicker, preview only — the recorder only draws from
         *  published states, so it never saw the black frames).
         *  Gating the *request* instead means onDrawFrame only runs
         *  when there is a real frame to draw. When pairing is on, a
         *  draw is requested only once BOTH eyes are fresh; if only one
         *  eye is fresh, a single delayed render is posted so a stalled
         *  eye still gets drawn after a short wait. */
        private fun onEyeFrameAvailable() {
            if (!pairFrames || !sbsEnabled || ultraSurfaceTexture == null) {
                requestRender()
                return
            }
            if (wideFrameAvailable && ultraFrameAvailable) {
                // Both fresh: draw now, and cancel any pending wait —
                // its draw would just repeat this frame.
                if (pairWaitPosted) {
                    removeCallbacks(pairWaitRunnable)
                    pairWaitPosted = false
                }
                requestRender()
            } else if (!pairWaitPosted) {
                pairWaitPosted = true
                postDelayed(pairWaitRunnable, 15)
            }
        }

        // 0.79.2: the previous SurfaceTexture pair, waiting to be
        // released. The old pair must NOT be released in
        // onSurfaceCreated (see below) — a camera open still in flight
        // with the old surfaces would die with "Surface was abandoned"
        // (the 0.79.1 crash). They are released only after the new
        // textures have demonstrably gone live (first successful
        // updateTexImage of the new wide texture), which proves the
        // camera is running on the new surfaces.
        private var pendingReleaseWide: SurfaceTexture? = null
        private var pendingReleaseUltra: SurfaceTexture? = null

        private fun releasePendingTextures() {
            val oldWide = pendingReleaseWide
            val oldUltra = pendingReleaseUltra
            pendingReleaseWide = null
            pendingReleaseUltra = null
            try { oldWide?.release() } catch (_: Exception) {}
            try { oldUltra?.release() } catch (_: Exception) {}
        }

        private val stMatrixWide = FloatArray(16)
        private val stMatrixUltra = FloatArray(16)

        // Full-screen quad: positions + tex coords (GL tex origin bottom-left).
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                // x, y, s, t per vertex (triangle strip)
                put(floatArrayOf(
                    -1f, -1f, 0f, 0f,
                     1f, -1f, 1f, 0f,
                    -1f,  1f, 0f, 1f,
                     1f,  1f, 1f, 1f
                ))
                position(0)
            }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // 0.79.1: capture the EGL14 handle of this context (it is
            // current on this thread) so the GPU recorder thread can
            // share it. The EGL context is not preserved across pause,
            // so this runs again on every resume.
            try {
                val ctx = EGL14.eglGetCurrentContext()
                sharedEglContext = if (ctx != null && ctx != EGL14.EGL_NO_CONTEXT) ctx else null
            } catch (_: Exception) {
                sharedEglContext = null
            }
            program = buildProgram()
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
            uRotCos = GLES20.glGetUniformLocation(program, "uRotCos")
            uRotSin = GLES20.glGetUniformLocation(program, "uRotSin")
            uWarpMatrix = GLES20.glGetUniformLocation(program, "uWarpMatrix")
            uColorGain = GLES20.glGetUniformLocation(program, "uColorGain")
            uColorOffset = GLES20.glGetUniformLocation(program, "uColorOffset")

            val texIds = IntArray(2)
            GLES20.glGenTextures(2, texIds, 0)
            wideTexId = texIds[0]
            ultraTexId = texIds[1]
            for (id in texIds) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
                GLES20.glTexParameterf(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat()
                )
                GLES20.glTexParameterf(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat()
                )
                GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
                )
                GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
                )
            }

            wideFrameAvailable = false
            ultraFrameAvailable = false
            // 0.78.2: the EGL context is not preserved across pause, so
            // onSurfaceCreated runs again on every resume — and every run
            // used to create two fresh SurfaceTextures without releasing
            // the old ones. Each leaked texture holds a BufferQueue, so
            // repeated launches could exhaust buffers and crash.
            // 0.79.2: the old pair is NOT released here any more. Doing
            // it here abandoned the old surfaces while a camera open was
            // still in flight with them ("Surface was abandoned", the
            // 0.79.1 crash). Instead they are parked in
            // pendingRelease* and released only after the new textures
            // have gone live (first successful updateTexImage below).
            // If the camera never delivers a frame with the new pair,
            // the pending pair is overwritten on the next recreation —
            // a bounded, tiny leak, far better than a crash.
            pendingReleaseWide = wideSurfaceTexture
            pendingReleaseUltra = ultraSurfaceTexture
            wideSurfaceTexture = null
            ultraSurfaceTexture = null
            val wideSt = SurfaceTexture(wideTexId).apply {
                setOnFrameAvailableListener {
                    wideFrameAvailable = true
                    onEyeFrameAvailable()
                }
            }
            val ultraSt = SurfaceTexture(ultraTexId).apply {
                setOnFrameAvailableListener {
                    ultraFrameAvailable = true
                    onEyeFrameAvailable()
                }
            }
            wideSurfaceTexture = wideSt
            ultraSurfaceTexture = ultraSt
            // The activity starts the camera from this callback (on the UI
            // thread — the GL thread must never touch the controller).
            post { listener?.onPreviewTexturesReady(wideSt, ultraSt) }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceW = width
            surfaceH = height
        }

        override fun onDrawFrame(gl: GL10?) {
            val wideSt = wideSurfaceTexture ?: return
            val ultraSt = ultraSurfaceTexture
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (program == 0 || surfaceW <= 0 || surfaceH <= 0) return

            // Pull the newest frame from each lens that has one. A texture
            // with no new frame keeps showing its last frame — the two eyes
            // are unsynchronised by design (no hardware sync on this phone),
            // so one eye may lag the other by a frame; that is fine for a
            // framing preview.
            if (wideFrameAvailable) {
                wideFrameAvailable = false
                try { wideSt.updateTexImage() } catch (_: Exception) { return }
                // 0.79.2: a frame just landed in the NEW wide texture,
                // so the camera is running on the new surfaces — the
                // previous pair is safe to release now.
                releasePendingTextures()
            }
            if (ultraSt != null && ultraFrameAvailable) {
                ultraFrameAvailable = false
                try { ultraSt.updateTexImage() } catch (_: Exception) { /* keep wide */ }
            }
            try { wideSt.getTransformMatrix(stMatrixWide) } catch (_: Exception) { return }
            if (ultraSt != null) {
                try { ultraSt.getTransformMatrix(stMatrixUltra) } catch (_: Exception) { /* keep wide */ }
            }

            GLES20.glUseProgram(program)
            quad.position(0)
            GLES20.glVertexAttribPointer(
                aPosition, 2, GLES20.GL_FLOAT, false, 16, quad
            )
            GLES20.glEnableVertexAttribArray(aPosition)
            quad.position(2)
            GLES20.glVertexAttribPointer(
                aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad
            )
            GLES20.glEnableVertexAttribArray(aTexCoord)

            drawSbsFrame(surfaceW, surfaceH)
            // 0.79.9: measure the just-drawn frame and update the color
            // match (throttled to once every 2 s; see
            // measureAndUpdateColorMatch). The readback happens before
            // the swap, while the back buffer still holds this frame.
            measureAndUpdateColorMatch()
            // 0.79.1: publish this frame for the GPU recorder thread
            // (shared context) and wake it. The screen draw is the only
            // GL work this thread does — recording can no longer stall it.
            val wideTs = try { wideSt.timestamp } catch (_: Exception) { -1L }
            latestRenderState = RenderState(
                wideTexId = wideTexId,
                ultraTexId = ultraTexId,
                stMatrixWide = stMatrixWide.copyOf(),
                stMatrixUltra = stMatrixUltra.copyOf(),
                rotationRad = texRotationRad,
                ultraGain = ultraColorGain.copyOf(),
                ultraOffset = ultraColorOffset.copyOf(),
                wideGain = wideColorGain.copyOf(),
                wideOffset = wideColorOffset.copyOf(),
                sbsEnabled = sbsEnabled,
                sbsCross = sbsCross,
                surfaceW = surfaceW,
                surfaceH = surfaceH,
                wideTsNs = wideTs
            )
            frameSequence++
            synchronized(frameSignal) { frameSignal.notifyAll() }
        }

        /** When the color match was last measured (nanoTime). */
        private var lastColorMeasureNs: Long = 0L
        /** 0.79.10: measure at most once every 4 s — the match only
         *  needs to follow the light, a readback stalls the GL pipeline
         *  briefly, and (0.79.9's lesson) a visible color step every
         *  couple of seconds is distracting. The update below also
         *  moves only part-way toward the target each time, so the
         *  colors drift gently instead of stepping. */
        private val colorMeasureIntervalNs: Long = 4_000_000_000L
        /** Reusable readback buffer for one patch (120x90 RGBA). */
        private val measureBuf = java.nio.ByteBuffer.allocateDirect(120 * 90 * 4)
            .order(java.nio.ByteOrder.nativeOrder())

        /**
         * 0.79.9 measured color match, smoothed in 0.79.10. Reads
         * patches of each eye's half from the just-drawn framebuffer
         * and works out the gain + offset that makes the corrected
         * eye's output statistics match the reference eye's — the same
         * mean/std transfer StereoSbs.computeColorMatchParams does for
         * photos and stitched video, but from the live preview pixels
         * instead of AWB gains (which the lenses report near-equal, so
         * the old 0.78.2 push did nothing). The measurement is of the
         * *output* (the current gain is already applied), so the update
         * is a feedback step: it backs the input statistics out of the
         * output ones and re-solves for the gain/offset that would
         * match the reference. 0.79.9 jumped straight to the new
         * values every 2 s, which was distracting — sometimes good,
         * sometimes terrible, because one small central patch is at the
         * mercy of whatever object happens to sit in it. Since 0.79.10
         * the statistics pool three spread-out patches per eye (so no
         * single object dominates), and the gain/offset move only 30%
         * of the way toward the target per measurement, so the colors
         * drift gently and never step. Runs on the GL thread, after
         * the draw and before the swap.
         */
        private fun measureAndUpdateColorMatch() {
            if (colorMatchMode == "off") return
            if (!sbsEnabled) return
            if (ultraSurfaceTexture == null) return
            val nowNs = System.nanoTime()
            if (nowNs - lastColorMeasureNs < colorMeasureIntervalNs) return
            lastColorMeasureNs = nowNs
            try {
                val halfW = surfaceW / 2
                if (halfW <= 0 || surfaceH <= 0) return
                val wideStats = eyeStats(isWide = true, halfW) ?: return
                val ultraStats = eyeStats(isWide = false, halfW) ?: return
                // Skip if the scene is nearly black (camera covered).
                if (wideStats.mean.all { it < 8.0 } && ultraStats.mean.all { it < 8.0 }) return
                when (colorMatchMode) {
                    "ultra" -> {
                        val (g, o) = updateEyeCorrection(
                            wideStats, ultraStats, wideColorGain, wideColorOffset
                        )
                        wideColorGain = g
                        wideColorOffset = o
                    }
                    else -> { // "wide" (default) and anything unknown
                        val (g, o) = updateEyeCorrection(
                            ultraStats, wideStats, ultraColorGain, ultraColorOffset
                        )
                        ultraColorGain = g
                        ultraColorOffset = o
                    }
                }
            } catch (_: Exception) {
                // Never let a color measurement break the preview.
            }
        }

        /** Pooled per-channel (RGB, 0–255) mean/std over three patches
         *  spread across one eye's half (centre, upper-left,
         *  lower-right), so a single odd object can't dominate the
         *  statistics. OpenGL's origin is bottom-left, but the patches
         *  are spread vertically too, so the flip does not matter. */
        private fun eyeStats(isWide: Boolean, halfW: Int): ChannelStats? {
            // Which half this eye is on (cross: ultra left, wide right;
            // parallel: wide left, ultra right).
            val halfStartX = if (sbsCross) {
                if (isWide) halfW else 0
            } else {
                if (isWide) 0 else halfW
            }
            val pw = minOf(120, halfW)
            val ph = minOf(90, surfaceH)
            if (pw <= 0 || ph <= 0) return null
            val sum = DoubleArray(3)
            val sumSq = DoubleArray(3)
            var n = 0
            val spots = arrayOf(0.5f to 0.5f, 0.25f to 0.75f, 0.75f to 0.25f)
            for ((fx, fy) in spots) {
                val cx = halfStartX + (fx * halfW).toInt()
                val cy = (fy * surfaceH).toInt()
                val x = (cx - pw / 2).coerceIn(halfStartX, halfStartX + halfW - pw)
                val y = (cy - ph / 2).coerceIn(0, surfaceH - ph)
                n += accumulatePatch(sum, sumSq, x, y, pw, ph)
            }
            if (n <= 0) return null
            val mean = DoubleArray(3) { sum[it] / n }
            val std = DoubleArray(3) {
                val variance = (sumSq[it] / n) - mean[it] * mean[it]
                kotlin.math.sqrt(variance.coerceAtLeast(0.0))
            }
            return ChannelStats(mean, std)
        }

        /** Read one framebuffer patch and add its pixels to the running
         *  per-channel sums. Returns the pixel count added. */
        private fun accumulatePatch(
            sum: DoubleArray, sumSq: DoubleArray, x: Int, y: Int, w: Int, h: Int
        ): Int {
            if (w <= 0 || h <= 0) return 0
            measureBuf.clear()
            GLES20.glReadPixels(x, y, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, measureBuf)
            val n = w * h
            var i = 0
            while (i < n) {
                val base = i * 4
                for (c in 0..2) {
                    val v = (measureBuf.get(base + c).toInt() and 0xFF).toDouble()
                    sum[c] += v
                    sumSq[c] += v * v
                }
                i++
            }
            return n
        }

        /** Feedback update for one eye (see measureAndUpdateColorMatch).
         *  out = in * gain + offset, so with the current (gain, offset)
         *  the input statistics are mean_in = (m - o*255)/gain and
         *  std_in = s/gain; the gain/offset that would make this eye's
         *  output match the reference eye's are gain' = gain *
         *  (s_ref/s_src) and offset' = m_ref - (s_ref/s_src) *
         *  (m_src - o*255), in 0–1 offset units. Gain clamped
         *  [0.25, 4.0] and near-flat channels shifted, not scaled —
         *  the same guards as StereoSbs.computeColorMatchParams.
         *  0.79.10: the correction is NOT applied in one jump (that
         *  made the colors step visibly every measurement, sometimes
         *  good, sometimes terrible). It moves only 30% of the way
         *  toward the target per measurement, so the colors drift
         *  gently and never step; and it only moves at all when the
         *  target is significantly different, so measurement noise
         *  does not make the colors jitter. */
        private fun updateEyeCorrection(
            srcStats: ChannelStats, refStats: ChannelStats,
            curGain: FloatArray, curOffset: FloatArray
        ): Pair<FloatArray, FloatArray> {
            val targetGain = FloatArray(3)
            val targetOffset = FloatArray(3)
            for (c in 0..2) {
                val mS = srcStats.mean[c]
                val sS = srcStats.std[c]
                val mR = refStats.mean[c]
                val sR = refStats.std[c]
                val ratio = if (sS > 1.0) (sR / sS).coerceIn(0.25, 4.0) else 1.0
                val g = (curGain[c].toDouble() * ratio).coerceIn(0.25, 4.0)
                val o255 = mR - ratio * (mS - curOffset[c].toDouble() * 255.0)
                val o = (o255 / 255.0).coerceIn(-0.5, 0.5)
                targetGain[c] = g.toFloat()
                targetOffset[c] = o.toFloat()
            }
            val needsMove = (0..2).any {
                kotlin.math.abs(targetGain[it] - curGain[it]) > 0.03f ||
                    kotlin.math.abs(targetOffset[it] - curOffset[it]) > 0.015f
            }
            if (!needsMove) return curGain to curOffset
            val k = 0.3f
            val newGain = FloatArray(3) { curGain[it] + (targetGain[it] - curGain[it]) * k }
            val newOffset = FloatArray(3) { curOffset[it] + (targetOffset[it] - curOffset[it]) * k }
            return newGain to newOffset
        }

        /** Draw one SBS frame (or the single wide eye) into the current
         *  EGL surface at the given size. Used for the screen and, when
         *  recording, for the encoder surface. */
        private fun drawSbsFrame(targetW: Int, targetH: Int) {
            val ultraSt = ultraSurfaceTexture
            val sbs = sbsEnabled && ultraSt != null
            if (!sbs) {
                // Single wide lens, full view.
                GLES20.glViewport(0, 0, targetW, targetH)
                drawEye(wideTexId, stMatrixWide, IDENTITY_WARP)
                return
            }
            // SBS: two half-viewports. CROSS = ultra left / wide right;
            // PARALLEL = wide left / ultra right. The ultra eye is warped
            // into the wide's geometry (ULTRA_WARP); the wide eye is the
            // reference and stays identity.
            val halfW = targetW / 2
            val (leftTex, leftMatrix, leftWarp, rightTex, rightMatrix, rightWarp) = if (sbsCross) {
                Quad(ultraTexId, stMatrixUltra, ULTRA_WARP, wideTexId, stMatrixWide, IDENTITY_WARP)
            } else {
                Quad(wideTexId, stMatrixWide, IDENTITY_WARP, ultraTexId, stMatrixUltra, ULTRA_WARP)
            }
            GLES20.glViewport(0, 0, halfW, targetH)
            drawEye(leftTex, leftMatrix, leftWarp)
            GLES20.glViewport(halfW, 0, targetW - halfW, targetH)
            drawEye(rightTex, rightMatrix, rightWarp)
        }

        /** Draw the bound eye texture across the current viewport. */
        private fun drawEye(texId: Int, stMatrix: FloatArray, warp: FloatArray) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            val rot = texRotationRad
            GLES20.glUniform1f(uRotCos, kotlin.math.cos(rot))
            GLES20.glUniform1f(uRotSin, kotlin.math.sin(rot))
            GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)
            GLES20.glUniformMatrix3fv(uWarpMatrix, 1, false, warp, 0)
            // 0.79.9: measured color match — each eye gets the gain and
            // offset the framebuffer measurement worked out (see
            // measureAndUpdateColorMatch); the reference eye stays at
            // identity. The old AWB-gain push from the controller is
            // disabled (it did nothing: the lenses report near-equal
            // gains, and the visible difference lives in the sensors'
            // color science, not the white balance).
            val (gain, offset) = if (texId == ultraTexId) {
                ultraColorGain to ultraColorOffset
            } else {
                wideColorGain to wideColorOffset
            }
            GLES20.glUniform3f(uColorGain, gain[0], gain[1], gain[2])
            GLES20.glUniform3f(uColorOffset, offset[0], offset[1], offset[2])
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun buildProgram(): Int {
            val vertexShader = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                uniform mat4 uStMatrix;
                uniform float uRotCos;
                uniform float uRotSin;
                uniform mat3 uWarpMatrix;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    // Rotate the sampling coordinates about the texture
                    // centre *before* the ST matrix: the ST matrix only
                    // flips/crops, it does not rotate for the display.
                    vec2 c = aTexCoord - vec2(0.5);
                    vec2 r = vec2(
                        c.x * uRotCos - c.y * uRotSin,
                        c.x * uRotSin + c.y * uRotCos
                    );
                    vec4 st = uStMatrix * vec4(r + vec2(0.5), 0.0, 1.0);
                    vec3 warped = uWarpMatrix * vec3(st.xy, 1.0);
                    vTexCoord = warped.xy / warped.z;
                }
            """.trimIndent()
            // OES external textures need the extension declaration; the
            // sampler must be samplerExternalOES, not sampler2D.
            val fragmentShader = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES sTexture;
                uniform vec3 uColorGain;
                uniform vec3 uColorOffset;
                void main() {
                    vec4 c = texture2D(sTexture, vTexCoord);
                    gl_FragColor = vec4(clamp(c.rgb * uColorGain + uColorOffset, 0.0, 1.0), c.a);
                }
            """.trimIndent()
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
            if (vs == 0 || fs == 0) return 0
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Log.e(TAG, "SBS preview program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
                GLES20.glDeleteProgram(prog)
                return 0
            }
            return prog
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e(TAG, "SBS preview shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }
    }

    private data class Quad(
        val leftTex: Int, val leftMatrix: FloatArray, val leftWarp: FloatArray,
        val rightTex: Int, val rightMatrix: FloatArray, val rightWarp: FloatArray
    )

    companion object {
        private const val TAG = "SbsPreviewView"
        /** 3x3 identity (for the identity, row- vs column-major makes no
         *  difference to glUniformMatrix3fv with transpose=false). */
        private val IDENTITY_WARP = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
        /** Ultra -> wide geometry, column-major (glUniformMatrix3fv,
         *  transpose=false): sample the central 66% of the ultra frame,
         *  scaled up to the wide frame. a = 0.66, offset = 0.5*(1-a) =
         *  0.17 on each axis. */
        private val ULTRA_WARP = floatArrayOf(
            0.66f, 0f, 0f,
            0f, 0.66f, 0f,
            0.17f, 0.17f, 1f
        )
        private val IDENTITY_GAIN = floatArrayOf(1f, 1f, 1f)
    }
}

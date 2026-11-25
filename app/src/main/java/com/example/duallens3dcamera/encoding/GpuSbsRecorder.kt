package com.example.duallens3dcamera.encoding

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Size
import android.view.Surface
import com.example.duallens3dcamera.media.MediaStoreUtils
import com.example.duallens3dcamera.preview.SbsPreviewView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GPU side-by-side video recording (0.79.0, reworked in 0.79.1).
 *
 * Why this exists: the two older video paths both hurt. Two-stream
 * records two MP4s and stitches them after (leaving a wait to
 * eliminate), and Direct SBS composes the finished SBS frame on the CPU
 * with OpenCV (per-eye capped at 1440x1080, and laggy on busy scenes).
 * The GPU SBS preview already *is* the finished
 * composite — warped, rotated, color-gained, side by side — drawn on
 * the GPU at camera rate. This recorder feeds that exact GL frame
 * straight into a video encoder and muxes it with mic audio: no CPU
 * frames, no warm-up, no stitch, no wait after stopping.
 *
 * How it works (0.79.1): the encoder is configured with
 * COLOR_FormatSurface, so MediaCodec hands us an input Surface. A
 * dedicated render thread draws into that surface with its own EGL
 * context, which *shares* the preview view's context (see
 * SbsPreviewView.gpuRecordSource). Sharing means the recorder thread
 * can sample the same two camera textures the screen thread updates
 * — it never calls updateTexImage itself, it just draws the latest
 * published [SbsPreviewView.RenderState] whenever the view signals a
 * new frame. 0.79.0 instead drew twice on the view's own GL thread
 * (makeCurrent to the encoder surface, draw, swap, restore), and that
 * broke the preview: the encoder draw could stall the GL thread and
 * the ultra eye froze when recording started. Now the screen thread
 * never touches the encoder — a stuck encoder can only stall the
 * recorder thread, never the preview.
 *
 * The frame is already upright in the shader (rotation applied), so
 * the muxer orientation hint is 0. The SBS frame is 2x the preview
 * size (1600x600 at the default 800x600 preview).
 *
 * The shader below mirrors SbsPreviewView's — if the preview shader
 * changes, this one must change with it.
 */
class GpuSbsRecorder(
    private val context: Context,
    private val outputUri: Uri,
    private val sbsSize: Size,
    private val fps: Int,
    private val bitrateBps: Int
) {
    companion object {
        private const val TAG = "GpuSbsRecorder"
        private const val MIME_AVC = "video/avc"
        private const val TIMEOUT_US = 10_000L
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNELS = 1
        private const val AUDIO_BITRATE_BPS = 128_000
        private const val IFRAME_INTERVAL_SEC = 1
        /** EGL_RECORDABLE_ANDROID (not in the EGL14 bindings). */
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        /** Warp/gain constants, mirroring SbsPreviewView's. */
        private val IDENTITY_WARP = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
        private val ULTRA_WARP = floatArrayOf(
            0.66f, 0f, 0f,
            0f, 0.66f, 0f,
            0.17f, 0.17f, 1f
        )
        private val IDENTITY_GAIN = floatArrayOf(1f, 1f, 1f)
    }

    private var codec: MediaCodec? = null
    private var muxer: Mp4Muxer? = null
    private var audioEncoder: AudioEncoder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var drainThread: Thread? = null
    private var renderThread: Thread? = null

    @Volatile private var running = false
    @Volatile var encodedFrames = 0
        private set

    /**
     * Start the encoder + audio + drain loop + the render thread.
     * Returns true if the encoder is running (the preview sessions are
     * untouched — the preview itself is the source). Returns false if
     * the encoder or the shared-context render thread could not start
     * (caller falls back to Direct / two-stream).
     */
    fun startEncoding(source: SbsPreviewView.GpuRecordSource): Boolean {
        val shared = source.sharedContext
        if (shared == null) {
            Log.w(TAG, "GPU SBS: no shared EGL context from the preview view")
            return false
        }
        try {
            pfd = context.contentResolver.openFileDescriptor(outputUri, "rw")
                ?: return false
            // Orientation hint 0: the GL frame is already upright.
            muxer = Mp4Muxer("GpuSbsMuxer", requireNotNull(pfd), 0)

            audioEncoder = AudioEncoder(
                tag = "GpuAudioEncoder",
                muxers = listOf(requireNotNull(muxer)),
                sampleRate = AUDIO_SAMPLE_RATE,
                channelCount = AUDIO_CHANNELS,
                bitrateBps = AUDIO_BITRATE_BPS
            ).also { it.start() }

            val format = MediaFormat.createVideoFormat(MIME_AVC, sbsSize.width, sbsSize.height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps.coerceAtLeast(1_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_SEC)
                setInteger(
                    MediaFormat.KEY_COLOR_STANDARD,
                    MediaFormat.COLOR_STANDARD_BT601_NTSC
                )
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(
                    MediaFormat.KEY_COLOR_TRANSFER,
                    MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                )
            }
            val enc = MediaCodec.createEncoderByType(MIME_AVC)
            try {
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                Log.e(TAG, "GPU SBS encoder configure failed: ${e.message}", e)
                try { enc.release() } catch (_: Exception) {}
                releaseOutputs(deleteFile = true)
                return false
            }
            codec = enc
            val inputSurface = enc.createInputSurface()
            enc.start()

            encodedFrames = 0
            running = true
            drainThread = Thread({ drainLoop() }, "GpuSbs-Drain").also { it.start() }
            try { audioEncoder?.startCapturing() } catch (e: Exception) {
                Log.w(TAG, "GPU audio start failed: ${e.message}")
            }
            // The render thread owns its EGL setup; if it fails, stop.
            renderThread = Thread({
                EncoderRenderer(shared, inputSurface, source).run()
            }, "GpuSbs-Render").also { it.start() }
            Log.i(TAG, "GPU SBS encoding started ${sbsSize.width}x${sbsSize.height}@${fps}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "GPU SBS start failed: ${e.message}", e)
            releaseOutputs(deleteFile = true)
            return false
        }
    }

    /** Encoder drain: output format -> muxer track; samples -> muxer. */
    private fun drainLoop() {
        val enc = codec ?: return
        val mux = muxer ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = try {
                    enc.dequeueOutputBuffer(info, TIMEOUT_US)
                } catch (e: Exception) {
                    return
                }
                when {
                    idx >= 0 -> {
                        val outBuf = try { enc.getOutputBuffer(idx) } catch (_: Exception) { null }
                        if (outBuf != null && info.size > 0) {
                            try { mux.writeVideoSample(outBuf, info) } catch (e: Exception) {
                                Log.w(TAG, "Muxer video write failed: ${e.message}")
                            }
                            encodedFrames++
                        }
                        try { enc.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        try { mux.setVideoFormat(enc.outputFormat) } catch (e: Exception) {
                            Log.w(TAG, "Muxer video format failed: ${e.message}")
                        }
                    }
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!running) return
                    }
                    else -> {
                        if (!running) return
                    }
                }
                if (!running) {
                    // Drain remaining output after EOS was signalled.
                    continue
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "GPU drain loop failed: ${e.message}", e)
        }
    }

    /**
     * Stop the render thread, signal end-of-stream, drain, stop audio +
     * muxer, finalize the MediaStore entry. Returns the encoded frame
     * count.
     */
    fun stopAndFinalize(): Int {
        running = false
        // Wake the render thread so it sees the stop, then wait for it
        // to finish its last swap before EOS.
        try { renderThread?.join(2_000) } catch (_: Exception) {}
        renderThread = null
        try { codec?.signalEndOfInputStream() } catch (_: Exception) {}
        try { drainThread?.join(5_000) } catch (_: Exception) {}
        drainThread = null
        try { audioEncoder?.stopAndRelease() } catch (_: Exception) {}
        audioEncoder = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { muxer?.stopAndRelease() } catch (_: Exception) {}
        muxer = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        try {
            MediaStoreUtils.finalizePending(context.contentResolver, outputUri)
        } catch (e: Exception) {
            Log.w(TAG, "Finalize GPU SBS failed: ${e.message}")
        }
        val frames = encodedFrames
        Log.i(TAG, "GPU SBS finalized: $frames frames")
        return frames
    }

    /** Stop everything and delete the output file (encode failure). */
    fun releaseOnFailure() {
        running = false
        try { renderThread?.join(1_000) } catch (_: Exception) {}
        renderThread = null
        try { drainThread?.join(1_000) } catch (_: Exception) {}
        drainThread = null
        releaseOutputs(deleteFile = true)
    }

    private fun releaseOutputs(deleteFile: Boolean) {
        try { audioEncoder?.stopAndRelease() } catch (_: Exception) {}
        audioEncoder = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { muxer?.stopAndRelease() } catch (_: Exception) {}
        muxer = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        if (deleteFile) {
            try { MediaStoreUtils.delete(context.contentResolver, outputUri) } catch (_: Exception) {}
        }
    }

    /**
     * Renders the preview's SBS frame into the encoder's input surface
     * on its own thread, in a context sharing the view's context. One
     * draw per preview frame, always the latest published state — if
     * the thread falls behind it drops frames instead of queuing them.
     * It never calls updateTexImage (the screen thread owns that) and
     * never touches the screen surface, so a stall here cannot freeze
     * the preview.
     */
    private inner class EncoderRenderer(
        private val sharedContext: android.opengl.EGLContext,
        private val inputSurface: Surface,
        private val source: SbsPreviewView.GpuRecordSource
    ) {
        private var program = 0
        private var aPosition = 0
        private var aTexCoord = 0
        private var uStMatrix = 0
        private var uRotCos = 0
        private var uRotSin = 0
        private var uWarpMatrix = 0
        private var uColorGain = 0
        private var uColorOffset = 0

        private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(
                    -1f, -1f, 0f, 0f,
                     1f, -1f, 1f, 0f,
                    -1f,  1f, 0f, 1f,
                     1f,  1f, 1f, 1f
                ))
                position(0)
            }

        private var ptsBaseNs: Long = -1L
        private var lastPtsNs: Long = -1L
        private var framesDrawn: Int = 0
        /** 0.79.4: one record-frame interval, from the requested fps. */
        private val frameIntervalNs: Long = 1_000_000_000L / fps.coerceAtLeast(1)
        /** 0.79.5: target wide-eye timestamp for the next draw, in the
         *  wide eye's timestamp domain. -1 until the first frame. */
        private var nextDrawTsNs: Long = -1L

        fun run() {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                Log.e(TAG, "GPU render: no EGL display")
                return
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                Log.e(TAG, "GPU render: eglInitialize failed")
                return
            }
            val configAttribs = intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] == 0 || configs[0] == null
            ) {
                Log.e(TAG, "GPU render: no recordable EGL config")
                EGL14.eglTerminate(display)
                return
            }
            val config = configs[0]!!
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            val ctx = EGL14.eglCreateContext(display, config, sharedContext, ctxAttribs, 0)
            if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "GPU render: eglCreateContext (shared) failed: ${EGL14.eglGetError()}")
                EGL14.eglTerminate(display)
                return
            }
            val surface = EGL14.eglCreateWindowSurface(
                display, config, inputSurface, intArrayOf(EGL14.EGL_NONE), 0
            )
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "GPU render: eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
                EGL14.eglDestroyContext(display, ctx)
                EGL14.eglTerminate(display)
                return
            }
            try {
                if (!EGL14.eglMakeCurrent(display, surface, surface, ctx)) {
                    Log.e(TAG, "GPU render: makeCurrent failed")
                    return
                }
                program = buildProgram()
                if (program == 0) {
                    Log.e(TAG, "GPU render: shader program failed")
                    return
                }
                aPosition = GLES20.glGetAttribLocation(program, "aPosition")
                aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
                uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
                uRotCos = GLES20.glGetUniformLocation(program, "uRotCos")
                uRotSin = GLES20.glGetUniformLocation(program, "uRotSin")
                uWarpMatrix = GLES20.glGetUniformLocation(program, "uWarpMatrix")
                uColorGain = GLES20.glGetUniformLocation(program, "uColorGain")
                uColorOffset = GLES20.glGetUniformLocation(program, "uColorOffset")
                Log.i(TAG, "GPU render thread ready")

                var lastSeq = -1L
                while (running) {
                    // Wait for a new preview frame (or the stop signal).
                    val seq = source.frameSequence()
                    if (seq == lastSeq) {
                        synchronized(source.frameSignal) {
                            try { (source.frameSignal as java.lang.Object).wait(100) } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                break
                            }
                        }
                        continue
                    }
                    lastSeq = seq
                    val state = source.latestState() ?: continue
                    // 0.79.5: honor the requested record fps with a
                    // target-time accumulator. The preview runs at its
                    // own rate (30fps), so "skip if < 1/fps since the
                    // last draw" drew every other preview frame — the
                    // file came out at ~14fps instead of 24. The target
                    // must advance by a fixed interval from the TARGET,
                    // not from the drawn frame: draw a source frame only
                    // when its wide-eye timestamp reaches the next
                    // target, then advance the target past it. The PTS
                    // come from the wide eye's camera timestamp, so the
                    // file stays correctly timed at the requested rate.
                    if (state.wideTsNs > 0L) {
                        if (nextDrawTsNs < 0L) {
                            nextDrawTsNs = state.wideTsNs
                        } else if (state.wideTsNs < nextDrawTsNs) {
                            continue
                        }
                    }
                    try {
                        drawFrame(display, surface, state)
                    } catch (e: Exception) {
                        Log.w(TAG, "GPU render frame failed: ${e.message}")
                    }
                    if (state.wideTsNs > 0L) {
                        do { nextDrawTsNs += frameIntervalNs } while (nextDrawTsNs <= state.wideTsNs)
                    }
                }
            } finally {
                try { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) } catch (_: Exception) {}
                try { EGL14.eglDestroySurface(display, surface) } catch (_: Exception) {}
                try { EGL14.eglDestroyContext(display, ctx) } catch (_: Exception) {}
                try { EGL14.eglTerminate(display) } catch (_: Exception) {}
                Log.i(TAG, "GPU render thread ended, drew $framesDrawn frames")
            }
        }

        private fun drawFrame(
            display: android.opengl.EGLDisplay,
            surface: android.opengl.EGLSurface,
            state: SbsPreviewView.RenderState
        ) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            quad.position(0)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aPosition)
            quad.position(2)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            drawSbsFrame(state)
            val wideTs = state.wideTsNs
            if (ptsBaseNs < 0L) ptsBaseNs = if (wideTs > 0L) wideTs else System.nanoTime()
            var pts = if (wideTs > 0L) wideTs - ptsBaseNs else System.nanoTime() - ptsBaseNs
            if (pts <= lastPtsNs) pts = lastPtsNs + frameIntervalNs
            lastPtsNs = pts
            EGLExt.eglPresentationTimeANDROID(display, surface, pts)
            EGL14.eglSwapBuffers(display, surface)
            framesDrawn++
        }

        /** Same layout as SbsPreviewView's screen draw, at encoder size. */
        private fun drawSbsFrame(state: SbsPreviewView.RenderState) {
            val targetW = sbsSize.width
            val targetH = sbsSize.height
            val sbs = state.sbsEnabled
            if (!sbs) {
                GLES20.glViewport(0, 0, targetW, targetH)
                drawEye(state.wideTexId, state.stMatrixWide, IDENTITY_WARP, state)
                return
            }
            val halfW = targetW / 2
            val (leftTex, leftMatrix, leftWarp, rightTex, rightMatrix, rightWarp) =
                if (state.sbsCross) {
                    Quad(state.ultraTexId, state.stMatrixUltra, ULTRA_WARP, state.wideTexId, state.stMatrixWide, IDENTITY_WARP)
                } else {
                    Quad(state.wideTexId, state.stMatrixWide, IDENTITY_WARP, state.ultraTexId, state.stMatrixUltra, ULTRA_WARP)
                }
            GLES20.glViewport(0, 0, halfW, targetH)
            drawEye(leftTex, leftMatrix, leftWarp, state)
            GLES20.glViewport(halfW, 0, targetW - halfW, targetH)
            drawEye(rightTex, rightMatrix, rightWarp, state)
        }

        private fun drawEye(texId: Int, stMatrix: FloatArray, warp: FloatArray, state: SbsPreviewView.RenderState) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            val rot = state.rotationRad
            GLES20.glUniform1f(uRotCos, kotlin.math.cos(rot))
            GLES20.glUniform1f(uRotSin, kotlin.math.sin(rot))
            GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)
            GLES20.glUniformMatrix3fv(uWarpMatrix, 1, false, warp, 0)
            // 0.79.9: same measured per-eye correction as the preview
            // (see SbsPreviewView.measureAndUpdateColorMatch) — the
            // recorder draws from the published RenderState, so the
            // video's colors match the preview's.
            val (gain, offset) = if (texId == state.ultraTexId) {
                state.ultraGain to state.ultraOffset
            } else {
                state.wideGain to state.wideOffset
            }
            GLES20.glUniform3f(uColorGain, gain[0], gain[1], gain[2])
            GLES20.glUniform3f(uColorOffset, offset[0], offset[1], offset[2])
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun buildProgram(): Int {
            // Mirrors SbsPreviewView's shaders — keep them in sync.
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
                Log.e(TAG, "GPU render program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
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
                Log.e(TAG, "GPU render shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }

    }

    /** Quad for drawSbsFrame. At class level, not inside the inner
     *  renderer — a data class cannot be declared inside an inner class. */
    private data class Quad(
        val leftTex: Int, val leftMatrix: FloatArray, val leftWarp: FloatArray,
        val rightTex: Int, val rightMatrix: FloatArray, val rightWarp: FloatArray
    )
}

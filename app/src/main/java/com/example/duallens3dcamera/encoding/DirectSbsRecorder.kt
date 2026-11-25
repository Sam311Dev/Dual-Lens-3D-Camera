package com.example.duallens3dcamera.encoding

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.util.Size
import com.example.duallens3dcamera.media.MediaStoreUtils
import com.example.duallens3dcamera.util.StereoSbs
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Direct SBS recording (0.77.0): composes the finished side-by-side video
 * LIVE from the two lenses instead of recording two MP4s and stitching them
 * after.
 *
 * How it works:
 * - Both lenses feed YUV ImageReaders (wide: preview + reader; ultra:
 *   reader only). The stream counts fit the phone's mandatory concurrent
 *   combinations (wide: PRIVATE <=1280x720 + YUV <=1920x1440; ultra: a
 *   single YUV <=1920x1440) — see the 3DDualCam diagnostics dump.
 * - On record start the cameras run for a ~1s warm-up while the lenses'
 *   AE/AWB settle; the alignment affine + color-match params are estimated
 *   once from a settled pair (same math as the stitcher's two-pass
 *   estimation) and then reused for every frame, so the match can't flicker.
 * - A compose thread pairs frames by timestamp, warps the ultrawide into
 *   the wide's geometry, color-matches, assembles the SBS frame, and feeds
 *   it to a single NV12 byte-buffer encoder. One *_sbs.mp4 comes out, with
 *   audio from the mic — no separate per-lens videos, no post step.
 *
 * Costs, stated plainly: this is CPU composition (YUV->BGR->warp->color->
 * BGR->NV12 per frame), so the per-eye size is capped at 1440x1080 and a
 * busy scene may drop frames (the video stays valid, just choppier). The
 * two-stream stitched path keeps the originals and is still the default.
 * A GPU (OpenGL) composer would be the future upgrade if this proves
 * popular; it was deliberately not attempted in v1 because it can't be
 * validated on-device from here.
 */
class DirectSbsRecorder(
    private val context: Context,
    private val outputUri: Uri,
    private val eyeSize: Size,
    private val fps: Int,
    private val bitrateBps: Int,
    private val orientationHint: Int,
    private val rotationDegrees: Int,
    private val sbsView: StereoSbs.SbsView,
    private val colorMatch: StereoVideoStitcher.ColorMatch,
    listenerHandler: Handler
) {
    companion object {
        private const val TAG = "DirectSbsRecorder"
        private const val MIME_AVC = "video/avc"
        private const val TIMEOUT_US = 10_000L
        private const val WARMUP_SETTLE_MS = 1000L
        private const val WARMUP_TIMEOUT_MS = 4000L
        private const val PAIR_TOLERANCE_US = 66_000L
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNELS = 1
        private const val AUDIO_BITRATE_BPS = 128_000
        private const val IFRAME_INTERVAL_SEC = 1
    }

    /** One frame waiting to be composed, with the camera timestamp. */
    private data class TimedImage(val timestampNs: Long, val image: Image)

    /** ImageReaders the controller adds to the record sessions. */
    val wideReader: ImageReader =
        ImageReader.newInstance(eyeSize.width, eyeSize.height, ImageFormat.YUV_420_888, 3)
    val ultraReader: ImageReader =
        ImageReader.newInstance(eyeSize.width, eyeSize.height, ImageFormat.YUV_420_888, 3)

    private val wideQueue = ArrayBlockingQueue<TimedImage>(2)
    private val ultraQueue = ArrayBlockingQueue<TimedImage>(2)

    @Volatile private var running = false
    @Volatile private var warmupStartedAtMs = -1L
    @Volatile private var warmupCancelled = false

    private var affine: Mat? = null
    private var colorParams: StereoSbs.ColorMatchParams? = null
    /** Which eye the color params adjust: WIDE -> adjust ultra onto wide. */
    private var matchTargetWide = true

    private var codec: MediaCodec? = null
    private var muxer: Mp4Muxer? = null
    private var audioEncoder: AudioEncoder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var composeThread: Thread? = null
    private var drainThread: Thread? = null
    private var firstPtsUs = -1L
    private var lastPtsUs = -1L
    @Volatile var encodedFrames = 0
        private set

    init {
        wideReader.setOnImageAvailableListener(
            { reader -> offerFrame(reader, wideQueue) }, listenerHandler
        )
        ultraReader.setOnImageAvailableListener(
            { reader -> offerFrame(reader, ultraQueue) }, listenerHandler
        )
    }

    private fun offerFrame(reader: ImageReader, queue: ArrayBlockingQueue<TimedImage>) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        val timed = TimedImage(image.timestamp, image)
        // Latest-wins: drop the oldest queued frame (and close it) if full.
        var offered = queue.offer(timed)
        while (!offered) {
            val old = queue.poll()
            if (old == null) {
                offered = queue.offer(timed)
                break
            }
            try { old.image.close() } catch (_: Exception) {}
            offered = queue.offer(timed)
        }
        if (!offered) {
            // Could not hand the frame off: close it, or the reader's
            // image pool (maxImages) stalls and no more frames arrive.
            try { image.close() } catch (_: Exception) {}
        }
    }

    /** Called by the controller once both record sessions are repeating. */
    fun beginWarmup() {
        warmupStartedAtMs = SystemClock.elapsedRealtime()
    }

    /** Abort a warm-up in progress (user hit stop before encoding started). */
    fun cancelWarmup() {
        warmupCancelled = true
    }

    /**
     * Estimate the per-clip affine + color params from a settled frame pair.
     * Blocks up to [WARMUP_TIMEOUT_MS] after the settle window. Returns false
     * if no usable pair arrived or alignment failed — the caller should fall
     * back to the two-stream stitched path.
     */
    fun estimateParams(): Boolean {
        // 0.77.2: load OpenCV BEFORE any Mat is allocated — the YUV->BGR
        // step below creates Mats directly, and with the native library
        // unloaded that throws UnsatisfiedLinkError (an Error, uncatchable
        // by `catch (e: Exception)`), which crashed 0.77.0/0.77.1. If the
        // library won't load, fail soft and let the caller fall back to
        // the two-stream path.
        if (!StereoSbs.ensureOpenCv()) {
            Log.w(TAG, "OpenCV not available for direct warm-up")
            return false
        }
        val startedAt = warmupStartedAtMs
        if (startedAt < 0) return false
        val deadlineMs = startedAt + WARMUP_SETTLE_MS + WARMUP_TIMEOUT_MS
        try {
            while (SystemClock.elapsedRealtime() < deadlineMs) {
                if (warmupCancelled) return false
                val wideTimed = wideQueue.poll(100, TimeUnit.MILLISECONDS)
                val ultraTimed = ultraQueue.poll(100, TimeUnit.MILLISECONDS)
                if (wideTimed == null || ultraTimed == null) {
                    // Re-queue the one we got (still open!) so a later
                    // iteration can use it once the other lens delivers.
                    // (0.77.1: the first build closed it and then re-queued
                    // the closed image, so the next pair always failed.)
                    if (wideTimed != null) offerBack(wideQueue, wideTimed)
                    if (ultraTimed != null) offerBack(ultraQueue, ultraTimed)
                    continue
                }
                // Only use a pair once the lenses have had their settle second.
                if (SystemClock.elapsedRealtime() < startedAt + WARMUP_SETTLE_MS) {
                    try { wideTimed.image.close() } catch (_: Exception) {}
                    try { ultraTimed.image.close() } catch (_: Exception) {}
                    continue
                }
                if (abs(wideTimed.timestampNs - ultraTimed.timestampNs) > PAIR_TOLERANCE_US * 1000L) {
                    // Timestamps too far apart: drop the older frame, keep the newer.
                    if (wideTimed.timestampNs < ultraTimed.timestampNs) {
                        try { wideTimed.image.close() } catch (_: Exception) {}
                        offerBack(ultraQueue, ultraTimed)
                    } else {
                        try { ultraTimed.image.close() } catch (_: Exception) {}
                        offerBack(wideQueue, wideTimed)
                    }
                    continue
                }
                return estimateFromPair(wideTimed, ultraTimed)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Throwable) {
            // 0.77.2: catch Throwable, not just Exception — native-library
            // errors (UnsatisfiedLinkError) are Errors, and letting one
            // escape this thread kills the whole app instead of falling
            // back to the two-stream path.
            Log.w(TAG, "Warm-up estimation failed: ${e.message}", e)
        }
        return false
    }

    private fun offerBack(queue: ArrayBlockingQueue<TimedImage>, timed: TimedImage) {
        var offered = queue.offer(timed)
        while (!offered) {
            val old = queue.poll() ?: break
            try { old.image.close() } catch (_: Exception) {}
            offered = queue.offer(timed)
        }
        if (!offered) {
            // Could not re-queue: close it instead of leaking the image.
            try { timed.image.close() } catch (_: Exception) {}
        }
    }

    /** Runs alignBgrPair + color estimation on one settled pair. Takes ownership of both images. */
    private fun estimateFromPair(wideTimed: TimedImage, ultraTimed: TimedImage): Boolean {
        var wideBgr: Mat? = null
        var ultraBgr: Mat? = null
        try {
            wideBgr = yuv420ToBgr(wideTimed.image) ?: return false
            ultraBgr = yuv420ToBgr(ultraTimed.image) ?: return false
            val wideUp = rotateOwned(wideBgr, rotationDegrees)
            val ultraUp = rotateOwned(ultraBgr, rotationDegrees)
            // alignBgrPair takes ownership of both Mats.
            val aligned = StereoSbs.alignBgrPair(wideUp, ultraUp) ?: return false
            var mask: Mat? = null
            var params: StereoSbs.ColorMatchParams? = null
            var targetWide = true
            try {
                if (colorMatch != StereoVideoStitcher.ColorMatch.OFF) {
                    val m = StereoSbs.validityMask(aligned.ultraAligned)
                    mask = if (Core.countNonZero(m) < (aligned.wideBgr.cols() * aligned.wideBgr.rows() * 0.01)) {
                        try { m.release() } catch (_: Exception) {}
                        Mat(
                            aligned.wideBgr.rows(), aligned.wideBgr.cols(),
                            CvType.CV_8UC1, org.opencv.core.Scalar(255.0)
                        )
                    } else {
                        m
                    }
                    when (colorMatch) {
                        StereoVideoStitcher.ColorMatch.WIDE -> {
                            params = StereoSbs.computeColorMatchParams(
                                aligned.ultraAligned, aligned.wideBgr, requireNotNull(mask)
                            )
                            targetWide = true
                        }
                        StereoVideoStitcher.ColorMatch.ULTRA -> {
                            params = StereoSbs.computeColorMatchParams(
                                aligned.wideBgr, aligned.ultraAligned, requireNotNull(mask)
                            )
                            targetWide = false
                        }
                        StereoVideoStitcher.ColorMatch.OFF -> {}
                    }
                }
                affine = aligned.affine
                colorParams = params
                matchTargetWide = targetWide
                Log.i(TAG, "Warm-up estimation done: affine + colorMatch=$colorMatch")
                return true
            } finally {
                try { aligned.wideBgr.release() } catch (_: Exception) {}
                try { aligned.ultraAligned.release() } catch (_: Exception) {}
                try { mask?.release() } catch (_: Exception) {}
            }
        } catch (e: Throwable) {
            // 0.77.2: Throwable, not Exception — see estimateParams.
            Log.w(TAG, "Warm-up estimation failed: ${e.message}", e)
            return false
        } finally {
            try { wideTimed.image.close() } catch (_: Exception) {}
            try { ultraTimed.image.close() } catch (_: Exception) {}
        }
    }

    /**
     * Start the SBS encoder + audio + compose loop. Must be called after a
     * successful [estimateParams]. Returns false if the encoder could not be
     * configured (caller should fall back to the stitched path).
     */
    fun startEncoding(): Boolean {
        if (affine == null) return false
        // 0.77.2: the compose loop allocates Mats too — make sure the
        // native library is loaded (estimateParams already did this, but
        // never trust call order for a process-killing Error).
        if (!StereoSbs.ensureOpenCv()) return false
        val sbsW = eyeSize.width * 2
        val sbsH = eyeSize.height
        try {
            pfd = context.contentResolver.openFileDescriptor(outputUri, "rw")
                ?: return false
            muxer = Mp4Muxer("DirectSbsMuxer", requireNotNull(pfd), orientationHint)

            audioEncoder = AudioEncoder(
                tag = "DirectAudioEncoder",
                muxers = listOf(requireNotNull(muxer)),
                sampleRate = AUDIO_SAMPLE_RATE,
                channelCount = AUDIO_CHANNELS,
                bitrateBps = AUDIO_BITRATE_BPS
            ).also { it.start() }

            val format = MediaFormat.createVideoFormat(MIME_AVC, sbsW, sbsH).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps.coerceAtLeast(1_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_SEC)
                // Limited-range BT.601 YUV (OpenCV NV12), same as the stitcher.
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
                Log.e(TAG, "Direct SBS encoder configure failed: ${e.message}", e)
                try { enc.release() } catch (_: Exception) {}
                releaseOutputs(deleteFile = true)
                return false
            }
            codec = enc
            enc.start()

            firstPtsUs = -1L
            lastPtsUs = -1L
            encodedFrames = 0
            running = true

            drainThread = Thread({ drainLoop() }, "DirectSbs-Drain").also { it.start() }
            try { audioEncoder?.startCapturing() } catch (e: Exception) {
                Log.w(TAG, "Direct audio start failed: ${e.message}")
            }
            composeThread = Thread({ composeLoop() }, "DirectSbs-Compose").also { it.start() }
            Log.i(TAG, "Direct SBS encoding started ${sbsW}x${sbsH}@${fps}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Direct SBS start failed: ${e.message}", e)
            releaseOutputs(deleteFile = true)
            return false
        }
    }

    /** Compose loop: pair frames, build the SBS frame, feed the encoder.
     *  The outer guard keeps a rogue frame from killing the process: on a
     *  fatal error the loop stops and the stop path finalizes what was
     *  encoded so far. */
    private fun composeLoop() {
        val frameIntervalUs = 1_000_000L / fps.coerceIn(1, 120)
        try {
        while (running) {
            val wideTimed = wideQueue.poll(100, TimeUnit.MILLISECONDS)
                ?: continue
            val ultraTimed = ultraQueue.poll(100, TimeUnit.MILLISECONDS)
            if (ultraTimed == null) {
                // No ultra frame yet: keep the wide frame for the next try if
                // it is still fresh, else drop it.
                if (SystemClock.elapsedRealtimeNanos() - wideTimed.timestampNs < 100_000_000L) {
                    offerBack(wideQueue, wideTimed)
                } else {
                    try { wideTimed.image.close() } catch (_: Exception) {}
                }
                continue
            }
            if (abs(wideTimed.timestampNs - ultraTimed.timestampNs) > PAIR_TOLERANCE_US * 1000L) {
                // Too far apart: drop the older one, keep the newer for pairing.
                if (wideTimed.timestampNs < ultraTimed.timestampNs) {
                    try { wideTimed.image.close() } catch (_: Exception) {}
                    offerBack(ultraQueue, ultraTimed)
                } else {
                    try { ultraTimed.image.close() } catch (_: Exception) {}
                    offerBack(wideQueue, wideTimed)
                }
                continue
            }
            val ptsUs = wideTimed.timestampNs / 1000L
            try {
                val nv12 = composeSbsNv12(wideTimed.image, ultraTimed.image) ?: continue
                queueToEncoder(nv12, ptsUs, frameIntervalUs)
            } catch (e: Exception) {
                Log.w(TAG, "Compose frame failed: ${e.message}")
            } finally {
                try { wideTimed.image.close() } catch (_: Exception) {}
                try { ultraTimed.image.close() } catch (_: Exception) {}
            }
        }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Compose loop interrupted")
        } catch (e: Throwable) {
            Log.e(TAG, "Compose loop failed: ${e.message}", e)
            running = false
        }
    }

    /** Build one SBS frame as NV12 bytes. Returns null if the pair was unusable. */
    private fun composeSbsNv12(wideImage: Image, ultraImage: Image): ByteArray? {
        val affineNow = affine ?: return null
        var wideBgr: Mat? = null
        var ultraBgr: Mat? = null
        var ultraAligned: Mat? = null
        var ultraEye: Mat? = null
        var wideEye: Mat? = null
        var sbs: Mat? = null
        try {
            wideBgr = yuv420ToBgr(wideImage) ?: return null
            ultraBgr = yuv420ToBgr(ultraImage) ?: return null
            val wideUp = rotateOwned(wideBgr, rotationDegrees)
            val ultraUp = rotateOwned(ultraBgr, rotationDegrees)
            wideBgr = null
            ultraBgr = null
            ultraAligned = StereoSbs.warpUltraWithAffine(
                ultraUp, affineNow, eyeSize.width, eyeSize.height
            )
            try { ultraUp.release() } catch (_: Exception) {}
            val params = colorParams
            if (params != null) {
                if (matchTargetWide) {
                    ultraEye = StereoSbs.applyColorMatchParams(ultraAligned, params)
                    try { ultraAligned.release() } catch (_: Exception) {}
                    ultraAligned = null
                    wideEye = wideUp
                } else {
                    wideEye = StereoSbs.applyColorMatchParams(wideUp, params)
                    try { wideUp.release() } catch (_: Exception) {}
                    ultraEye = ultraAligned
                    ultraAligned = null
                }
            } else {
                ultraEye = ultraAligned
                ultraAligned = null
                wideEye = wideUp
            }
            val left = if (sbsView == StereoSbs.SbsView.CROSS) ultraEye else wideEye
            val right = if (sbsView == StereoSbs.SbsView.CROSS) wideEye else ultraEye
            sbs = StereoSbs.assembleSbsMat(left, right) ?: return null
            return bgrToNv12(sbs)
        } finally {
            try { wideBgr?.release() } catch (_: Exception) {}
            try { ultraBgr?.release() } catch (_: Exception) {}
            try { ultraAligned?.release() } catch (_: Exception) {}
            try { ultraEye?.release() } catch (_: Exception) {}
            try { wideEye?.release() } catch (_: Exception) {}
            try { sbs?.release() } catch (_: Exception) {}
        }
    }

    /** Queue one NV12 frame to the encoder with a monotonic PTS. */
    private fun queueToEncoder(nv12: ByteArray, ptsUs: Long, frameIntervalUs: Long) {
        val enc = codec ?: return
        val base = if (firstPtsUs < 0) {
            firstPtsUs = ptsUs
            ptsUs
        } else {
            firstPtsUs
        }
        var relPts = (ptsUs - base).coerceAtLeast(0L)
        if (relPts <= lastPtsUs) relPts = lastPtsUs + frameIntervalUs
        lastPtsUs = relPts
        val idx = try {
            enc.dequeueInputBuffer(TIMEOUT_US)
        } catch (e: Exception) {
            -1
        }
        if (idx < 0) return // encoder busy: drop the frame, keep recording
        try {
            val buf = enc.getInputBuffer(idx) ?: run {
                enc.queueInputBuffer(idx, 0, 0, 0, 0)
                return
            }
            buf.clear()
            if (buf.remaining() < nv12.size) {
                enc.queueInputBuffer(idx, 0, 0, 0, 0)
                return
            }
            buf.put(nv12)
            enc.queueInputBuffer(idx, 0, nv12.size, relPts, 0)
            encodedFrames++
        } catch (e: Exception) {
            Log.w(TAG, "Encoder input failed: ${e.message}")
        }
    }

    /** Encoder drain: output format -> muxer track; samples -> muxer.
     *  Guarded so a rogue drain error ends the thread instead of the app. */
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
            Log.e(TAG, "Drain loop failed: ${e.message}", e)
        }
    }

    /**
     * Stop composing, flush the encoder, stop audio + muxer, finalize the
     * MediaStore entry. Returns the encoded frame count.
     */
    fun stopAndFinalize(): Int {
        running = false
        drainQueues()
        try { composeThread?.join(2_000) } catch (_: Exception) {}
        composeThread = null
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
            Log.w(TAG, "Finalize direct SBS failed: ${e.message}")
        }
        val frames = encodedFrames
        releaseReaders()
        releaseEstimation()
        Log.i(TAG, "Direct SBS finalized: $frames frames")
        return frames
    }

    /** Stop everything and delete the output file (warm-up/encode failure). */
    fun releaseOnFailure() {
        running = false
        drainQueues()
        try { composeThread?.join(1_000) } catch (_: Exception) {}
        composeThread = null
        try { drainThread?.join(1_000) } catch (_: Exception) {}
        drainThread = null
        releaseOutputs(deleteFile = true)
        releaseReaders()
        releaseEstimation()
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

    private fun drainQueues() {
        var t = wideQueue.poll()
        while (t != null) {
            try { t.image.close() } catch (_: Exception) {}
            t = wideQueue.poll()
        }
        t = ultraQueue.poll()
        while (t != null) {
            try { t.image.close() } catch (_: Exception) {}
            t = ultraQueue.poll()
        }
    }

    private fun releaseReaders() {
        try { wideReader.close() } catch (_: Exception) {}
        try { ultraReader.close() } catch (_: Exception) {}
    }

    private fun releaseEstimation() {
        try { affine?.release() } catch (_: Exception) {}
        affine = null
        colorParams = null
    }

    /** Rotate a BGR Mat to upright, releasing the input when a new Mat is made. */
    private fun rotateOwned(src: Mat, degrees: Int): Mat {
        return when (degrees) {
            180 -> {
                val out = Mat()
                Core.flip(src, out, -1)
                try { src.release() } catch (_: Exception) {}
                out
            }
            90 -> {
                val out = Mat()
                Core.rotate(src, out, Core.ROTATE_90_CLOCKWISE)
                try { src.release() } catch (_: Exception) {}
                out
            }
            270 -> {
                val out = Mat()
                Core.rotate(src, out, Core.ROTATE_90_COUNTERCLOCKWISE)
                try { src.release() } catch (_: Exception) {}
                out
            }
            else -> src
        }
    }

    /** YUV_420_888 Image -> BGR Mat (via NV21). Returns null on failure. */
    private fun yuv420ToBgr(image: Image): Mat? {
        val w = image.width
        val h = image.height
        val planes = image.planes
        if (planes.size < 3) return null
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val ySize = w * h
        val uvSize = ySize / 4
        val nv21 = ByteArray(ySize + uvSize * 2)
        // Y plane, row by row (rowStride may exceed width).
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        if (yRowStride == w) {
            yBuf.get(nv21, 0, ySize)
        } else {
            var dst = 0
            val row = ByteArray(w)
            for (r in 0 until h) {
                yBuf.position(r * yRowStride)
                yBuf.get(row, 0, w)
                System.arraycopy(row, 0, nv21, dst, w)
                dst += w
            }
        }
        // Chroma: NV21 wants interleaved V,U. YUV_420_888 planes are separate;
        // read each at its pixel stride and interleave.
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vPixStride = vPlane.pixelStride
        var dst = ySize
        for (r in 0 until h / 2) {
            for (c in 0 until w / 2) {
                val vIdx = r * vRowStride + c * vPixStride
                val uIdx = r * uRowStride + c * uPixStride
                nv21[dst++] = vBuf.get(vIdx)
                nv21[dst++] = uBuf.get(uIdx)
            }
        }
        val yuv = Mat(h + h / 2, w, CvType.CV_8UC1)
        yuv.put(0, 0, nv21)
        val bgr = Mat()
        Imgproc.cvtColor(yuv, bgr, Imgproc.COLOR_YUV2BGR_NV21)
        try { yuv.release() } catch (_: Exception) {}
        return bgr
    }

    /** BGR Mat (2W x H) -> NV12 bytes for the encoder. */
    private fun bgrToNv12(bgr: Mat): ByteArray {
        val w = bgr.cols()
        val h = bgr.rows()
        val i420 = Mat()
        Imgproc.cvtColor(bgr, i420, Imgproc.COLOR_BGR2YUV_I420)
        val ySize = w * h
        val uvSize = ySize / 4
        val planar = ByteArray(ySize + uvSize * 2)
        i420.get(0, 0, planar)
        try { i420.release() } catch (_: Exception) {}
        // I420 is Y + U + V planar; NV12 wants Y + interleaved U,V.
        val nv12 = ByteArray(ySize + uvSize * 2)
        System.arraycopy(planar, 0, nv12, 0, ySize)
        var dst = ySize
        for (i in 0 until uvSize) {
            nv12[dst++] = planar[ySize + i]
            nv12[dst++] = planar[ySize + uvSize + i]
        }
        return nv12
    }
}

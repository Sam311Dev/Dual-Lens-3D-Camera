package com.example.duallens3dcamera.encoding

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.duallens3dcamera.media.MediaStoreUtils
import com.example.duallens3dcamera.util.StereoSbs
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Post-recording video stitcher: combines the separate wide + ultrawide MP4s
 * into one side-by-side `*_sbs.mp4`.
 *
 * How it works:
 * - Both MP4s are decoded frame-by-frame (synchronous MediaCodec decoders) and
 *   paired by presentation timestamp (the lens that is behind gets its frames
 *   dropped until the pair lines up within half a frame interval).
 * - The first paired frames establish the geometry: the same ORB-affine
 *   alignment the photo SBS uses ([StereoSbs.alignBgrPair]) is estimated once,
 *   then reused for every frame via [StereoSbs.warpUltraWithAffine].
 * - Color matching is also computed once from the first pair (so it can't
 *   flicker) and applied to every frame: "wide" (default, warmer) adjusts the
 *   ultrawide to the wide's colors, "ultra" adjusts the wide to the
 *   ultrawide's colors, "off" keeps each lens's own colors.
 * - Frames are re-encoded with a ByteBuffer-input AVC encoder (NV12) at twice
 *   the configured video bitrate (the SBS frame is twice as wide) and muxed
 *   with the wide file's audio track, timestamps preserved.
 *
 * This runs synchronously on the caller's thread — always call it from a
 * background executor. It never touches the two source MP4s: on any failure it
 * returns null and the separate videos are simply kept as they are.
 */
object StereoVideoStitcher {
    private const val TAG = "StereoVideoStitcher"
    private const val TIMEOUT_US = 10_000L

    enum class ColorMatch { WIDE, ULTRA, OFF }

    fun colorMatchFromSetting(value: String): ColorMatch = when (value.lowercase()) {
        "ultra" -> ColorMatch.ULTRA
        "off" -> ColorMatch.OFF
        else -> ColorMatch.WIDE
    }

    data class Result(
        val outputUri: Uri,
        val frames: Int,
        val durationUs: Long
    )

    private val openCvReady = AtomicBoolean(false)
    private val triedInit = AtomicBoolean(false)

    private fun ensureOpenCv(): Boolean {
        if (openCvReady.get()) return true
        if (!triedInit.compareAndSet(false, true)) return openCvReady.get()
        return try {
            val ok = OpenCVLoader.initDebug()
            openCvReady.set(ok)
            if (!ok) Log.e(TAG, "OpenCVLoader.initDebug() returned false")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "OpenCV init failed: ${t.message}", t)
            false
        }
    }

    private data class DecodedFrame(val bgr: Mat, val ptsUs: Long)

    /**
     * Alignment + color parameters for one stitch run, estimated once from a
     * settled frame pair (see [estimateStitchParams]). The affine and mask
     * Mats are owned by the caller and must be released.
     */
    private class StitchEstimation(
        val affine: Mat,
        val colorParams: StereoSbs.ColorMatchParams?,
        val mask: Mat?
    )

    /**
     * Stitch [wideUri] + [ultraUri] into a new `*_sbs.mp4` named [outputName].
     * Returns the result, or null if stitching failed (sources untouched).
     *
     * Two passes: pass 1 decodes forward to a settled frame pair (~1s in, or
     * the middle of a short clip) and estimates the alignment + color match
     * from it; pass 2 re-decodes from the start and stitches every frame
     * with those parameters. Estimating from the very first frames was wrong:
     * at recording start the lenses' AE/AWB are still settling (on the Moto
     * the ultrawide starts much brighter/cooler than the wide and darkens
     * over the first second), so first-pair color parameters crushed every
     * later frame, and a failed ORB on the first pair fell back to a cached
     * affine from an earlier photo (wrong frame size, wrong framing).
     */
    fun stitch(
        context: Context,
        wideUri: Uri,
        ultraUri: Uri,
        outputName: String,
        timestampMillis: Long,
        colorMatch: ColorMatch,
        bitrateBps: Int,
        sbsView: StereoSbs.SbsView = StereoSbs.SbsView.CROSS,
        /** Progress reports (0.76.5): stage text + frames done / total frames. */
        onProgress: ((stage: String, done: Int, total: Int) -> Unit)? = null
    ): Result? {
        if (!ensureOpenCv()) {
            Log.e(TAG, "OpenCV not available; cannot stitch")
            return null
        }

        var wideDec: VideoTrackDecoder? = null
        var ultraDec: VideoTrackDecoder? = null
        var audioExt: MediaExtractor? = null
        var audioPfd: ParcelFileDescriptor? = null
        var outPfd: ParcelFileDescriptor? = null
        var outUri: Uri? = null
        try {
            wideDec = VideoTrackDecoder(context, wideUri)
            ultraDec = VideoTrackDecoder(context, ultraUri)
            if (wideDec.width != ultraDec.width || wideDec.height != ultraDec.height) {
                Log.w(TAG, "Video sizes differ: wide=${wideDec.width}x${wideDec.height} " +
                    "ultra=${ultraDec.width}x${ultraDec.height}; refusing to stitch")
                return null
            }
            if (wideDec.width % 2 != 0 || wideDec.height % 2 != 0) {
                Log.w(TAG, "Odd video dimensions; refusing to stitch")
                return null
            }
            wideDec.start()
            ultraDec.start()

            val wideW = wideDec.width
            val wideH = wideDec.height
            val fps = wideDec.frameRate.coerceIn(1, 120)
            val sbsW = wideW * 2
            val sbsH = wideH

            // ---- Pass 1: estimate alignment + color match from a settled pair.
            val clipDurationUs = maxOf(wideDec.durationUs, ultraDec.durationUs)
            val totalFrames = ((clipDurationUs / 1_000_000.0) * fps).roundToInt().coerceAtLeast(1)
            onProgress?.invoke("Reading video…", 0, totalFrames)
            val targetUs = minOf(1_000_000L, maxOf(clipDurationUs / 2, 0L))
            val estimation = estimateStitchParams(wideDec, ultraDec, targetUs, colorMatch)
            if (estimation == null) {
                Log.w(TAG, "Could not estimate stitch parameters; aborting stitch")
                return null
            }
            val affine = estimation.affine
            val colorParams = estimation.colorParams
            val mask = estimation.mask
            Log.i(TAG, "Stitch params estimated at ~${targetUs / 1000}ms; " +
                "colorMatch=$colorMatch paramsSet=${colorParams != null}")

            // Pass 2 needs to decode from the start again: recreate the decoders.
            try { wideDec.release() } catch (_: Exception) {}
            try { ultraDec.release() } catch (_: Exception) {}
            wideDec = VideoTrackDecoder(context, wideUri)
            ultraDec = VideoTrackDecoder(context, ultraUri)
            wideDec.start()
            ultraDec.start()

            // Audio track from the wide file (same timeline as its video).
            var audioFormat: MediaFormat? = null
            try {
                audioPfd = context.contentResolver.openFileDescriptor(wideUri, "r")
                audioExt = MediaExtractor()
                audioExt?.setDataSource(audioPfd!!.fileDescriptor)
                for (i in 0 until (audioExt?.trackCount ?: 0)) {
                    val fmt = audioExt!!.getTrackFormat(i)
                    if ((fmt.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                        audioExt!!.selectTrack(i)
                        audioFormat = fmt
                        break
                    }
                }
                if (audioFormat == null) {
                    try { audioExt?.release() } catch (_: Exception) {}
                    audioExt = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "No usable audio track: ${e.message}")
                try { audioExt?.release() } catch (_: Exception) {}
                audioExt = null
            }

            outUri = MediaStoreUtils.createPendingVideo(context, outputName, timestampMillis)
            outPfd = context.contentResolver.openFileDescriptor(outUri, "rw")
                ?: run {
                    MediaStoreUtils.delete(context.contentResolver, outUri)
                    return null
                }
            val muxer = MediaMuxer(outPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(wideDec.rotationDegrees)

            val encFormat = MediaFormat.createVideoFormat("video/avc", sbsW, sbsH).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps.coerceAtLeast(1_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                // The stitched frames are limited-range BT.601 YUV (OpenCV
                // I420/NV12). Say so explicitly: without these keys the
                // encoder wrote a GBR matrix flag and players showed wrong
                // colors even when the pixels were right.
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
            val encoder = MediaCodec.createEncoderByType("video/avc")
            try {
                encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                Log.e(TAG, "SBS encoder configure failed (NV12 unsupported?): ${e.message}", e)
                try { encoder.release() } catch (_: Exception) {}
                try { muxer.release() } catch (_: Exception) {}
                // outPfd is closed by the outer finally; just delete the entry.
                MediaStoreUtils.delete(context.contentResolver, outUri)
                return null
            }
            encoder.start()

            var videoTrack = -1
            var audioTrack = -1
            var muxerStarted = false
            var frames = 0
            var lastPtsUs = 0L
            var startPtsUs: Long? = null
            val halfFrameUs = 1_000_000L / (2L * fps)

            // Drain encoder output into the muxer. On format change the video
            // track (and audio track, if any) are added and the muxer starts.
            fun drainEncoder(endOfStream: Boolean) {
                var guard = 0
                while (guard++ < 200) {
                    val info = MediaCodec.BufferInfo()
                    val idx = encoder.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0L)
                    when {
                        idx >= 0 -> {
                            val outBuf = encoder.getOutputBuffer(idx)
                            if (outBuf != null && info.size > 0 && muxerStarted && videoTrack >= 0) {
                                try { muxer.writeSampleData(videoTrack, outBuf, info) } catch (e: Exception) {
                                    Log.w(TAG, "Muxer video write failed: ${e.message}")
                                }
                            }
                            encoder.releaseOutputBuffer(idx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                videoTrack = muxer.addTrack(encoder.outputFormat)
                                if (audioFormat != null) {
                                    audioTrack = muxer.addTrack(audioFormat)
                                }
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        else -> return
                    }
                    if (!endOfStream) return
                }
            }

            val audioBuf = ByteBuffer.allocate(256 * 1024)
            fun drainAudioUpTo(ptsUs: Long) {
                val ext = audioExt ?: return
                if (!muxerStarted || audioTrack < 0) return
                while (true) {
                    val sampleTime = ext.sampleTime
                    if (sampleTime < 0) return
                    val adj = (sampleTime - (startPtsUs ?: 0L)).coerceAtLeast(0L)
                    if (adj > ptsUs) return
                    audioBuf.clear()
                    val size = ext.readSampleData(audioBuf, 0)
                    if (size < 0) return
                    val flags = if (ext.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else 0
                    val info = MediaCodec.BufferInfo()
                    info.set(0, size, adj, flags)
                    try { muxer.writeSampleData(audioTrack, audioBuf, info) } catch (e: Exception) {
                        Log.w(TAG, "Muxer audio write failed: ${e.message}")
                    }
                    ext.advance()
                }
            }

            // Encode one SBS frame: warp the ultrawide with the precomputed
            // affine, apply the precomputed color match, assemble
            // side-by-side, convert to NV12, feed the encoder.
            fun emitSbsFrame(wideBgr: Mat, ultraAligned: Mat, ptsUs: Long) {
                var left: Mat = ultraAligned
                var right: Mat = wideBgr
                var leftOwned = false
                var rightOwned = false
                try {
                    val params = colorParams
                    if (params != null) {
                        when (colorMatch) {
                            ColorMatch.WIDE -> {
                                left = StereoSbs.applyColorMatchParams(ultraAligned, params)
                                leftOwned = true
                            }
                            ColorMatch.ULTRA -> {
                                right = StereoSbs.applyColorMatchParams(wideBgr, params)
                                rightOwned = true
                            }
                            ColorMatch.OFF -> { /* params is null in OFF mode; unreachable */ }
                        }
                    }
                    // CROSS: LEFT = ultra, RIGHT = wide. PARALLEL swaps the
                    // halves (LEFT = wide, RIGHT = ultra) — same eyes, same
                    // colors, just the other layout.
                    val sbs = if (sbsView == StereoSbs.SbsView.PARALLEL) {
                        StereoSbs.assembleSbsMat(right, left)
                    } else {
                        StereoSbs.assembleSbsMat(left, right)
                    } ?: return
                    try {
                        val nv12 = bgrToNv12Bytes(sbs)
                        val inIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx >= 0) {
                            val inBuf = encoder.getInputBuffer(inIdx)
                            if (inBuf != null && inBuf.capacity() >= nv12.size) {
                                inBuf.clear()
                                inBuf.put(nv12)
                                encoder.queueInputBuffer(inIdx, 0, nv12.size, ptsUs, 0)
                            } else {
                                Log.w(TAG, "Encoder input buffer too small; dropping a frame")
                            }
                        }
                    } finally {
                        try { sbs.release() } catch (_: Exception) {}
                    }
                    drainEncoder(endOfStream = false)
                } finally {
                    if (leftOwned) try { left.release() } catch (_: Exception) {}
                    if (rightOwned) try { right.release() } catch (_: Exception) {}
                }
            }

            // ---- Pass 2: stitch every frame with the precomputed parameters.
            var wideFrame = wideDec.nextFrame()
            var ultraFrame = ultraDec.nextFrame()
            var failed = false
            try {
                while (wideFrame != null && ultraFrame != null) {
                    val dPts = wideFrame.ptsUs - ultraFrame.ptsUs
                    when {
                        abs(dPts) <= halfFrameUs -> {
                            if (startPtsUs == null) startPtsUs = wideFrame.ptsUs
                            val relPts = (wideFrame.ptsUs - (startPtsUs ?: 0L)).coerceAtLeast(0L)
                            val ultraAligned = StereoSbs.warpUltraWithAffine(
                                ultraFrame.bgr, affine, wideW, wideH
                            )
                            emitSbsFrame(wideFrame.bgr, ultraAligned, relPts)
                            try { ultraFrame.bgr.release() } catch (_: Exception) {}
                            try { ultraAligned.release() } catch (_: Exception) {}
                            try { wideFrame.bgr.release() } catch (_: Exception) {}
                            // Mats released; null the refs before fetching the
                            // next frames so a throw in nextFrame() can't
                            // double-release them in the finally.
                            wideFrame = null
                            ultraFrame = null
                            frames++
                            lastPtsUs = relPts
                            if (frames % 15 == 0) {
                                onProgress?.invoke("Stitching…", frames, totalFrames)
                            }
                            drainAudioUpTo(relPts)
                            wideFrame = wideDec.nextFrame()
                            ultraFrame = ultraDec.nextFrame()
                        }
                        dPts < 0 -> {
                            // Wide is behind; drop its frame and catch up.
                            try { wideFrame.bgr.release() } catch (_: Exception) {}
                            wideFrame = null
                            wideFrame = wideDec.nextFrame()
                        }
                        else -> {
                            try { ultraFrame.bgr.release() } catch (_: Exception) {}
                            ultraFrame = null
                            ultraFrame = ultraDec.nextFrame()
                        }
                    }
                }
            } finally {
                wideFrame?.let { try { it.bgr.release() } catch (_: Exception) {} }
                ultraFrame?.let { try { it.bgr.release() } catch (_: Exception) {} }
                try { affine.release() } catch (_: Exception) {}
                try { mask?.release() } catch (_: Exception) {}
            }

            // End of stream: flush the encoder, then the rest of the audio.
            onProgress?.invoke("Saving…", totalFrames, totalFrames)
            try {
                val inIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    encoder.queueInputBuffer(
                        inIdx, 0, 0, lastPtsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Encoder EOS signal failed: ${e.message}")
            }
            drainEncoder(endOfStream = true)
            drainAudioUpTo(Long.MAX_VALUE)

            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            if (muxerStarted) {
                try { muxer.stop() } catch (e: Exception) {
                    Log.w(TAG, "Muxer stop failed: ${e.message}")
                }
            }
            try { muxer.release() } catch (_: Exception) {}
            // outPfd is closed by the outer finally.

            if (failed || frames == 0 || !muxerStarted) {
                MediaStoreUtils.delete(context.contentResolver, outUri)
                return null
            }
            MediaStoreUtils.finalizePending(context.contentResolver, outUri)
            return Result(outUri, frames, lastPtsUs)
        } catch (e: Exception) {
            Log.e(TAG, "Stitch failed: ${e.message}", e)
            outUri?.let {
                try { MediaStoreUtils.delete(context.contentResolver, it) } catch (_: Exception) {}
            }
            return null
        } finally {
            try { wideDec?.release() } catch (_: Exception) {}
            try { ultraDec?.release() } catch (_: Exception) {}
            try { audioExt?.release() } catch (_: Exception) {}
            try { audioPfd?.close() } catch (_: Exception) {}
            try { outPfd?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Pass 1: walk the two decoders forward (both must already be started)
     * and pick the first frame pair at or after [targetUs] — or the last pair
     * seen if the streams end first (short clip). Estimate the alignment +
     * color match from that pair. Returns null if no pair could be found or
     * alignment failed.
     *
     * The pair must NOT be the first frames: the lenses' AE/AWB are still
     * settling at recording start, so first-pair color parameters are stale
     * for the rest of the clip (they crushed every later frame on the Moto).
     */
    private fun estimateStitchParams(
        wideDec: VideoTrackDecoder,
        ultraDec: VideoTrackDecoder,
        targetUs: Long,
        colorMatch: ColorMatch
    ): StitchEstimation? {
        val fps = wideDec.frameRate.coerceIn(1, 120)
        val halfFrameUs = 1_000_000L / (2L * fps)

        var wideFrame: DecodedFrame? = wideDec.nextFrame()
        var ultraFrame: DecodedFrame? = ultraDec.nextFrame()
        // Latest paired Mats + the wide frame's PTS (ownership: ours until
        // handed to alignBgrPair, which takes ownership).
        var pairWide: Mat? = null
        var pairUltra: Mat? = null
        var pairPtsUs = 0L
        try {
            while (wideFrame != null && ultraFrame != null) {
                val dPts = wideFrame.ptsUs - ultraFrame.ptsUs
                when {
                    abs(dPts) <= halfFrameUs -> {
                        // Newer pair supersedes the earlier one.
                        try { pairWide?.release() } catch (_: Exception) {}
                        try { pairUltra?.release() } catch (_: Exception) {}
                        pairWide = wideFrame.bgr
                        pairUltra = ultraFrame.bgr
                        pairPtsUs = wideFrame.ptsUs
                        wideFrame = null
                        ultraFrame = null
                        if (pairPtsUs >= targetUs) break
                        wideFrame = wideDec.nextFrame()
                        ultraFrame = ultraDec.nextFrame()
                    }
                    dPts < 0 -> {
                        try { wideFrame.bgr.release() } catch (_: Exception) {}
                        wideFrame = null
                        wideFrame = wideDec.nextFrame()
                    }
                    else -> {
                        try { ultraFrame.bgr.release() } catch (_: Exception) {}
                        ultraFrame = null
                        ultraFrame = ultraDec.nextFrame()
                    }
                }
            }

            val wideIn = pairWide ?: return null
            val ultraIn = pairUltra ?: return null
            // alignBgrPair takes ownership of both Mats.
            pairWide = null
            pairUltra = null
            val aligned = StereoSbs.alignBgrPair(wideIn, ultraIn) ?: return null
            var mask: Mat? = null
            var params: StereoSbs.ColorMatchParams? = null
            try {
                if (colorMatch != ColorMatch.OFF) {
                    val m = StereoSbs.validityMask(aligned.ultraAligned)
                    if (Core.countNonZero(m) < (aligned.wideBgr.cols() * aligned.wideBgr.rows() * 0.01)) {
                        try { m.release() } catch (_: Exception) {}
                        mask = Mat(
                            aligned.wideBgr.rows(), aligned.wideBgr.cols(),
                            CvType.CV_8UC1, org.opencv.core.Scalar(255.0)
                        )
                    } else {
                        mask = m
                    }
                    params = when (colorMatch) {
                        ColorMatch.WIDE -> StereoSbs.computeColorMatchParams(
                            aligned.ultraAligned, aligned.wideBgr, requireNotNull(mask)
                        )
                        ColorMatch.ULTRA -> StereoSbs.computeColorMatchParams(
                            aligned.wideBgr, aligned.ultraAligned, requireNotNull(mask)
                        )
                        ColorMatch.OFF -> null
                    }
                }
                // Estimation owns the affine + mask now; release the frame Mats.
                val estimation = StitchEstimation(aligned.affine, params, mask)
                try { aligned.wideBgr.release() } catch (_: Exception) {}
                try { aligned.ultraAligned.release() } catch (_: Exception) {}
                return estimation
            } catch (e: Exception) {
                Log.w(TAG, "Stitch parameter estimation failed: ${e.message}", e)
                try { aligned.wideBgr.release() } catch (_: Exception) {}
                try { aligned.ultraAligned.release() } catch (_: Exception) {}
                try { aligned.affine.release() } catch (_: Exception) {}
                try { mask?.release() } catch (_: Exception) {}
                return null
            }
        } finally {
            wideFrame?.let { try { it.bgr.release() } catch (_: Exception) {} }
            ultraFrame?.let { try { it.bgr.release() } catch (_: Exception) {} }
            try { pairWide?.release() } catch (_: Exception) {}
            try { pairUltra?.release() } catch (_: Exception) {}
        }
    }

    /** One video track decoded to BGR frames. */
    private class VideoTrackDecoder(context: Context, uri: Uri) {
        private val pfd: ParcelFileDescriptor =
            context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Cannot open video file")
        private val extractor = MediaExtractor()
        private var decoder: MediaCodec? = null
        private var inputEos = false
        private var outputEos = false

        var width: Int = 0
            private set
        var height: Int = 0
            private set
        var frameRate: Int = 30
            private set
        var durationUs: Long = 0L
            private set
        var rotationDegrees: Int = 0
            private set

        private var mime: String = ""
        private var trackFormat: MediaFormat? = null
        private var colorFormat: Int =
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        private var stride: Int = 0
        private var sliceHeight: Int = 0

        init {
            extractor.setDataSource(pfd.fileDescriptor)
            var found = false
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val m = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("video/")) {
                    extractor.selectTrack(i)
                    mime = m
                    trackFormat = fmt
                    width = fmt.getInteger(MediaFormat.KEY_WIDTH, 0)
                    height = fmt.getInteger(MediaFormat.KEY_HEIGHT, 0)
                    frameRate = fmt.getInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    durationUs = fmt.getLong(MediaFormat.KEY_DURATION, 0L)
                    rotationDegrees = fmt.getInteger("rotation-degrees", 0)
                    found = true
                    break
                }
            }
            if (!found || width <= 0 || height <= 0) {
                throw IllegalStateException("No video track found")
            }
            stride = width
            sliceHeight = height
        }

        fun start() {
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()
            decoder = codec
        }

        /** Next decoded frame, or null at end of stream / on failure. */
        fun nextFrame(): DecodedFrame? {
            val codec = decoder ?: return null
            if (outputEos) return null
            var guard = 0
            while (guard++ < 2000) {
                if (!inputEos) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf == null) continue
                        inBuf.clear()
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(
                                inIdx, 0, sampleSize, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }
                val info = MediaCodec.BufferInfo()
                // Non-blocking output poll: this loop keeps feeding input, so a
                // long output timeout here would only stall the pump.
                val outIdx = codec.dequeueOutputBuffer(info, 0L)
                when {
                    outIdx >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEos = true
                            codec.releaseOutputBuffer(outIdx, false)
                            return null
                        }
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf == null || info.size <= 0) {
                            codec.releaseOutputBuffer(outIdx, false)
                            continue
                        }
                        val bgr = yuvBufferToBgr(
                            outBuf, width, height, stride, sliceHeight, colorFormat
                        )
                        codec.releaseOutputBuffer(outIdx, false)
                        if (bgr == null) continue
                        return DecodedFrame(bgr, info.presentationTimeUs)
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        colorFormat = fmt.getInteger(
                            MediaFormat.KEY_COLOR_FORMAT, colorFormat
                        )
                        stride = fmt.getInteger(MediaFormat.KEY_STRIDE, width)
                        sliceHeight = fmt.getInteger(MediaFormat.KEY_SLICE_HEIGHT, height)
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output yet; keep feeding (loop continues).
                    }
                    else -> {
                    }
                }
                if (inputEos && outputEos) return null
            }
            Log.w(TAG, "nextFrame gave up after $guard iterations")
            return null
        }

        fun release() {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }
    }

    /**
     * Convert a decoder output buffer (I420 planar or NV12 semi-planar YUV,
     * possibly with stride/slice-height padding) to a BGR Mat. Returns null
     * for unsupported color formats.
     */
    private fun yuvBufferToBgr(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int,
        sliceHeight: Int,
        colorFormat: Int
    ): Mat? {
        val isPlanar =
            colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        val isSemiPlanar =
            colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        if (!isPlanar && !isSemiPlanar) {
            Log.w(TAG, "Unsupported decoder color format: $colorFormat")
            return null
        }
        val ySize = width * height
        val total = ySize + ySize / 2
        val packed = ByteArray(total)
        try {
            if (stride == width && sliceHeight == height) {
                val dup = buffer.duplicate()
                if (dup.remaining() < total) return null
                dup.get(packed)
            } else {
                val src = ByteArray(buffer.duplicate().remaining())
                buffer.duplicate().get(src)
                val need = stride * sliceHeight * 3 / 2
                if (src.size < need) return null
                for (row in 0 until height) {
                    System.arraycopy(src, row * stride, packed, row * width, width)
                }
                val chromaRows = height / 2
                val chromaSrcOffset = stride * sliceHeight
                if (isSemiPlanar) {
                    for (row in 0 until chromaRows) {
                        System.arraycopy(
                            src, chromaSrcOffset + row * stride,
                            packed, ySize + row * width, width
                        )
                    }
                } else {
                    val chromaStride = stride / 2
                    val uSrcOffset = chromaSrcOffset
                    val vSrcOffset = chromaSrcOffset + chromaStride * chromaRows
                    for (row in 0 until chromaRows) {
                        System.arraycopy(
                            src, uSrcOffset + row * chromaStride,
                            packed, ySize + row * (width / 2), width / 2
                        )
                        System.arraycopy(
                            src, vSrcOffset + row * chromaStride,
                            packed, ySize + (ySize / 4) + row * (width / 2), width / 2
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "YUV copy failed: ${e.message}")
            return null
        }
        val yuv = Mat(height + height / 2, width, CvType.CV_8UC1)
        yuv.put(0, 0, packed)
        val bgr = Mat()
        Imgproc.cvtColor(
            yuv, bgr,
            if (isPlanar) Imgproc.COLOR_YUV2BGR_I420 else Imgproc.COLOR_YUV2BGR_NV12
        )
        yuv.release()
        return bgr
    }

    /** Convert a BGR Mat to NV12 bytes (Y plane + interleaved UV) for the encoder. */
    private fun bgrToNv12Bytes(bgr: Mat): ByteArray {
        val w = bgr.cols()
        val h = bgr.rows()
        val i420 = Mat()
        Imgproc.cvtColor(bgr, i420, Imgproc.COLOR_BGR2YUV_I420)
        val ySize = w * h
        val total = ySize + ySize / 2
        val packed = ByteArray(total)
        i420.get(0, 0, packed)
        i420.release()
        val nv12 = ByteArray(total)
        System.arraycopy(packed, 0, nv12, 0, ySize)
        val uvSize = ySize / 4
        for (i in 0 until uvSize) {
            nv12[ySize + i * 2] = packed[ySize + i]
            nv12[ySize + i * 2 + 1] = packed[ySize + uvSize + i]
        }
        return nv12
    }
}

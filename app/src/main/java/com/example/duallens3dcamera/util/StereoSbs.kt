package com.example.duallens3dcamera.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Core
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size as CvSize
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

/**
 * Stereo SBS helper:
 * - SBS output: LEFT = aligned/cropped ultrawide, RIGHT = wide (unchanged geometry)
 * - Alignment: Calib3d.estimateAffinePartial2D + RANSAC (rotation+scale+translation only)
 * - Feature detection restricted to inner 80% of ultrawide (mask) to avoid margin junk
 * - Output JPEG quality = 100
 *
 * Returns SBS JPEG bytes; caller saves to MediaStore + writes EXIF date taken.
 */
object StereoSbs {
    private const val TAG = "StereoSbs"

    /**
     * Which eye goes on which side of a side-by-side image/video.
     * CROSS (the original layout): LEFT = ultrawide, RIGHT = wide — view
     * cross-eyed. PARALLEL: LEFT = wide, RIGHT = ultrawide — view with eyes
     * parallel (or in a viewer); better for video, which is harder to flip
     * in post. Only the left/right placement changes; alignment and color
     * matching are identical either way.
     */
    enum class SbsView {
        CROSS, PARALLEL;

        companion object {
            fun fromSetting(value: String): SbsView =
                if (value == "parallel") PARALLEL else CROSS
        }
    }

    @Volatile private var lastGoodAffine6: DoubleArray? = null
    // The wide-frame size the cached affine was estimated for. An affine's
    // translation is in pixels, so reusing a photo's affine (e.g. 1024x768)
    // on video frames (e.g. 1920x1440) warps to the wrong place even though
    // its scale still looks plausible — the cache is only valid for the size
    // it was estimated at.
    @Volatile private var lastGoodAffineSize: Pair<Int, Int>? = null

    // report alignments params or not
    private const val DEBUG_AFFINE_LOGS = true

    // prevents the 1x affine from being reused right after a zoom-mode flip
    fun resetLastGoodAffine() {
        lastGoodAffine6 = null
        lastGoodAffineSize = null
    }

    private fun logAffine(label: String, affine: Mat) {
        if (!DEBUG_AFFINE_LOGS) return
        val m = DoubleArray(6)
        affine.get(0, 0, m)
        val scale = Math.hypot(m[0], m[3])
        val thetaDeg = Math.atan2(m[3], m[0]) * 180.0 / Math.PI
        val tx = m[2]
        val ty = m[5]
        Log.i(TAG, "Affine $label: scale=$scale thetaDeg=$thetaDeg tx=$tx ty=$ty")
    }

    private fun storeLastGoodAffine(affine: Mat, wideW: Int, wideH: Int) {
        val arr = DoubleArray(6)
        affine.get(0, 0, arr)
        lastGoodAffine6 = arr
        lastGoodAffineSize = Pair(wideW, wideH)
    }

    private fun loadLastGoodAffine(wideW: Int, wideH: Int): Mat? {
        if (lastGoodAffineSize != Pair(wideW, wideH)) return null
        val arr = lastGoodAffine6 ?: return null
        val m = Mat(2, 3, CvType.CV_64F)
        m.put(0, 0, *arr)
        return m
    }

    private val triedInit = AtomicBoolean(false)
    private val openCvReady = AtomicBoolean(false)

    private fun isAffinePlausible(
        affine: Mat,
        ultraW: Int,
        ultraH: Int,
        wideW: Int,
        wideH: Int,
        expectedScale: Double
    ): Boolean {
        if (affine.empty() || affine.rows() != 2 || affine.cols() != 3) return false

        val m = DoubleArray(6)
        affine.get(0, 0, m)

        // reject NaN/Inf
        if (m.any { it.isNaN() || it.isInfinite() }) return false

        val a00 = m[0]
        val a01 = m[1]
        val tx  = m[2]
        val a10 = m[3]
        val a11 = m[4]
        val ty  = m[5]

        // scale + rotation sanity
        val scale = Math.hypot(a00, a10) // sqrt(a00^2 + a10^2)
        val thetaDeg = Math.atan2(a10, a00) * 180.0 / Math.PI

        // ExpectedScale ~= 1 / ultra3aFraction (e.g. 1/0.5 = 2.0)
        // Allow some room but not insane values.
        val minScale = expectedScale * 0.75
        val maxScale = expectedScale * 1.35
        if (scale < minScale || scale > maxScale) return false

        // The two rear cameras shouldn’t differ by big rotation.
        if (abs(thetaDeg) > 15.0) return false

        // center mapping sanity (should land roughly inside/near the wide frame)
        val cxU = ultraW / 2.0
        val cyU = ultraH / 2.0
        val cxMapped = a00 * cxU + a01 * cyU + tx
        val cyMapped = a10 * cxU + a11 * cyU + ty

        val marginX = wideW * 0.35
        val marginY = wideH * 0.35
        if (cxMapped < -marginX || cxMapped > wideW + marginX) return false
        if (cyMapped < -marginY || cyMapped > wideH + marginY) return false

        // Strong check: the wide frame corners should back-project inside the ultrawide image.
        val det = a00 * a11 - a01 * a10
        if (abs(det) < 1e-8) return false

        val inv00 =  a11 / det
        val inv01 = -a01 / det
        val inv10 = -a10 / det
        val inv11 =  a00 / det

        fun backProject(xw: Double, yw: Double): Pair<Double, Double> {
            val x = xw - tx
            val y = yw - ty
            val xu = inv00 * x + inv01 * y
            val yu = inv10 * x + inv11 * y
            return Pair(xu, yu)
        }

        val marginU = 0.08 * min(ultraW.toDouble(), ultraH.toDouble()) // ~8% margin
        val corners = arrayOf(
            backProject(0.0, 0.0),
            backProject(wideW.toDouble(), 0.0),
            backProject(0.0, wideH.toDouble()),
            backProject(wideW.toDouble(), wideH.toDouble())
        )

        for ((xu, yu) in corners) {
            if (xu < -marginU || xu > ultraW + marginU) return false
            if (yu < -marginU || yu > ultraH + marginU) return false
        }

        return true
    }


    /** Load the OpenCV native library once (idempotent). Every OpenCV
     *  entry point must call this before allocating any Mat — a bare
     *  `Mat(...)` with the library unloaded throws UnsatisfiedLinkError,
     *  which is an Error, not an Exception, and kills the process.
     *  (0.77.2: direct mode hit exactly that because its YUV->BGR step
     *  allocated a Mat before anything had called this.) */
    fun ensureOpenCv(): Boolean {
        if (openCvReady.get()) return true
        if (!triedInit.compareAndSet(false, true)) return openCvReady.get()

        return try {
            val ok = OpenCVLoader.initDebug()
            openCvReady.set(ok)
            if (!ok) Log.e(TAG, "OpenCVLoader.initDebug() returned false")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "OpenCV init failed: ${t.message}", t)
            openCvReady.set(false)
            false
        }
    }

    /**
     * Emulate a 2x zoomed ultrawide for alignment WITHOUT changing camera capture:
     *  - center-crop to [cropFraction] of width/height (0.5 => 2x)
     *  - resize back to the original dimensions
     *
     * This keeps output dimensions identical (important for the existing expectedScale gates),
     * but narrows FoV like a real 2x zoom.
     */
    private fun centerCropThenResizeBackBgr(srcBgr: Mat, cropFraction: Float = 0.50f): Mat {
        val f = cropFraction.coerceIn(0.05f, 1.0f)
        if (f >= 0.999f) return srcBgr

        val outW = srcBgr.cols()
        val outH = srcBgr.rows()

        val cropW = max(1, (outW * f).toInt())
        val cropH = max(1, (outH * f).toInt())
        val x0 = ((outW - cropW) / 2).coerceAtLeast(0)
        val y0 = ((outH - cropH) / 2).coerceAtLeast(0)

        val roi = Rect(x0, y0, cropW, cropH)

        val cropped = srcBgr.submat(roi).clone()
        srcBgr.release()

        val resized = Mat()
        Imgproc.resize(
            cropped,
            resized,
            CvSize(outW.toDouble(), outH.toDouble()),
            0.0,
            0.0,
            Imgproc.INTER_CUBIC
        )
        cropped.release()
        return resized
    }

    data class Nv21Frame(
        val nv21: ByteArray,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int = 0
    )

    fun yuvToJpegBytes(
        frame: Nv21Frame,
        jpegQuality: Int = 100,
        maxOutputDim: Int = 6144
    ): ByteArray? {
        if (!ensureOpenCv()) return null

        var bgr: Mat? = null
        try {
            bgr = nv21ToBgrMat(frame.nv21, frame.width, frame.height) ?: return null
            bgr = rotateBgr(bgr!!, frame.rotationDegrees)
            bgr = downscaleIfNeeded(bgr!!, maxOutputDim)
            return encodeJpegBytes(bgr!!, jpegQuality)
        } finally {
            bgr?.release()
        }
    }

    /**
     * One aligned frame pair: the wide eye untouched, the ultra eye warped into
     * wide geometry, plus the 2x3 affine that maps ultra -> wide (kept so video
     * stitching can reuse one estimated affine for every frame instead of
     * re-running feature matching per frame). All three Mats are owned by the
     * caller and must be released.
     */
    data class AlignedFrames(
        val wideBgr: Mat,
        val ultraAligned: Mat,
        val affine: Mat
    )

    /**
     * Align an already-decoded BGR pair (e.g. video frames). Takes ownership of
     * both input Mats. Returns null when alignment could not be established.
     * The returned affine is in full-resolution coordinates of the wide frame.
     */
    fun alignBgrPair(
        wideBgrIn: Mat,
        ultraBgrIn: Mat,
        fallbackUltraOverlapFraction: Float = 0.66f
    ): AlignedFrames? {
        if (!ensureOpenCv()) {
            try { wideBgrIn.release() } catch (_: Exception) {}
            try { ultraBgrIn.release() } catch (_: Exception) {}
            return null
        }
        return alignBgrMats(
            wideBgrIn = wideBgrIn,
            ultraBgrIn = ultraBgrIn,
            zoom2xEnabled = false,
            fallbackUltraOverlapFraction = fallbackUltraOverlapFraction
        )
    }

    /**
     * Warp an ultrawide BGR frame into wide geometry with a previously estimated
     * affine (same interpolation rule as the photo path). The returned Mat is
     * owned by the caller.
     */
    fun warpUltraWithAffine(ultraBgr: Mat, affine: Mat, wideW: Int, wideH: Int): Mat {
        val m = DoubleArray(6)
        affine.get(0, 0, m)
        val scale = kotlin.math.hypot(m[0], m[3])
        val interp = if (scale > 1.01) Imgproc.INTER_CUBIC else Imgproc.INTER_LINEAR
        val out = Mat()
        Imgproc.warpAffine(
            ultraBgr,
            out,
            affine,
            CvSize(wideW.toDouble(), wideH.toDouble()),
            interp,
            Core.BORDER_CONSTANT,
            Scalar(0.0, 0.0, 0.0)
        )
        return out
    }

    /**
     * Core alignment: feature-match the ultrawide onto the wide, warp it, and
     * return both eyes plus the affine. Takes ownership of both input Mats
     * (they are downscaled/released inside as needed). Shared by the photo
     * SBS path (NV21 in) and the video stitcher (decoded BGR in).
     */
    private fun alignBgrMats(
        wideBgrIn: Mat,
        ultraBgrIn: Mat,
        zoom2xEnabled: Boolean = false,
        ultraInnerFractionForFeatures: Float = 0.80f,
        fallbackUltraOverlapFraction: Float = 0.66f,
        maxOutputDim: Int = 6144,
        featureMaxDim: Int = 1600,
        orbMaxFeatures: Int = 2000,
        ransacReprojThreshold: Double = 3.0,
        minInliers: Int = 35
    ): AlignedFrames? {
        var wideBgr: Mat? = wideBgrIn
        var ultraBgr: Mat? = ultraBgrIn
        var wideGray: Mat? = null
        var ultraGray: Mat? = null
        var wideSmall: Mat? = null
        var ultraSmall: Mat? = null
        var affineSmall: Mat? = null
        var affineFull: Mat? = null
        var ultraAligned: Mat? = null
        try {
            // Safety downscale to prevent pathological memory usage.
            wideBgr = downscaleIfNeeded(requireNotNull(wideBgr), maxOutputDim)
            ultraBgr = downscaleIfNeeded(requireNotNull(ultraBgr), maxOutputDim)

            // 2x mode: crop ultrawide to central region before alignment.
            // This narrows the UW FoV by ~2x so matching to wide2x reuses the same overlap heuristic.
            if (zoom2xEnabled) {
                Log.i(TAG, "2x mode: center-cropping ultra to simulate ~2x")
                ultraBgr = centerCropThenResizeBackBgr(requireNotNull(ultraBgr), cropFraction = 0.50f)
            }

            val wideW = requireNotNull(wideBgr).cols()
            val wideH = requireNotNull(wideBgr).rows()
            val ultraW = requireNotNull(ultraBgr).cols()
            val ultraH = requireNotNull(ultraBgr).rows()

            wideGray = Mat()
            ultraGray = Mat()
            Imgproc.cvtColor(requireNotNull(wideBgr), wideGray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(requireNotNull(ultraBgr), ultraGray, Imgproc.COLOR_BGR2GRAY)

            val scaleForFeatures = computeUniformScaleForMaxDim(
                maxDim = featureMaxDim,
                w1 = requireNotNull(wideGray).cols(),
                h1 = requireNotNull(wideGray).rows(),
                w2 = requireNotNull(ultraGray).cols(),
                h2 = requireNotNull(ultraGray).rows()
            )

            wideSmall = Mat()
            ultraSmall = Mat()
            if (scaleForFeatures < 0.999) {
                Imgproc.resize(
                    requireNotNull(wideGray),
                    wideSmall,
                    CvSize(
                        requireNotNull(wideGray).cols() * scaleForFeatures,
                        requireNotNull(wideGray).rows() * scaleForFeatures
                    ),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA
                )
                Imgproc.resize(
                    requireNotNull(ultraGray),
                    ultraSmall,
                    CvSize(
                        requireNotNull(ultraGray).cols() * scaleForFeatures,
                        requireNotNull(ultraGray).rows() * scaleForFeatures
                    ),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA
                )
            } else {
                // No downscale needed; copy so the rest of the code can assume wideSmall/ultraSmall are valid.
                requireNotNull(wideGray).copyTo(wideSmall)
                requireNotNull(ultraGray).copyTo(ultraSmall)
            }

            val expectedScale = 1.0 / fallbackUltraOverlapFraction.toDouble()

            // Estimate affine in the downscaled "feature" coordinate space.
            affineSmall = estimateUltraToWideAffineRansac(
                ultraGray = requireNotNull(ultraSmall),
                wideGray = requireNotNull(wideSmall),
                ultraInnerFraction = ultraInnerFractionForFeatures,
                nFeatures = orbMaxFeatures,
                ransacReprojThresholdPx = ransacReprojThreshold,
                minInliers = minInliers
            )

            // Reject obviously-bad affines before scaling up.
            if (affineSmall != null) {
                val ok = isAffinePlausible(
                    affine = requireNotNull(affineSmall),
                    ultraW = requireNotNull(ultraSmall).cols(),
                    ultraH = requireNotNull(ultraSmall).rows(),
                    wideW = requireNotNull(wideSmall).cols(),
                    wideH = requireNotNull(wideSmall).rows(),
                    expectedScale = expectedScale
                )
                if (!ok) {
                    Log.w(TAG, "Rejecting implausible affine from RANSAC; will fallback")
                    try { affineSmall?.release() } catch (_: Exception) {}
                    affineSmall = null
                }
            }

            // Scale affine back up to full-resolution (only translation scales).
            if (affineSmall != null) {
                val m = DoubleArray(6)
                requireNotNull(affineSmall).get(0, 0, m)

                val scaleBack = if (scaleForFeatures > 0.0) (1.0 / scaleForFeatures) else 1.0

                affineFull = Mat(2, 3, CvType.CV_64F)
                requireNotNull(affineFull).put(0, 0, m[0], m[1], m[2] * scaleBack)
                requireNotNull(affineFull).put(1, 0, m[3], m[4], m[5] * scaleBack)

                // Validate in full-res coordinate space too.
                val okFull = isAffinePlausible(
                    affine = requireNotNull(affineFull),
                    ultraW = ultraW,
                    ultraH = ultraH,
                    wideW = wideW,
                    wideH = wideH,
                    expectedScale = expectedScale
                )
                if (!okFull) {
                    Log.w(TAG, "Rejecting implausible full-res affine; will fallback")
                    try { affineFull?.release() } catch (_: Exception) {}
                    affineFull = null
                }
            }

            // Fallback chain: last good affine (same frame size only) ->
            // centered scale heuristic
            if (affineFull == null) {
                val last = loadLastGoodAffine(wideW, wideH)
                if (last != null && isAffinePlausible(last, ultraW, ultraH, wideW, wideH, expectedScale)) {
                    Log.w(TAG, "Using cached lastGoodAffine fallback")
                    affineFull = last
                } else {
                    try { last?.release() } catch (_: Exception) {}
                    Log.w(TAG, "Using centered-scale fallback affine")
                    affineFull = fallbackCenteredScaleAffine(
                        ultraWidth = ultraW,
                        ultraHeight = ultraH,
                        wideWidth = wideW,
                        wideHeight = wideH,
                        overlapFraction = fallbackUltraOverlapFraction
                    )
                }
            } else {
                // Cache the full-res affine for next time (same size only).
                storeLastGoodAffine(requireNotNull(affineFull), wideW, wideH)
            }

            if (DEBUG_AFFINE_LOGS) {
                logAffine("FINAL", requireNotNull(affineFull))
            }

            // Warp ultrawide into wide geometry.
            ultraAligned = warpUltraWithAffine(
                requireNotNull(ultraBgr),
                requireNotNull(affineFull),
                wideW,
                wideH
            )

            val result = AlignedFrames(
                wideBgr = requireNotNull(wideBgr),
                ultraAligned = requireNotNull(ultraAligned),
                affine = requireNotNull(affineFull)
            )
            // Hand ownership to the caller: null the locals so finally doesn't release them.
            wideBgr = null
            ultraAligned = null
            affineFull = null
            return result
        } catch (e: Exception) {
            Log.e(TAG, "alignBgrMats failed: ${e.message}", e)
            return null
        } finally {
            try { affineSmall?.release() } catch (_: Exception) {}
            try { affineFull?.release() } catch (_: Exception) {}
            try { wideGray?.release() } catch (_: Exception) {}
            try { ultraGray?.release() } catch (_: Exception) {}
            try { wideSmall?.release() } catch (_: Exception) {}
            try { ultraSmall?.release() } catch (_: Exception) {}
            try { ultraAligned?.release() } catch (_: Exception) {}
            try { wideBgr?.release() } catch (_: Exception) {}
            try { ultraBgr?.release() } catch (_: Exception) {}
        }
    }

    fun alignAndCreateSbsJpegBytes(
        wideFrame: Nv21Frame,
        ultraFrame: Nv21Frame,
        zoom2xEnabled: Boolean = false,
        ultraInnerFractionForFeatures: Float = 0.80f,
        fallbackUltraOverlapFraction: Float = 0.66f,
        maxOutputDim: Int = 6144,
        featureMaxDim: Int = 1600,
        orbMaxFeatures: Int = 2000,
        ransacReprojThreshold: Double = 3.0,
        minInliers: Int = 35,
        sbsView: SbsView = SbsView.CROSS
    ): ByteArray? {
        if (!ensureOpenCv()) return null

        var wideBgr: Mat? = null
        var ultraBgr: Mat? = null
        try {
            wideBgr = nv21ToBgrMat(wideFrame.nv21, wideFrame.width, wideFrame.height) ?: return null
            ultraBgr = nv21ToBgrMat(ultraFrame.nv21, ultraFrame.width, ultraFrame.height) ?: return null

            // Rotate to match what JPEG_ORIENTATION used to do.
            wideBgr = rotateBgr(requireNotNull(wideBgr), wideFrame.rotationDegrees)
            ultraBgr = rotateBgr(requireNotNull(ultraBgr), ultraFrame.rotationDegrees)

            // alignBgrMats takes ownership of both inputs: hand them over and
            // null the locals first so this finally can't double-release them.
            val wideIn = requireNotNull(wideBgr)
            val ultraIn = requireNotNull(ultraBgr)
            wideBgr = null
            ultraBgr = null
            val aligned = alignBgrMats(
                wideBgrIn = wideIn,
                ultraBgrIn = ultraIn,
                zoom2xEnabled = zoom2xEnabled,
                ultraInnerFractionForFeatures = ultraInnerFractionForFeatures,
                fallbackUltraOverlapFraction = fallbackUltraOverlapFraction,
                maxOutputDim = maxOutputDim,
                featureMaxDim = featureMaxDim,
                orbMaxFeatures = orbMaxFeatures,
                ransacReprojThreshold = ransacReprojThreshold,
                minInliers = minInliers
            ) ?: return null

            // Assemble SBS: CROSS -> LEFT = aligned ultra, RIGHT = wide;
            // PARALLEL -> LEFT = wide, RIGHT = aligned ultra.
            val sbs = if (sbsView == SbsView.PARALLEL) {
                assembleSbsMat(aligned.wideBgr, aligned.ultraAligned)
            } else {
                assembleSbsMat(aligned.ultraAligned, aligned.wideBgr)
            }
            if (sbs == null) {
                try { aligned.wideBgr.release() } catch (_: Exception) {}
                try { aligned.ultraAligned.release() } catch (_: Exception) {}
                try { aligned.affine.release() } catch (_: Exception) {}
                return null
            }
            try {
                return encodeJpegBytes(sbs, 100)
            } finally {
                try { sbs.release() } catch (_: Exception) {}
                try { aligned.wideBgr.release() } catch (_: Exception) {}
                try { aligned.ultraAligned.release() } catch (_: Exception) {}
                try { aligned.affine.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "alignAndCreateSbsJpegBytes(YUV) failed: ${e.message}", e)
            return null
        } finally {
            try { wideBgr?.release() } catch (_: Exception) {}
            try { ultraBgr?.release() } catch (_: Exception) {}
        }
    }

    /** Assemble an SBS Mat: LEFT = [left], RIGHT = [right]. Returned Mat is owned by the caller. */
    fun assembleSbsMat(left: Mat, right: Mat): Mat? {
        val h = left.rows()
        val w = left.cols()
        if (right.rows() != h || right.cols() != w) return null
        val sbs = Mat(h, w * 2, CvType.CV_8UC3)
        left.copyTo(sbs.submat(0, h, 0, w))
        right.copyTo(sbs.submat(0, h, w, w * 2))
        return sbs
    }

    /**
     * Per-channel color-transfer parameters (BGR order): out = in * alpha + beta.
     * Computed once (e.g. from the first frame pair of a video) and applied to
     * every frame so the match can't flicker.
     */
    data class ColorMatchParams(val alpha: DoubleArray, val beta: DoubleArray)

    /** Per-channel mean/std color transfer from [src] onto [ref]'s statistics,
     *  computed under [mask]. Scale is clamped to [0.25, 4.0]; near-flat source
     *  channels are shifted, not scaled. */
    fun computeColorMatchParams(src: Mat, ref: Mat, mask: Mat): ColorMatchParams {
        val srcMean = MatOfDouble()
        val srcStd = MatOfDouble()
        val refMean = MatOfDouble()
        val refStd = MatOfDouble()
        try {
            Core.meanStdDev(src, srcMean, srcStd, mask)
            Core.meanStdDev(ref, refMean, refStd, mask)
            val srcMeanArr = srcMean.toArray()
            val srcStdArr = srcStd.toArray()
            val refMeanArr = refMean.toArray()
            val refStdArr = refStd.toArray()
            val alpha = DoubleArray(3)
            val beta = DoubleArray(3)
            for (i in 0..2) {
                val mS = srcMeanArr[i]
                val sS = srcStdArr[i]
                val mR = refMeanArr[i]
                val sR = refStdArr[i]
                alpha[i] = if (sS > 1e-3) (sR / sS).coerceIn(0.25, 4.0) else 1.0
                beta[i] = mR - alpha[i] * mS
            }
            return ColorMatchParams(alpha, beta)
        } finally {
            try { srcMean.release() } catch (_: Exception) {}
            try { srcStd.release() } catch (_: Exception) {}
            try { refMean.release() } catch (_: Exception) {}
            try { refStd.release() } catch (_: Exception) {}
        }
    }

    /** Apply precomputed [ColorMatchParams] to [src]. Returned Mat is owned by the caller. */
    fun applyColorMatchParams(src: Mat, params: ColorMatchParams): Mat {
        val srcChannels = ArrayList<Mat>()
        Core.split(src, srcChannels)
        val outChannels = ArrayList<Mat>(3)
        for (i in 0..2) {
            val ch = Mat()
            srcChannels[i].convertTo(ch, CvType.CV_8U, params.alpha[i], params.beta[i])
            outChannels.add(ch)
        }
        val out = Mat()
        Core.merge(outChannels, out)
        srcChannels.forEach { try { it.release() } catch (_: Exception) {} }
        outChannels.forEach { try { it.release() } catch (_: Exception) {} }
        return out
    }

    /**
     * Color-matched SBS variants, built from an already-aligned SBS JPEG.
     * [sbsView] must match the layout the SBS was assembled with (CROSS:
     * LEFT = aligned ultra, RIGHT = wide; PARALLEL: the other way round) so
     * the halves are identified correctly and the variants keep the same
     * layout. The two lenses run independent AWB/AE, so the eyes can differ
     * a lot in white balance and brightness — on the Moto G Stylus 2026 the
     * wide is noticeably warmer and darker than the ultrawide. Each variant
     * color-transfers one eye onto the other's statistics (per-channel
     * mean/std matching, Reinhard-style), computed over the overlap region
     * only: the warped ultra's black borders are masked out of the
     * statistics so they don't drag the match down. The alignment is
     * identical to the plain SBS; only the colors change.
     *
     * Returns (matchedToWide, matchedToUltra):
     *  - matchedToWide: ultra eye adjusted to the wide eye's colors; wide untouched.
     *  - matchedToUltra: wide eye adjusted to the ultra eye's colors; ultra untouched.
     * Either entry is null if that variant could not be built.
     */
    fun createColorMatchedSbsVariants(
        sbsJpegBytes: ByteArray,
        sbsView: SbsView = SbsView.CROSS
    ): Pair<ByteArray?, ByteArray?> {
        if (!ensureOpenCv()) return Pair(null, null)

        var sbs: Mat? = null
        try {
            val buf = MatOfByte(*sbsJpegBytes)
            sbs = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR)
            buf.release()
            val sbsMat = sbs ?: return Pair(null, null)
            if (sbsMat.empty()) return Pair(null, null)
            val w = sbsMat.cols()
            val h = sbsMat.rows()
            if (w < 4 || w % 2 != 0 || h < 1) return Pair(null, null)
            val halfW = w / 2

            val leftHalf = sbsMat.submat(0, h, 0, halfW)
            val rightHalf = sbsMat.submat(0, h, halfW, w)
            // Identify the eyes by the layout the SBS was assembled with.
            val ultra = if (sbsView == SbsView.PARALLEL) rightHalf else leftHalf
            val wide = if (sbsView == SbsView.PARALLEL) leftHalf else rightHalf

            var mask = validityMask(ultra)
            if (Core.countNonZero(mask) < (halfW * h * 0.01)) {
                // Almost no valid overlap (e.g. a failed warp): fall back to the
                // whole frame so the stats are at least defined.
                try { mask.release() } catch (_: Exception) {}
                mask = Mat(h, halfW, CvType.CV_8UC1, Scalar(255.0))
            }

            val ultraToWide = matchColors(ultra, wide, mask)
            val wideToUltra = matchColors(wide, ultra, mask)
            try { mask.release() } catch (_: Exception) {}

            val matchedToWide = assembleEyesAndEncode(ultraToWide, wide, sbsView)
            val matchedToUltra = assembleEyesAndEncode(ultra, wideToUltra, sbsView)

            try { ultraToWide.release() } catch (_: Exception) {}
            try { wideToUltra.release() } catch (_: Exception) {}

            return Pair(matchedToWide, matchedToUltra)
        } catch (e: Exception) {
            Log.e(TAG, "createColorMatchedSbsVariants failed: ${e.message}", e)
            return Pair(null, null)
        } finally {
            try { sbs?.release() } catch (_: Exception) {}
        }
    }

    /** Assemble the two eyes into an SBS JPEG in [sbsView] layout. */
    private fun assembleEyesAndEncode(ultraEye: Mat, wideEye: Mat, sbsView: SbsView): ByteArray? =
        if (sbsView == SbsView.PARALLEL) {
            assembleAndEncode(wideEye, ultraEye)
        } else {
            assembleAndEncode(ultraEye, wideEye)
        }

    /** Mask of the warped ultra's valid (non-black-border) pixels: 255 where the
     *  pixel is not near-black, 0 where it is. */
    fun validityMask(ultraAligned: Mat): Mat {
        val gray = Mat()
        val mask = Mat()
        try {
            Imgproc.cvtColor(ultraAligned, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.threshold(gray, mask, 8.0, 255.0, Imgproc.THRESH_BINARY)
            return mask
        } finally {
            try { gray.release() } catch (_: Exception) {}
        }
    }

    /**
     * Per-channel mean/std color transfer: returns a new Mat with [src]'s colors
     * shifted to match [ref]'s, statistics computed under [mask] (same size as
     * src/ref). Delegates to [computeColorMatchParams] + [applyColorMatchParams].
     */
    private fun matchColors(src: Mat, ref: Mat, mask: Mat): Mat =
        applyColorMatchParams(src, computeColorMatchParams(src, ref, mask))

    private fun assembleAndEncode(left: Mat, right: Mat): ByteArray? {
        val sbs = assembleSbsMat(left, right) ?: return null
        try {
            return encodeJpegBytes(sbs, 100)
        } finally {
            try { sbs.release() } catch (_: Exception) {}
        }
    }

    private fun nv21ToBgrMat(nv21: ByteArray, width: Int, height: Int): Mat? {
        val expected = width * height * 3 / 2
        if (nv21.size < expected) {
            Log.e(TAG, "NV21 too small: got=${nv21.size} expected>=$expected")
            return null
        }
        val yuv = Mat(height + height / 2, width, CvType.CV_8UC1)
        yuv.put(0, 0, nv21)
        val bgr = Mat()
        Imgproc.cvtColor(yuv, bgr, Imgproc.COLOR_YUV2BGR_NV21)
        yuv.release()
        return bgr
    }

    private fun rotateBgr(src: Mat, rotationDegrees: Int): Mat {
        val deg = ((rotationDegrees % 360) + 360) % 360
        if (deg == 0) return src
        val dst = Mat()
        when (deg) {
            90 -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, dst, Core.ROTATE_180)
            270 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> {
                Log.w(TAG, "Unexpected rotation=$deg; leaving unrotated")
                src.copyTo(dst)
            }
        }
        src.release()
        return dst
    }

    private fun downscaleIfNeeded(src: Mat, maxDim: Int): Mat {
        val w = src.cols()
        val h = src.rows()
        val m = max(w, h)
        if (m <= maxDim) return src
        val scale = maxDim.toDouble() / m.toDouble()
        val dst = Mat()
        Imgproc.resize(src, dst, CvSize(w * scale, h * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        src.release()
        return dst
    }

    private fun encodeJpegBytes(bgr: Mat, quality: Int): ByteArray? {
        val out = MatOfByte()
        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality.coerceIn(0, 100))
        return try {
            val ok = Imgcodecs.imencode(".jpg", bgr, out, params)
            if (!ok) null else out.toArray()
        } finally {
            params.release()
            out.release()
        }
    }

    // -------------------- Alignment internals --------------------

    private fun estimateUltraToWideAffineRansac(
        ultraGray: Mat,
        wideGray: Mat,
        ultraInnerFraction: Float,
        // More matches / stability: ORB nFeatures
        nFeatures: Int = 2000, // 900
        // ratioTest -> Lower = stricter = fewer wrong affines
        // (but may reduce matches in low-texture scenes)
        ratioTest: Float = 0.70f, // 0.75f
        // ransacReprojThresholdPx influences how “tight” inliers must be during RANSAC
        ransacReprojThresholdPx: Double = 2.0, // 3.0 // if decreasing, increase maxIters too
        maxIters: Long = 3000, // 2000
        confidence: Double = 0.99,
        refineIters: Long = 20, // 10
        minInliers: Int = 35
    ): Mat? {
        val orb = ORB.create(nFeatures)

        // Mask only the inner 80% (or whatever was passed) of the ULTRAWIDE image.
        val mask = Mat.zeros(ultraGray.size(), CvType.CV_8U)
        val marginX = ((1.0f - ultraInnerFraction) * ultraGray.cols() / 2.0f).toInt().coerceAtLeast(1)
        val marginY = ((1.0f - ultraInnerFraction) * ultraGray.rows() / 2.0f).toInt().coerceAtLeast(1)
        val x0 = marginX
        val y0 = marginY
        val x1 = (ultraGray.cols() - marginX - 1).coerceAtLeast(x0 + 1)
        val y1 = (ultraGray.rows() - marginY - 1).coerceAtLeast(y0 + 1)
        Imgproc.rectangle(
            mask,
            Point(x0.toDouble(), y0.toDouble()),
            Point(x1.toDouble(), y1.toDouble()),
            Scalar(255.0),
            -1
        )

        val kpUltra = MatOfKeyPoint()
        val kpWide = MatOfKeyPoint()
        val descUltra = Mat()
        val descWide = Mat()

        try {
            orb.detectAndCompute(ultraGray, mask, kpUltra, descUltra)
            orb.detectAndCompute(wideGray, Mat(), kpWide, descWide)

            if (descUltra.empty() || descWide.empty()) return null

            val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
            val knnMatches = ArrayList<MatOfDMatch>()
            matcher.knnMatch(descUltra, descWide, knnMatches, 2)

            val good = ArrayList<DMatch>(knnMatches.size)
            for (m in knnMatches) {
                val arr = m.toArray()
                if (arr.size < 2) continue
                val best = arr[0]
                val second = arr[1]
                if (best.distance < ratioTest * second.distance) {
                    good.add(best)
                }
            }
            if (good.size < 8) return null

            good.sortBy { it.distance }
            val capped = good.take(kotlin.math.min(good.size, 250))

            val ultraPts = ArrayList<Point>(capped.size)
            val widePts = ArrayList<Point>(capped.size)

            val ultraKps = kpUltra.toArray()
            val wideKps = kpWide.toArray()

            for (dm in capped) {
                ultraPts.add(ultraKps[dm.queryIdx].pt)
                widePts.add(wideKps[dm.trainIdx].pt)
            }

            val from = MatOfPoint2f(*ultraPts.toTypedArray())
            val to = MatOfPoint2f(*widePts.toTypedArray())
            val inliers = Mat()

            val affine = Calib3d.estimateAffinePartial2D(
                from,
                to,
                inliers,
                Calib3d.RANSAC,
                ransacReprojThresholdPx,
                maxIters,
                confidence,
                refineIters
            )

            val inlierCount = try { Core.countNonZero(inliers) } catch (_: Exception) { -1 }
            if (affine.empty() || inlierCount < minInliers) return null

            return affine
        } finally {
            try { mask.release() } catch (_: Exception) {}
            try { kpUltra.release() } catch (_: Exception) {}
            try { kpWide.release() } catch (_: Exception) {}
            try { descUltra.release() } catch (_: Exception) {}
            try { descWide.release() } catch (_: Exception) {}
        }
    }

    private fun fallbackCenteredScaleAffine(
        ultraWidth: Int,
        ultraHeight: Int,
        wideWidth: Int,
        wideHeight: Int,
        overlapFraction: Float
    ): Mat {
        val frac = overlapFraction.coerceIn(0.20f, 0.95f)
        val scale = 1.0 / frac

        val cxU = ultraWidth / 2.0
        val cyU = ultraHeight / 2.0
        val cxW = wideWidth / 2.0
        val cyW = wideHeight / 2.0

        val tx = cxW - scale * cxU
        val ty = cyW - scale * cyU

        val m = Mat(2, 3, CvType.CV_64F)
        m.put(0, 0, scale, 0.0, tx)
        m.put(1, 0, 0.0, scale, ty)
        return m
    }

    // -------------------- decode + scaling helpers --------------------

    private fun decodeJpegSafely(jpeg: ByteArray, maxDim: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null

            val sample = computeInSampleSize(w, h, maxDim)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun computeInSampleSize(srcW: Int, srcH: Int, maxDim: Int): Int {
        if (maxDim <= 0 || maxDim == Int.MAX_VALUE) return 1
        var sample = 1
        var w = srcW
        var h = srcH
        while (max(w, h) > maxDim) {
            sample *= 2
            w /= 2
            h /= 2
            if (sample >= 128) break
        }
        return sample.coerceAtLeast(1)
    }

    private fun computeUniformScaleForMaxDim(
        maxDim: Int,
        w1: Int,
        h1: Int,
        w2: Int,
        h2: Int
    ): Double {
        if (maxDim <= 0 || maxDim == Int.MAX_VALUE) return 1.0
        val m = max(max(w1, h1), max(w2, h2))
        if (m <= 0) return 1.0
        return min(1.0, maxDim.toDouble() / m.toDouble())
    }
}

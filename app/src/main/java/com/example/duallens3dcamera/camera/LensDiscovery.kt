package com.example.duallens3dcamera.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.util.SizeF
import android.view.Surface
import kotlin.math.abs

/**
 * Small, self-contained lens discovery for a rear LOGICAL_MULTI_CAMERA.
 *
 * We need:
 *  - The logical rear camera ID to open.
 *  - The physical ID for the 1x wide and the ultrawide.
 *  - Optionally, the physical ID that represents the 2x remosaic/crop of the wide.
 *
 * We also bake in one intentionally-simple device heuristic:
 *  - Pixels: assume portrait use + wide is the RIGHT-eye image.
 *  - Non-Pixels (Samsung target): assume landscape use + wide is the LEFT-eye image.
 *
 * MOTO (dual-device) MODE:
 *  Some Motorola phones (e.g. Moto G Stylus 2026, device "lamy") expose NO logical
 *  multi-camera at all: cameraIdList is just "0" (wide) and "1" (front), and neither
 *  camera reports LOGICAL_MULTI_CAMERA. The ultrawide exists as a HIDDEN camera ID
 *  ("2", f=2.13mm) that is not in cameraIdList but CAN be opened directly — the
 *  3DDualCam diagnostics prove ID 0 + ID 2 open together. In that case we return a
 *  DUAL_DEVICE rig: wideId/ultraId are standalone camera IDs to open as two separate
 *  CameraDevices, and logicalRearId is set to the wide ID (there is no logical camera).
 *  The controller must NOT use OutputConfiguration.setPhysicalCameraId() in this mode.
 */
object LensDiscovery {

    private const val TAG = "LensDiscovery"

    /** How the two lenses are opened. LOGICAL = one logical camera, two physical streams
     *  (Pixel/Samsung). DUAL_DEVICE = two standalone CameraDevices (Moto, no logical camera). */
    enum class RigMode { LOGICAL, DUAL_DEVICE }

    data class StereoRig(
        val logicalRearId: String,
        val wide1xId: String,
        val ultraId: String,
        val wide2xId: String?,
        /** Pixel or (assumed) Samsung */
        val phoneIsAPixel: Boolean,
        /** Pixel => portrait. Samsung => landscape. */
        val assumedDisplayRotation: Int,
        val mode: RigMode = RigMode.LOGICAL,
        /** Focal lengths (mm) of the wide / ultrawide, when known. */
        val wideFocalMm: Float? = null,
        val ultraFocalMm: Float? = null
    ) {
        val isDualDevice: Boolean get() = mode == RigMode.DUAL_DEVICE
    }

    fun isPixelDevice(): Boolean {
        // Examples:
        //  - "Pixel 7", "Pixel 7 Pro", "Pixel 8 Pro", "Pixel 9a", etc.
        return Build.MODEL?.contains("Pixel", ignoreCase = true) == true
    }

    fun isMotoStylusDevice(): Boolean {
        // Build.MODEL on the 2026 Stylus is "moto g stylus - 2026".
        return Build.MODEL?.contains("moto g stylus", ignoreCase = true) == true
    }

    /**
     * Discovers a usable rear stereo rig (ultrawide + wide) from Camera2 metadata.
     * Returns null if we can't find a logical multi-camera exposing 2+ physical cameras
     * AND we can't find a hidden dual-rear pair to open as two standalone devices.
     *
     * Order: on a Moto G Stylus we try the hidden dual-device scan first (it has no
     * logical camera by design); everywhere else we try the logical path first and
     * fall back to the hidden scan, so Pixel/Samsung behaviour is unchanged.
     */
    fun discoverStereoRig(cameraManager: CameraManager): StereoRig? {
        if (isMotoStylusDevice()) {
            scanHiddenRearRig(cameraManager)?.let {
                Log.i(TAG, "Moto dual-device rig: wide=${it.wide1xId} ultra=${it.ultraId}")
                return it
            }
            Log.w(TAG, "Moto Stylus: hidden dual-device scan failed, trying logical path")
        }

        discoverLogicalRig(cameraManager)?.let { return it }

        // Fallback for any other phone whose OEM hides the ultrawide: if two rear
        // cameras can be found by scanning IDs, open them as two standalone devices.
        return scanHiddenRearRig(cameraManager)?.also {
            Log.i(TAG, "Hidden dual-device rig (fallback): wide=${it.wide1xId} ultra=${it.ultraId}")
        }
    }

    private fun discoverLogicalRig(cameraManager: CameraManager): StereoRig? {
        val isPixel = isPixelDevice()

        val logicalRearId = findBestLogicalRearCameraId(cameraManager) ?: run {
            Log.e(TAG, "No rear LOGICAL_MULTI_CAMERA found")
            return null
        }

        val logicalChars = cameraManager.getCameraCharacteristics(logicalRearId)
        val physicalIds = logicalChars.physicalCameraIds
        if (physicalIds.size < 2) {
            Log.e(TAG, "Logical rear camera $logicalRearId exposes <2 physical IDs: $physicalIds")
            return null
        }

        val infos = physicalIds.mapNotNull { pid -> readPhysicalInfo(cameraManager, pid) }
        if (infos.size < 2) {
            Log.e(TAG, "Could not read physical lens metadata for enough cameras under $logicalRearId")
            return null
        }

        // Group physical cameras by focal length (crop/remosaic variants share focal length).
        val focalGroups = groupByApproxFocalMm(infos)
        if (focalGroups.size < 2) {
            Log.e(TAG, "Need at least 2 focal groups (ultra + wide). Found: ${focalGroups.size}")
            return null
        }

        // Typical ordering: ultrawide (smallest focal), then wide (next), then tele...
        val ultraGroup = focalGroups[0]
        val wideGroup = focalGroups[1]

        val ultra = requireNotNull(ultraGroup.maxByOrNull { it.areaMm2 })
        val wideFull = requireNotNull(wideGroup.maxByOrNull { it.areaMm2 })
        val wide2x = findHalfSizeVariant(full = wideFull, group = wideGroup)

        Log.i(TAG, "logicalRearId=$logicalRearId")
        Log.i(TAG, "ultra=${ultra.id} focal=${ultra.focalMm}mm size=${ultra.physicalSize.width}x${ultra.physicalSize.height}mm")
        Log.i(TAG, "wide1x=${wideFull.id} focal=${wideFull.focalMm}mm size=${wideFull.physicalSize.width}x${wideFull.physicalSize.height}mm")
        Log.i(TAG, "wide2x=${wide2x?.id ?: "<none>"}")

        return StereoRig(
            logicalRearId = logicalRearId,
            wide1xId = wideFull.id,
            ultraId = ultra.id,
            wide2xId = wide2x?.id,
            phoneIsAPixel = isPixel,
            assumedDisplayRotation = if (isPixel) Surface.ROTATION_0 else Surface.ROTATION_90,
            mode = RigMode.LOGICAL,
            wideFocalMm = wideFull.focalMm,
            ultraFocalMm = ultra.focalMm
        )
    }

    /**
     * DUAL_DEVICE discovery for phones with no logical multi-camera (Moto G Stylus 2026).
     *
     * Scans camera IDs "0".."20" with getCameraCharacteristics() — IDs not in
     * cameraIdList ("hidden" cameras) are still readable/openable on these phones, so
     * a try/catch per ID is the discovery mechanism. Keeps back-facing cameras only
     * and classifies by focal length exactly like the logical path: smallest focal
     * group = ultrawide, next = wide.
     *
     * Verified on Moto G Stylus 2026 (lamy): ID 0 wide f=5.56mm, ID 2 ultrawide
     * f=2.13mm (hidden), ID 1 front f=2.87mm (ignored: front-facing). ID 0 + ID 2
     * were confirmed to open together. Do NOT gate on getConcurrentCameraIds() — it
     * only lists front-camera combos (ID1, ID0+ID1) on this phone and omits 0+2.
     */
    fun scanHiddenRearRig(cameraManager: CameraManager): StereoRig? {
        val infos = ArrayList<PhysicalInfo>()
        for (n in 0..20) {
            val id = n.toString()
            val info = try {
                val c = cameraManager.getCameraCharacteristics(id)
                val facing = c.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
                readPhysicalInfo(cameraManager, id)
            } catch (_: Exception) {
                null
            }
            if (info != null) infos.add(info)
        }
        if (infos.size < 2) {
            Log.e(TAG, "Hidden scan found <2 rear cameras: ${infos.map { it.id }}")
            return null
        }

        val focalGroups = groupByApproxFocalMm(infos)
        if (focalGroups.size < 2) {
            Log.e(TAG, "Hidden scan: need at least 2 focal groups, found ${focalGroups.size}")
            return null
        }

        val ultra = requireNotNull(focalGroups[0].maxByOrNull { it.areaMm2 })
        val wideFull = requireNotNull(focalGroups[1].maxByOrNull { it.areaMm2 })

        Log.i(TAG, "Hidden dual-device rig: wide=${wideFull.id} focal=${wideFull.focalMm}mm, " +
            "ultra=${ultra.id} focal=${ultra.focalMm}mm")
        // Lenses are stacked vertically on the back of these phones: landscape use,
        // like Samsung. No 2x physical camera exists on the Moto.
        // logicalRearId is set to the wide ID — there is no logical camera; the
        // controller opens wideId and ultraId as two standalone CameraDevices.
        return StereoRig(
            logicalRearId = wideFull.id,
            wide1xId = wideFull.id,
            ultraId = ultra.id,
            wide2xId = null,
            phoneIsAPixel = false,
            assumedDisplayRotation = Surface.ROTATION_90,
            mode = RigMode.DUAL_DEVICE,
            wideFocalMm = wideFull.focalMm,
            ultraFocalMm = ultra.focalMm
        )
    }

    // -----------------------------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------------------------

    private data class PhysicalInfo(
        val id: String,
        val focalMm: Float,
        val physicalSize: SizeF
    ) {
        val areaMm2: Float = physicalSize.width * physicalSize.height
    }

    private fun findBestLogicalRearCameraId(cameraManager: CameraManager): String? {
        val ids = cameraManager.cameraIdList

        var bestId: String? = null
        var bestPhysicalCount = -1

        for (id in ids) {
            val c = try {
                cameraManager.getCameraCharacteristics(id)
            } catch (_: Exception) {
                continue
            }

            val facing = c.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
            if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) continue

            val physCount = c.physicalCameraIds.size
            if (physCount < 2) continue

            // Prefer the one with the most physical cameras. Tie-break: prefer cameraId "0".
            val isBetter = when {
                physCount > bestPhysicalCount -> true
                physCount == bestPhysicalCount && bestId != "0" && id == "0" -> true
                else -> false
            }
            if (isBetter) {
                bestId = id
                bestPhysicalCount = physCount
            }
        }

        return bestId
    }

    private fun readPhysicalInfo(cameraManager: CameraManager, physicalId: String): PhysicalInfo? {
        return try {
            val c = cameraManager.getCameraCharacteristics(physicalId)
            val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

            val focal = focals?.firstOrNull()
            if (focal == null || size == null) return null

            PhysicalInfo(id = physicalId, focalMm = focal, physicalSize = size)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Groups physical cameras by focal length using a small epsilon.
     * Crop/remosaic variants share (roughly) the same focal length.
     */
    private fun groupByApproxFocalMm(
        infos: List<PhysicalInfo>,
        focalEpsilonMm: Float = 0.25f
    ): List<List<PhysicalInfo>> {
        val sorted = infos.sortedBy { it.focalMm }
        val out = ArrayList<MutableList<PhysicalInfo>>()

        for (info in sorted) {
            val last = out.lastOrNull()
            if (last == null) {
                out.add(mutableListOf(info))
                continue
            }
            val base = last[0].focalMm
            if (abs(info.focalMm - base) <= focalEpsilonMm) {
                last.add(info)
            } else {
                out.add(mutableListOf(info))
            }
        }

        return out
    }

    /**
     * For a focal-length group (e.g. the "wide" group), detect a half-physical-size variant.
     * Pixels expose the 2x remosaic/crop this way.
     */
    private fun findHalfSizeVariant(full: PhysicalInfo, group: List<PhysicalInfo>): PhysicalInfo? {
        val fullW = full.physicalSize.width
        val fullH = full.physicalSize.height
        if (fullW <= 0f || fullH <= 0f) return null

        var best: PhysicalInfo? = null
        var bestScore = Float.MAX_VALUE

        for (c in group) {
            if (c.id == full.id) continue

            val rw = c.physicalSize.width / fullW
            val rh = c.physicalSize.height / fullH

            // we expect ~0.5x in each dimension (=> ~0.25 area)
            val score = abs(rw - 0.5f) + abs(rh - 0.5f)
            val closeEnough = abs(rw - 0.5f) <= 0.12f && abs(rh - 0.5f) <= 0.12f
            if (closeEnough && score < bestScore) {
                best = c
                bestScore = score
            }
        }

        return best
    }
}
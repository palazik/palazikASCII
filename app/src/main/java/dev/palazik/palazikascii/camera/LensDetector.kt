package dev.palazik.palazikascii.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Represents a detected physical camera lens on the device.
 */
data class DetectedLens(
    val cameraId: String,
    val lensType: LensType,
    val focalLength: Float,
    val isFront: Boolean,
) {
    val label: String get() = if (isFront) "Front" else lensType.label
}

enum class LensType(val label: String) {
    ULTRAWIDE("Ultrawide"),
    MAIN("Main"),
    TELEPHOTO("Telephoto"),
    FRONT("Front"),
    UNKNOWN("Lens"),
}

object LensDetector {

    private const val TAG = "LensDetector"

    /**
     * Categorisation strategy:
     *
     * Pass 1 — collect ALL back + front cameras, skip nothing except truly broken sensors.
     *   We intentionally allow LEGACY hardware level here because many OEM ultrawide
     *   cameras (including Xiaomi SM8350) are exposed as LEGACY.
     *
     * Pass 2 — relative classification by focal length within back cameras:
     *   - Sort back cameras by focal length ascending.
     *   - Shortest focal ≤ 60 % of the median  → ULTRAWIDE
     *   - Middle cluster                         → MAIN  (also confirmed by wide aperture)
     *   - Longest focal ≥ 160 % of the median   → TELEPHOTO
     *   - Everything else                        → MAIN (safe fallback)
     *
     * This relative approach is immune to OEM focal-length quirks and works on
     * devices where the ultrawide reports focal ~2.2 mm instead of the expected < 2.0 mm.
     *
     * Pass 3 — deduplicate by (lensType, rounded focal) to avoid duplicate physical
     *   sensors exposed under multiple logical IDs, then sort UW → Main → Tele → Front.
     */
    fun detectLenses(context: Context): List<DetectedLens> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        data class RawLens(
            val cameraId: String,
            val minFocal: Float,
            val minAperture: Float,
            val isFront: Boolean,
        )

        val raw = mutableListOf<RawLens>()

        for (cameraId in manager.cameraIdList) {
            try {
                val chars = manager.getCameraCharacteristics(cameraId)

                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue

                // Allow LEGACY — many ultrawide sensors on Xiaomi/Samsung are LEGACY.
                // Only skip sensors that have NO focal length info at all (depth / TOF sensors).
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.takeIf { it.isNotEmpty() } ?: continue

                val minFocal = focalLengths.minOrNull() ?: continue

                // TOF / depth sensors typically report focal = 0 or very high values with
                // no aperture info — skip them.
                if (minFocal <= 0f) continue

                val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                val minAperture = apertures?.minOrNull() ?: Float.MAX_VALUE

                val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT

                raw.add(RawLens(cameraId, minFocal, minAperture, isFront))

                Log.d(TAG, "Raw lens $cameraId | front=$isFront | focal=${minFocal}mm | aperture=f${minAperture}")

            } catch (e: Exception) {
                Log.w(TAG, "Skipping camera $cameraId: ${e.message}")
            }
        }

        // ── Relative classification for back cameras ──────────────────────────
        val backCameras = raw.filter { !it.isFront }.sortedBy { it.minFocal }

        // Median focal length among back cameras
        val medianFocal: Float = if (backCameras.isEmpty()) 3.5f else {
            val mid = backCameras.size / 2
            if (backCameras.size % 2 == 0)
                (backCameras[mid - 1].minFocal + backCameras[mid].minFocal) / 2f
            else
                backCameras[mid].minFocal
        }

        fun classifyBack(r: RawLens): LensType = when {
            r.minFocal <= medianFocal * 0.70f -> LensType.ULTRAWIDE    // significantly shorter
            r.minFocal >= medianFocal * 1.50f -> LensType.TELEPHOTO    // significantly longer
            r.minAperture < 2.0f              -> LensType.MAIN         // wide aperture = main
            else                              -> LensType.MAIN
        }

        Log.d(TAG, "Back cameras: ${backCameras.size}, median focal: ${medianFocal}mm")

        val lenses = raw.map { r ->
            val lensType = if (r.isFront) LensType.FRONT else classifyBack(r)
            Log.d(TAG, "Classified ${r.cameraId}: focal=${r.minFocal}mm → $lensType")
            DetectedLens(
                cameraId    = r.cameraId,
                lensType    = lensType,
                focalLength = r.minFocal,
                isFront     = r.isFront,
            )
        }

        // ── Deduplicate: keep one per (lensType) preferring smallest focal ────
        // Group back cameras by type, pick the best candidate per group.
        // Front cameras: allow multiple only if focal lengths differ meaningfully.
        val typeOrder = listOf(
            LensType.ULTRAWIDE,
            LensType.MAIN,
            LensType.TELEPHOTO,
            LensType.FRONT,
            LensType.UNKNOWN,
        )

        val deduplicated = lenses
            .groupBy { it.lensType }
            .flatMap { (type, group) ->
                if (type == LensType.FRONT) {
                    // Keep all distinct front cameras (some devices have wide + narrow front)
                    group.distinctBy { (it.focalLength * 10).toInt() }
                } else {
                    // For back lenses, one per type — prefer smallest focal in group
                    listOf(group.minByOrNull { it.focalLength }!!)
                }
            }
            .sortedWith(compareBy({ typeOrder.indexOf(it.lensType) }, { it.focalLength }))

        Log.d(TAG, "Final lenses: ${deduplicated.map { "${it.lensType}@${it.focalLength}mm(id=${it.cameraId})" }}")
        return deduplicated
    }
}
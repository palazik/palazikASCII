package dev.palazik.palazikascii.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

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

                // Do NOT filter by LEGACY — ultrawide cameras on Xiaomi/OnePlus/Samsung
                // are often reported as LEGACY and would be incorrectly skipped.
                // Only skip sensors with no focal length info (TOF/depth sensors).
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.takeIf { it.isNotEmpty() } ?: continue

                val minFocal = focalLengths.minOrNull() ?: continue
                if (minFocal <= 0f) continue  // depth/TOF sensor

                val minAperture = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    ?.minOrNull() ?: Float.MAX_VALUE

                val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT

                raw.add(RawLens(cameraId, minFocal, minAperture, isFront))
                Log.d(TAG, "Raw: id=$cameraId front=$isFront focal=${minFocal}mm aperture=f$minAperture")

            } catch (e: Exception) {
                Log.w(TAG, "Skipping $cameraId: ${e.message}")
            }
        }

        // ── Relative classification for back cameras ──────────────────────────
        // Sort by focal length, compute median, then classify relative to it.
        // This avoids hardcoded thresholds that break across OEMs.
        val backCameras = raw.filter { !it.isFront }.sortedBy { it.minFocal }

        val medianFocal: Float = when {
            backCameras.isEmpty() -> 4.0f
            backCameras.size % 2 == 1 -> backCameras[backCameras.size / 2].minFocal
            else -> (backCameras[backCameras.size / 2 - 1].minFocal +
                     backCameras[backCameras.size / 2].minFocal) / 2f
        }

        Log.d(TAG, "Back cameras: ${backCameras.size}, median focal: ${medianFocal}mm")

        fun classifyBack(r: RawLens): LensType = when {
            r.minFocal <= medianFocal * 0.75f -> LensType.ULTRAWIDE
            r.minFocal >= medianFocal * 1.50f -> LensType.TELEPHOTO
            else                              -> LensType.MAIN
        }

        val lenses = raw.map { r ->
            val type = if (r.isFront) LensType.FRONT else classifyBack(r)
            Log.d(TAG, "Classified ${r.cameraId}: ${r.minFocal}mm → $type")
            DetectedLens(r.cameraId, type, r.minFocal, r.isFront)
        }

        // ── Deduplicate: one per type, prefer smallest focal in group ─────────
        // Exception: front cameras — keep all if focal lengths differ (wide selfie + narrow)
        val typeOrder = listOf(LensType.ULTRAWIDE, LensType.MAIN, LensType.TELEPHOTO, LensType.FRONT, LensType.UNKNOWN)

        val result = lenses
            .groupBy { it.lensType }
            .flatMap { (type, group) ->
                if (type == LensType.FRONT)
                    group.distinctBy { (it.focalLength * 10).toInt() }
                else
                    listOf(group.minByOrNull { it.focalLength }!!)
            }
            .sortedWith(compareBy({ typeOrder.indexOf(it.lensType) }, { it.focalLength }))

        Log.d(TAG, "Final: ${result.map { "${it.lensType}@${it.focalLength}mm(${it.cameraId})" }}")
        return result
    }
}

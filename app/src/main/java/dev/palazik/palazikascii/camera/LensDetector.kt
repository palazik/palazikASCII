package dev.palazik.palazikascii.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Represents a detected physical camera lens on the device.
 *
 * @param cameraId   The Camera2 logical camera ID string.
 * @param lensType   Categorised lens role.
 * @param focalLength The shortest reported focal length (mm) — used for sorting.
 * @param isFront    Whether this lens faces the user.
 */
data class DetectedLens(
    val cameraId: String,
    val lensType: LensType,
    val focalLength: Float,
    val isFront: Boolean,
) {
    /** Human-readable label shown in the UI cycling button. */
    val label: String get() = buildString {
        append(if (isFront) "Front" else lensType.label)
    }
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
     * Queries [CameraManager] and returns a deduplicated, sorted list of usable lenses.
     *
     * Categorisation heuristic (mirrors what OEM camera apps do internally):
     *  - Front-facing  → FRONT  (always)
     *  - Focal length < 2.0 mm → ULTRAWIDE
     *  - Largest aperture (< 2.0f) or Focal length <= 7.0 mm → MAIN
     *  - Focal length > 7.0 mm → TELEPHOTO
     *
     * Sorted order: ULTRAWIDE → MAIN → TELEPHOTO → FRONT
     */
    fun detectLenses(context: Context): List<DetectedLens> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val lenses = mutableListOf<DetectedLens>()
        val seenFocalLengths = mutableSetOf<Float>()

        for (cameraId in manager.cameraIdList) {
            try {
                val chars = manager.getCameraCharacteristics(cameraId)

                // Skip external / non-optical sensors
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                
                // NEW: Skip fake "depth" or legacy auxiliary sensors
                val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                if (hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) continue

                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.takeIf { it.isNotEmpty() } ?: continue

                // Use the minimum reported focal length as the representative value
                val minFocal = focalLengths.minOrNull() ?: continue

                // Deduplicate: some devices expose the same physical sensor under two IDs
                if (!seenFocalLengths.add(minFocal)) continue

                val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT

                // NEW: Use Aperture to reliably find the real Main sensor
                val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                val minAperture = apertures?.minOrNull() ?: Float.MAX_VALUE

                val lensType = when {
                    isFront            -> LensType.FRONT
                    minFocal < 2.0f    -> LensType.ULTRAWIDE
                    minAperture < 2.0f -> LensType.MAIN      // widest aperture = main sensor
                    minFocal <= 7.0f   -> LensType.MAIN
                    else               -> LensType.TELEPHOTO
                }

                lenses.add(
                    DetectedLens(
                        cameraId    = cameraId,
                        lensType    = lensType,
                        focalLength = minFocal,
                        isFront     = isFront,
                    )
                )

                Log.d(TAG, "Lens $cameraId | facing=$facing | focal=${minFocal}mm → $lensType")

            } catch (e: Exception) {
                Log.w(TAG, "Skipping camera $cameraId: ${e.message}")
            }
        }

        // Sort: UW → Main → Tele → Front
        val typeOrder = listOf(
            LensType.ULTRAWIDE,
            LensType.MAIN,
            LensType.TELEPHOTO,
            LensType.FRONT,
            LensType.UNKNOWN,
        )
        return lenses
            .distinctBy { it.lensType } // <-- THIS FIXES THE DOUBLE CLICK BUG
            .sortedWith(
                compareBy({ typeOrder.indexOf(it.lensType) }, { it.focalLength })
            )
    }
}

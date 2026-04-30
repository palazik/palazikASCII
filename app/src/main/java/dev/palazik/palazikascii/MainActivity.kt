package dev.palazik.palazikascii

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.palazik.palazikascii.ui.ASCIIViewerScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    // ── JNI ───────────────────────────────────────────────────────────────────
    external fun getLatestAsciiFrame(): String
    external fun feedFrame(yBytes: ByteArray, width: Int, height: Int)

    companion object {
        init { System.loadLibrary("palazikascii") }
    }

    // ── Permission launcher ───────────────────────────────────────────────────
    private val cameraPermissionResult =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGranted.value = granted
        }

    private val permissionGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge, let Compose handle insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor  = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        // Check / request camera permission immediately
        permissionGranted.value = checkSelfPermission(Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!permissionGranted.value) {
            cameraPermissionResult.launch(Manifest.permission.CAMERA)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val granted by permissionGranted

                if (granted) {
                    // Loop to pull the latest ASCII frame constantly (~30 FPS)
                    val asciiFrame by produceState(initialValue = "") {
                        while (true) {
                            value = getLatestAsciiFrame()
                            delay(33) // ~30 FPS
                        }
                    }
                    
                    // The UI Screen
                    ASCIIViewerScreen(
                        asciiFrame = asciiFrame, // Uses the polled frame variable
                        onFrame = { bytes, width, height -> 
                            feedFrame(bytes, width, height) // Sends the camera frame to C++!
                        }
                    )
                } else {
                    PermissionDeniedScreen()
                }
            }
        }
    }
}

@Composable
private fun PermissionDeniedScreen() {
    Box(
        modifier          = Modifier
            .fillMaxSize()
            .background(Color(0xFF050805)),
        contentAlignment  = Alignment.Center,
    ) {
        Text(
            text       = "[ CAMERA PERMISSION REQUIRED ]",
            fontFamily = FontFamily.Monospace,
            fontSize   = 14.sp,
            color      = Color(0xFF00FF41),
        )
    }
}

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
    external fun getLatestAsciiFrame(): String                          // legacy stub
    external fun getLatestColorFrame(): IntArray                        // new: packed int[]
    external fun feedFrame(
        yBytes: ByteArray, uvBytes: ByteArray,                          // added uvBytes
        width: Int, height: Int, rotation: Int
    )

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

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor     = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        permissionGranted.value = checkSelfPermission(Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!permissionGranted.value) {
            cameraPermissionResult.launch(Manifest.permission.CAMERA)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val granted by permissionGranted

                if (granted) {
                    // Pull latest color frame ~30 FPS on main thread (just an array copy)
                    val colorFrame by produceState(initialValue = intArrayOf()) {
                        while (true) {
                            value = getLatestColorFrame()
                            delay(33)
                        }
                    }

                    ASCIIViewerScreen(
                        colorFrame = colorFrame,
                        onFrame = { yBytes, uvBytes, width, height, rotation ->
                            feedFrame(yBytes, uvBytes, width, height, rotation)
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
        modifier         = Modifier
            .fillMaxSize()
            .background(Color(0xFF050805)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = "[ CAMERA PERMISSION REQUIRED ]",
            fontFamily = FontFamily.Monospace,
            fontSize   = 14.sp,
            color      = Color(0xFF00FF41),
        )
    }
}
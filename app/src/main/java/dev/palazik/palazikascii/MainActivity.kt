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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MainActivity : ComponentActivity() {

    // ── JNI ───────────────────────────────────────────────────────────────────
    external fun getLatestAsciiFrame(): String                          // legacy stub
    external fun getLatestColorFrame(): IntArray                        // new: packed int[]
    
    // FIX: Added 'isFront: Boolean' here so it matches C++ and the UI screen!
    external fun feedFrame(
        yBytes: ByteArray, uvBytes: ByteArray,                          
        width: Int, height: Int, rotation: Int, isFront: Boolean
    )

    companion object {
        init { System.loadLibrary("palazikascii") }
    }

    // FIX: Analysis thread pushes frames here; Compose collects on main thread — no polling lag!
    private val _frameFlow = MutableSharedFlow<IntArray>(extraBufferCapacity = 1)
    private val frameFlow  = _frameFlow.asSharedFlow()

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
                    // Uses the lag-free flow instead of the 33ms delay loop
                    val colorFrame by frameFlow.collectAsState(initial = intArrayOf())

                    ASCIIViewerScreen(
                        colorFrame = colorFrame,
                        onFrame = { yBytes, uvBytes, width, height, rotation, front ->
                            feedFrame(yBytes, uvBytes, width, height, rotation, front)
                            _frameFlow.tryEmit(getLatestColorFrame())
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

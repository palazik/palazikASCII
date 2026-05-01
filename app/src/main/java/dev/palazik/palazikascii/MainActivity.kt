package dev.palazik.palazikascii

import android.Manifest
import android.os.Bundle
import android.util.DisplayMetrics
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
import dev.palazik.palazikascii.ui.ASCIIViewerScreen
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MainActivity : ComponentActivity() {

    external fun getLatestAsciiFrame(): String
    external fun getLatestColorFrame(): IntArray
    external fun feedFrame(yBytes: ByteArray, uvBytes: ByteArray,
                           width: Int, height: Int, rotation: Int, isFront: Boolean)
    external fun setScreenSize(screenW: Int, screenH: Int)

    companion object {
        init { System.loadLibrary("palazikascii") }
    }

    // Analysis thread pushes frames here; Compose collects on main thread — no polling
    private val _frameFlow = MutableSharedFlow<IntArray>(extraBufferCapacity = 1)
    private val frameFlow  = _frameFlow.asSharedFlow()

    private val cameraPermissionResult =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGranted.value = granted
        }
    private val permissionGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor     = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        @Suppress("DEPRECATION")
        val dm = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
        setScreenSize(dm.widthPixels, dm.heightPixels)

        permissionGranted.value = checkSelfPermission(Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!permissionGranted.value) cameraPermissionResult.launch(Manifest.permission.CAMERA)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val granted by permissionGranted
                if (granted) {
                    val colorFrame by frameFlow.collectAsState(initial = intArrayOf())
                    ASCIIViewerScreen(
                        colorFrame = colorFrame,
                        onFrame    = { yBytes, uvBytes, w, h, rot, front ->
                            feedFrame(yBytes, uvBytes, w, h, rot, front)
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
    Box(Modifier.fillMaxSize().background(Color(0xFF050805)), Alignment.Center) {
        Text("[ CAMERA PERMISSION REQUIRED ]",
            fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Color(0xFF00FF41))
    }
}

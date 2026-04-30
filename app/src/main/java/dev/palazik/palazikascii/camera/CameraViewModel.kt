package dev.palazik.palazikascii.camera

import android.app.Application
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.camera.core.ImageAnalysis

data class CameraUiState(
    val lenses: List<DetectedLens> = emptyList(),
    val activeLensIndex: Int = 0,
    val isReady: Boolean = false,
)

@OptIn(ExperimentalCamera2Interop::class)
class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null

    init {
        viewModelScope.launch {
            val lenses = LensDetector.detectLenses(application)
            // Prefer MAIN as default; fall back to index 0
            val defaultIndex = lenses.indexOfFirst { it.lensType == LensType.MAIN }
                .coerceAtLeast(0)
            _uiState.update { it.copy(lenses = lenses, activeLensIndex = defaultIndex) }
        }
    }

    fun cycleToNextLens() {
        _uiState.update { state ->
            if (state.lenses.isEmpty()) return@update state
            val next = (state.activeLensIndex + 1) % state.lenses.size
            state.copy(activeLensIndex = next)
        }
    }

    val activeSelector: CameraSelector
        get() {
            val state = _uiState.value
            val lens = state.lenses.getOrNull(state.activeLensIndex)
                ?: return CameraSelector.DEFAULT_BACK_CAMERA
            return CameraSelector.Builder()
                .addCameraFilter { cameras ->
                    cameras.filter { Camera2CameraInfo.from(it).cameraId == lens.cameraId }
                }
                .build()
        }

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        preview: Preview,
        onFrame: (ByteArray, Int, Int) -> Unit // 1. ADD IT HERE
    ) {
        val ctx = getApplication<Application>()
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            cameraProvider = future.get()
            rebind(lifecycleOwner, preview, onFrame) // 2. PASS IT HERE
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun rebind(
        lifecycleOwner: LifecycleOwner, 
        preview: Preview,
        onFrame: (ByteArray, Int, Int) -> Unit
    ) {
        val provider = cameraProvider ?: return
        provider.unbindAll()
    
        val ctx = getApplication<Application>()
        
        // Build the frame analyzer
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { ia ->
                ia.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                    val plane = imageProxy.planes[0]  // Grab Y plane
                    val buf = plane.buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    
                    onFrame(bytes, imageProxy.width, imageProxy.height) // Send to C++
                    imageProxy.close()
                }
            }
    
        try {
            // Add 'analysis' to the bindToLifecycle call
            provider.bindToLifecycle(lifecycleOwner, activeSelector, preview, analysis)
            _uiState.update { it.copy(isReady = true) }
        } catch (e: Exception) {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }
    }
}

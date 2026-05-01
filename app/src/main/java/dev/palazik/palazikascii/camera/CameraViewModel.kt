package dev.palazik.palazikascii.camera

import android.app.Application
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import java.util.concurrent.Executors

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

    // Dedicated single thread for image analysis — keeps main thread free
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    init {
        viewModelScope.launch {
            val lenses = LensDetector.detectLenses(application)
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
        onFrame: (ByteArray, ByteArray, Int, Int, Int) -> Unit   // added uvBytes
    ) {
        val ctx = getApplication<Application>()
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            cameraProvider = future.get()
            rebind(lifecycleOwner, preview, onFrame)
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun rebind(
        lifecycleOwner: LifecycleOwner,
        preview: Preview,
        onFrame: (ByteArray, ByteArray, Int, Int, Int) -> Unit
    ) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { ia ->
                ia.setAnalyzer(analysisExecutor) { imageProxy ->
                    // Y plane
                    val yBuf = imageProxy.planes[0].buffer
                    val yBytes = ByteArray(yBuf.remaining()).also { yBuf.get(it) }

                    // UV plane (NV12 interleaved — plane index 1)
                    val uvBuf = imageProxy.planes[1].buffer
                    val uvBytes = ByteArray(uvBuf.remaining()).also { uvBuf.get(it) }

                    val rotation = imageProxy.imageInfo.rotationDegrees
                    onFrame(yBytes, uvBytes, imageProxy.width, imageProxy.height, rotation)

                    imageProxy.close()
                }
            }

        try {
            provider.bindToLifecycle(lifecycleOwner, activeSelector, preview, analysis)
            _uiState.update { it.copy(isReady = true) }
        } catch (e: Exception) {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }
    }

    override fun onCleared() {
        super.onCleared()
        analysisExecutor.shutdown()
    }
}
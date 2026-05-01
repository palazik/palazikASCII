package dev.palazik.palazikascii.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.palazik.palazikascii.camera.CameraViewModel
import dev.palazik.palazikascii.camera.LensType
import kotlinx.coroutines.delay

// ─── Colour tokens ─────────────────────────────────────────────────────────────
private val TermGreen     = Color(0xFF00FF41)
private val TermGreenDim  = Color(0xFF00CC33)
private val TermBg        = Color(0xFF050805)
private val TermSurface   = Color(0xFF0C110C)
private val TermBorder    = Color(0xFF1A2E1A)
private val TermOverlay   = Color(0xCC080808)

// ─── ASCII ramp (must match C++) ───────────────────────────────────────────────
private const val kRamp = " .:-=+*#%@"

private fun lensIcon(type: LensType) = when (type) {
    LensType.ULTRAWIDE -> "0.6×"
    LensType.MAIN      -> "1×"
    LensType.TELEPHOTO -> "3×"
    LensType.FRONT     -> "✦"
    LensType.UNKNOWN   -> "?"
}

@Composable
fun ASCIIViewerScreen(
    colorFrame: IntArray,
    // FIX 1: Added Boolean for isFront so it matches CameraViewModel
    onFrame: (ByteArray, ByteArray, Int, Int, Int, Boolean) -> Unit 
) {
    val viewModel: CameraViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    var showAbout by remember { mutableStateOf(false) }

    val preview = remember {
        Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .build()
    }

    LaunchedEffect(uiState.activeLensIndex, uiState.lenses) {
        if (uiState.lenses.isNotEmpty()) {
            viewModel.bindCamera(lifecycleOwner, preview, onFrame)
        }
    }

    // Scanline animation
    val scanlineOffset = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scanlineOffset.animateTo(
            targetValue   = 1f,
            animationSpec = infiniteRepeatable(
                animation  = tween(durationMillis = 4000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            )
        )
    }

    // Cursor blink
    var cursorVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) { delay(530); cursorVisible = !cursorVisible }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TermBg)
            .systemBarsPadding()
    ) {
        ColoredAsciiCanvas(
            modifier   = Modifier.fillMaxSize(),
            colorFrame = colorFrame,
        )

        ScanlineOverlay(
            modifier = Modifier.fillMaxSize(),
            progress = scanlineOffset.value,
        )

        TopStatusBar(
            modifier      = Modifier.align(Alignment.TopCenter),
            cursorVisible = cursorVisible,
        )

        BottomControlBar(
            modifier        = Modifier.align(Alignment.BottomCenter),
            lenses          = uiState.lenses,
            activeLensIndex = uiState.activeLensIndex,
            onCycleCamera   = { viewModel.cycleToNextLens() },
            onAboutClick    = { showAbout = true },
        )
    }

    if (showAbout) {
        AboutBottomSheet(onDismiss = { showAbout = false })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Colored ASCII Canvas — native Canvas drawText per cell
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ColoredAsciiCanvas(
    modifier: Modifier,
    colorFrame: IntArray,
) {
    // Cached Paint — created once, never reallocated
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface  = Typeface.MONOSPACE
            textAlign = Paint.Align.LEFT
        }
    }

    // Reusable 1-char buffer so we don't allocate a String per cell per frame
    val charBuf = remember { CharArray(1) }

    Canvas(modifier = modifier.background(TermBg)) {
        // FIX 2: Dynamic grid size reading from C++ header (index 0 and 1)
        if (colorFrame.size < 2) return@Canvas

        val cols = colorFrame[0]
        val rows = colorFrame[1]

        if (cols <= 0 || rows <= 0 || colorFrame.size < (cols * rows) + 2) return@Canvas

        val cellW = size.width  / cols
        val cellH = size.height / rows

        // Using your original font scaling
        paint.textSize = cellH * 0.95f

        drawContext.canvas.nativeCanvas.apply {
            // Loop starts at index 2 because 0 and 1 are cols/rows
            for (i in 2 until colorFrame.size) {
                val packed   = colorFrame[i]
                val charIdx  = (packed ushr 24) and 0xFF
                val r        = (packed ushr 16) and 0xFF
                val g        = (packed ushr  8) and 0xFF
                val b        =  packed          and 0xFF

                paint.color = android.graphics.Color.rgb(r, g, b)

                val dataIndex = i - 2
                val col = dataIndex % cols
                val row = dataIndex / cols

                charBuf[0] = kRamp[charIdx.coerceIn(0, kRamp.length - 1)]
                drawText(
                    charBuf, 0, 1,
                    col * cellW,
                    (row + 1) * cellH,   // baseline
                    paint
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Scanline overlay
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ScanlineOverlay(modifier: Modifier, progress: Float) {
    Canvas(modifier = modifier.alpha(0.06f)) {
        val lineSpacing = 4f
        var y = 0f
        while (y < size.height) {
            drawLine(
                color       = TermGreen,
                start       = Offset(0f, y),
                end         = Offset(size.width, y),
                strokeWidth = 1f,
            )
            y += lineSpacing
        }
        val scanY = progress * size.height
        drawLine(
            brush       = Brush.horizontalGradient(
                listOf(Color.Transparent, TermGreen.copy(alpha = 0.35f), Color.Transparent)
            ),
            start       = Offset(0f, scanY),
            end         = Offset(size.width, scanY),
            strokeWidth = 2f,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Top status bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TopStatusBar(modifier: Modifier, cursorVisible: Boolean) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = buildString {
                append("palazik")
                append("ASCII")
                if (cursorVisible) append("_") else append(" ")
            },
            fontFamily    = FontFamily.Monospace,
            fontWeight    = FontWeight.Bold,
            fontSize      = 13.sp,
            color         = TermGreen,
            letterSpacing = 1.sp,
        )

        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            LiveDot()
            Text(
                text          = "LIVE",
                fontFamily    = FontFamily.Monospace,
                fontSize      = 10.sp,
                color         = TermGreenDim,
                letterSpacing = 2.sp,
            )
        }
    }
}

@Composable
private fun LiveDot() {
    val scale by rememberInfiniteTransition(label = "dot").animateFloat(
        initialValue  = 0.6f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liveDot",
    )
    Box(
        Modifier
            .size((8 * scale).dp)
            .clip(CircleShape)
            .background(TermGreen)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Bottom control bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BottomControlBar(
    modifier: Modifier,
    lenses: List<dev.palazik.palazikascii.camera.DetectedLens>,
    activeLensIndex: Int,
    onCycleCamera: () -> Unit,
    onAboutClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Transparent, TermOverlay))
            )
            .padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 20.dp),
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            TerminalIconButton(onClick = onAboutClick, label = "ABOUT", icon = Icons.Outlined.Info)
            LensCycleButton(lenses = lenses, activeLensIndex = activeLensIndex, onClick = onCycleCamera)
        }
    }
}

@Composable
private fun LensCycleButton(
    lenses: List<dev.palazik.palazikascii.camera.DetectedLens>,
    activeLensIndex: Int,
    onClick: () -> Unit,
) {
    val activeLens = lenses.getOrNull(activeLensIndex)
    val label = activeLens?.let { "${lensIcon(it.lensType)}  ${it.label.uppercase()}" } ?: "CAM"

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(TermSurface.copy(alpha = 0.85f))
            .border(1.dp, TermBorder, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null,
                onClick           = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector        = Icons.Outlined.FlipCameraAndroid,
                contentDescription = "Switch camera",
                tint               = TermGreen,
                modifier           = Modifier.size(18.dp),
            )
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    fadeIn(tween(200)) + slideInVertically { it / 2 } togetherWith
                    fadeOut(tween(150)) + slideOutVertically { -it / 2 }
                },
                label = "lensLabel",
            ) { text ->
                Text(
                    text          = text,
                    fontFamily    = FontFamily.Monospace,
                    fontSize      = 12.sp,
                    fontWeight    = FontWeight.SemiBold,
                    color         = TermGreen,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

@Composable
private fun TerminalIconButton(
    onClick: () -> Unit,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null,
                onClick           = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(TermSurface.copy(alpha = 0.85f))
                .border(1.dp, TermBorder, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = label,
                tint               = TermGreen,
                modifier           = Modifier.size(20.dp),
            )
        }
        Text(
            text          = label,
            fontFamily    = FontFamily.Monospace,
            fontSize      = 9.sp,
            color         = TermGreenDim.copy(alpha = 0.7f),
            letterSpacing = 1.5.sp,
        )
    }
}

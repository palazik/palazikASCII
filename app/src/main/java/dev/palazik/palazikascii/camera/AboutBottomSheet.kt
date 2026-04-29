package dev.palazik.palazikascii.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ─── Colour palette (reused from theme) ───────────────────────────────────────
private val TermGreen   = Color(0xFF00FF41)
private val TermGreenDim = Color(0xFF00CC33)
private val TermBg      = Color(0xFF0A0A0A)
private val TermSurface = Color(0xFF111411)
private val TermBorder  = Color(0xFF1A2E1A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutBottomSheet(
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = TermSurface,
        dragHandle       = {
            Box(
                Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 40.dp, height = 3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(TermBorder)
            )
        },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 0.dp,
    ) {
        AboutContent(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
        )
    }
}

@Composable
private fun AboutContent(modifier: Modifier = Modifier) {
    // Staggered fade-in for each row
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(80); visible = true }

    Column(
        modifier            = modifier.padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {

        // ── ASCII art logo ────────────────────────────────────────────────────
        AnimatedRow(visible = visible, delayMs = 0) {
            AsciiLogoBlock()
        }

        Spacer(Modifier.height(20.dp))

        // ── Divider ───────────────────────────────────────────────────────────
        AnimatedRow(visible = visible, delayMs = 80) {
            HorizontalDivider(color = TermBorder, thickness = 1.dp)
        }

        Spacer(Modifier.height(20.dp))

        // ── App name ──────────────────────────────────────────────────────────
        AnimatedRow(visible = visible, delayMs = 160) {
            InfoRow(label = "APP", value = "palazikASCII")
        }

        Spacer(Modifier.height(12.dp))

        // ── Author ────────────────────────────────────────────────────────────
        AnimatedRow(visible = visible, delayMs = 240) {
            InfoRow(label = "BY ", value = "@tm_palaziks")
        }

        Spacer(Modifier.height(12.dp))

        // ── TG Channel ───────────────────────────────────────────────────────
        AnimatedRow(visible = visible, delayMs = 320) {
            InfoRow(label = "TG ", value = "@palazikASCII")
        }

        Spacer(Modifier.height(24.dp))

        // ── Divider ───────────────────────────────────────────────────────────
        AnimatedRow(visible = visible, delayMs = 400) {
            HorizontalDivider(color = TermBorder, thickness = 1.dp)
        }

        Spacer(Modifier.height(16.dp))

        // ── Flavour tag ───────────────────────────────────────────────────────
        AnimatedRow(visible = visible, delayMs = 480) {
            Text(
                text       = "[ real-time camera → ASCII renderer ]",
                fontFamily = FontFamily.Monospace,
                fontSize   = 11.sp,
                color      = TermGreenDim.copy(alpha = 0.55f),
                textAlign  = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AsciiLogoBlock() {
    // Compact 5-line logo that fits narrow screens
    val logo = """
 ____   ____  _____ ___ ___ 
|  _ \ / _  \/ ___//   |   \
| |_) | |_| |\__ \/ /| | |\ \
|  __/ |___/____/ / _  | | \ \
|_|   |_|  /____/_/  |_|_|  \_\
    """.trimIndent()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(TermBg, TermSurface)
                )
            )
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = logo,
            fontFamily = FontFamily.Monospace,
            fontSize   = 8.5.sp,
            color      = TermGreen,
            lineHeight = 13.sp,
            textAlign  = TextAlign.Center,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Label badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(TermBorder)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text       = label,
                fontFamily = FontFamily.Monospace,
                fontSize   = 10.sp,
                fontWeight = FontWeight.Bold,
                color      = TermGreenDim,
                letterSpacing = 2.sp,
            )
        }

        Spacer(Modifier.width(12.dp))

        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = TermGreen.copy(alpha = 0.4f))) { append("→ ") }
                withStyle(SpanStyle(color = TermGreen, fontWeight = FontWeight.SemiBold)) { append(value) }
            },
            fontFamily = FontFamily.Monospace,
            fontSize   = 15.sp,
        )
    }
}

@Composable
private fun AnimatedRow(
    visible: Boolean,
    delayMs: Int,
    content: @Composable () -> Unit,
) {
    val alpha by animateFloatAsState(
        targetValue    = if (visible) 1f else 0f,
        animationSpec  = tween(durationMillis = 350, delayMillis = delayMs),
        label          = "rowFade",
    )
    Box(Modifier.alpha(alpha)) { content() }
}

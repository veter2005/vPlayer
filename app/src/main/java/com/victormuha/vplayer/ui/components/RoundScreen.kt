package com.victormuha.vplayer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt

/** All corners of this square are inside the screen circle, even on small/high-density watches. */
internal fun safeSquareSide(diameterDp: Float, marginDp: Float = 6f): Float =
    ((diameterDp - 2f * marginDp).coerceAtLeast(0f) / sqrt(2f))

@Composable
fun RoundScreen(background: Color = Color.Black, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(background), Alignment.Center) {
        val side = safeSquareSide(minOf(maxWidth.value, maxHeight.value)).dp
        Box(
            Modifier.size(side).testTag("round-safe-area"),
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}

/** A single scroll container: headers, errors and footer actions scroll with the list. */
@Composable
fun RoundPage(title: String, content: LazyListScope.() -> Unit) {
    RoundScreen {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("round-list").clip(RoundedCornerShape(8.dp)),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            item("page-title") {
                Text(
                    title, color = Color(0xFFFFCC00), fontSize = 14.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
            }
            content()
        }
    }
}

@Composable
fun CircularProgress(progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
        val origin = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(
            Color(0xFF303030), -90f, 360f, false, origin, arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        drawArc(
            Color(0xFFFFCC00), -90f, 360f * progress.coerceIn(0f, 1f), false,
            origin, arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
        )
    }
}

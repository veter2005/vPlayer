package com.victormuha.vplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke

// Box that renders inside a circle (insets for round screen)
// The idea: content is placed inside a rectangle of size ~ (2*r/√2) x (2*r/√2)
// centered in the circle. This gives safe-area for text.
// For a 466x466 screen, r=233, safe rectangle = 2*233/√2 ≈ 329x329dp.

@Composable
fun RoundScreen(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black, CircleShape)
    ) {
        // Inset content into safe circle
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 30.dp, vertical = 40.dp),
            content = content
        )
    }
}

// (removed unused helper)

// Scrolling list that handles round geometry — children in middle are wide, top/bottom are narrow
@Composable
fun CircularList(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

// Circular progress arc for playback progress
@Composable
fun CircularProgress(
    modifier: Modifier = Modifier,
    progress: Float,
    color: Color = Color(0xFFFFCC00),
    trackColor: Color = Color(0x33FFFFFF),
    strokeWidth: Dp = 6.dp
) {
    Canvas(modifier = modifier.size(200.dp)) {
        val r = size.minDimension / 2 - strokeWidth.toPx() / 2
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = strokeWidth.toPx())
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            style = Stroke(width = strokeWidth.toPx())
        )
    }
}

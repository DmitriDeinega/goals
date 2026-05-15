package com.goals.app.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Stylus + mouse hover support. Touch never fires hover events; this is for
// users on tablets / Chromebooks / DeX with a pointing device.
@Composable
fun rememberHoverState(): Pair<MutableInteractionSource, State<Boolean>> {
    val source = remember { MutableInteractionSource() }
    val hovered = source.collectIsHoveredAsState()
    return source to hovered
}

// Dashed rounded-rect border. Mirrors the web's `1px dashed var(--border2)`
// on the + NEW GOAL and + ADD RULE buttons; Modifier.border is solid-only.
fun Modifier.dashedBorder(
    color: Color,
    shape: Shape,
    width: Dp = 1.dp,
    dashLength: Dp = 6.dp,
    gapLength: Dp = 4.dp
): Modifier = this.drawWithCache {
    val strokeWidthPx = width.toPx()
    val pathEffect = PathEffect.dashPathEffect(
        floatArrayOf(dashLength.toPx(), gapLength.toPx()), 0f
    )
    val insetPx = strokeWidthPx / 2f
    val insetSize = Size(size.width - strokeWidthPx, size.height - strokeWidthPx)
    val outline = shape.createOutline(insetSize, layoutDirection, this)
    onDrawWithContent {
        drawContent()
        inset(insetPx, insetPx) {
            drawOutline(
                outline = outline,
                color = color,
                style = Stroke(width = strokeWidthPx, pathEffect = pathEffect)
            )
        }
    }
}

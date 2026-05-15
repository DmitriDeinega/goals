package com.goals.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goals.app.ui.theme.*

// Web logic (GoalRow.jsx):
//   getStatusClass(value, isNeg): isNeg → value=true means success (avoided) → no class; value=false → 'fail'
//                                  !isNeg → value=true → 'success'; value=false → no class
//   getIcon(value, isNeg):         isNeg → value=true → '' (empty); value=false → '✗'
//                                  !isNeg → value=true → '✓'; value=false → ''

@Composable
fun ToggleButton(
    slotValue: Boolean,
    isNegative: Boolean,
    isMultiSlot: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isToggling: Boolean = false
) {
    val isFail   = isNegative && !slotValue   // negative + false = failed (showed ✗)
    val isSuccess = if (isNegative) slotValue  // negative + true  = avoided (success, no icon)
                    else slotValue             // positive + true  = done (✓)
    val showSuccess = !isNegative && slotValue // only positives show ✓

    // Hover preview: when pointer hovers an empty positive slot we tint it with
    // accent-dim, and a negative slot still in success state with red-dim —
    // mirrors web's `.toggle-btn:hover` (scale + bg/border).
    val (source, hovered) = rememberHoverState()
    // After click, suppress the preview until the pointer leaves and returns —
    // otherwise the slot flashes the action-color it just LEFT (e.g. green
    // hover lingering on a freshly-unchecked goal), reading as "click didn't
    // take". Same pattern as web's `justClicked`.
    var justClicked by remember { mutableStateOf(false) }
    LaunchedEffect(hovered.value) {
        if (!hovered.value) justClicked = false
    }
    val isHovering = hovered.value && !justClicked && !isToggling
    val previewSuccess = isHovering && !isNegative && !slotValue   // would become ✓
    val previewFail    = isHovering && isNegative && slotValue     // would become ✗

    val bgColor = when {
        isFail || previewFail              -> RedDim
        (isSuccess && !isNegative) || previewSuccess -> AccentDim
        else                               -> Color.Transparent
    }
    val borderColor = when {
        isFail || previewFail              -> RedColor
        (isSuccess && !isNegative) || previewSuccess -> AccentColor
        else                               -> Border2Color
    }
    val symbol = when {
        isFail      -> "✗"
        showSuccess -> "✓"
        else        -> ""
    }
    val symbolColor = when {
        isFail      -> RedColor
        showSuccess -> AccentColor
        else        -> Color.Transparent
    }

    val shape = if (isMultiSlot) RoundedCornerShape(20.dp) else CircleShape

    val scale by animateFloatAsState(
        targetValue = if (isHovering) 1.1f else 1f,
        label = "toggleScale"
    )

    Box(
        modifier = modifier
            .then(if (isMultiSlot) Modifier.height(36.dp).widthIn(min = 36.dp) else Modifier.size(36.dp))
            .scale(scale)
            .clip(shape)
            .drawBehind { drawRect(bgColor) }
            .border(2.dp, borderColor, shape)
            .then(if (isToggling) Modifier.alpha(0.5f) else Modifier)
            .hoverable(source)
            .clickable(enabled = !isToggling) { justClicked = true; onClick() }
            .then(if (isMultiSlot) Modifier.padding(horizontal = 10.dp) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (symbol.isNotEmpty()) {
            Text(
                text = symbol,
                color = symbolColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

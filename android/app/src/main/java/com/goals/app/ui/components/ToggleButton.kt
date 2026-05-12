package com.goals.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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

    val bgColor = when {
        isFail      -> RedDim
        isSuccess && !isNegative -> AccentDim
        else        -> Color.Transparent
    }
    val borderColor = when {
        isFail      -> RedColor
        isSuccess && !isNegative -> AccentColor
        else        -> Border2Color
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

    Box(
        modifier = modifier
            .then(if (isMultiSlot) Modifier.height(36.dp).widthIn(min = 36.dp) else Modifier.size(36.dp))
            .clip(shape)
            .drawBehind { drawRect(bgColor) }
            .border(2.dp, borderColor, shape)
            .then(if (isToggling) Modifier.alpha(0.5f) else Modifier)
            .clickable(enabled = !isToggling) { onClick() }
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

package com.goals.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.goals.app.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun ToastOverlay(
    message: String?,
    isError: Boolean = true,
    onDismiss: () -> Unit
) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(4000)
            onDismiss()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f),
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedVisibility(
            visible = message != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            if (message != null) {
                Box(
                    modifier = Modifier
                        .padding(top = 24.dp, start = 20.dp, end = 20.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isError) RedColor else AccentColor)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = message,
                        color = if (isError) Color.White else BgColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = SyneFont
                    )
                }
            }
        }
    }
}

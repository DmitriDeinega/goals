package com.goals.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Exact colors from index.css CSS variables ──────────────────────────────────
val BgColor        = Color(0xFF0A0A0A)
val SurfaceColor   = Color(0xFF111111)
val Surface2Color  = Color(0xFF1A1A1A)
val BorderColor    = Color(0xFF222222)
val Border2Color   = Color(0xFF2E2E2E)
val TextColor      = Color(0xFFF0F0F0)
val Text2Color     = Color(0xFF888888)
val Text3Color     = Color(0xFF444444)
val AccentColor    = Color(0xFFC8F135)
val AccentDim      = Color(0x1FC8F135)   // rgba(200,241,53, 0.12)
val RedColor       = Color(0xFFFF4444)
val RedDim         = Color(0x1FFF4444)   // rgba(255,68,68, 0.12)

private val GoalsDarkColorScheme = darkColorScheme(
    primary = AccentColor,
    onPrimary = BgColor,
    secondary = Text2Color,
    onSecondary = BgColor,
    background = BgColor,
    onBackground = TextColor,
    surface = SurfaceColor,
    onSurface = TextColor,
    surfaceVariant = Surface2Color,
    onSurfaceVariant = Text2Color,
    outline = Border2Color,
    error = RedColor,
    onError = Color.White,
)

@Composable
fun GoalsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GoalsDarkColorScheme,
        typography = GoalsTypography,
        content = content
    )
}

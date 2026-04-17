package com.goals.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.goals.app.R

val googleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val SyneFont = FontFamily(
    Font(googleFont = GoogleFont("Syne"), fontProvider = googleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Syne"), fontProvider = googleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Syne"), fontProvider = googleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("Syne"), fontProvider = googleFontProvider, weight = FontWeight.Bold),
)

val DmMonoFont = FontFamily(
    Font(googleFont = GoogleFont("DM Mono"), fontProvider = googleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("DM Mono"), fontProvider = googleFontProvider, weight = FontWeight.Medium),
)

val GoalsTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = SyneFont,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        color = TextColor
    ),
    bodyMedium = TextStyle(
        fontFamily = SyneFont,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        color = Text2Color
    ),
    bodySmall = TextStyle(
        fontFamily = SyneFont,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        color = Text3Color
    ),
    labelLarge = TextStyle(
        fontFamily = SyneFont,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 0.08.sp,
        color = TextColor
    ),
    labelSmall = TextStyle(
        fontFamily = SyneFont,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 0.15.sp,
        color = Text3Color
    ),
    titleMedium = TextStyle(
        fontFamily = SyneFont,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        letterSpacing = 0.06.sp,
        color = TextColor
    )
)

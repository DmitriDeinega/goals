package com.goals.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goals.app.ui.theme.*
import com.goals.app.viewmodel.WeekSummary

@Composable
fun WeekSummarySection(
    summary: WeekSummary?,
    currency: String,
    modifier: Modifier = Modifier
) {
    // A null summary means "this week's data hasn't arrived yet" — render that as a
    // loading state, never as 0%. Coercing null to 0 showed a real-looking zero that
    // then jumped to the true value, which reads as wrong data rather than as loading.
    val loading = summary == null
    val pct = summary?.pct ?: 0
    val earned = summary?.totalEarned ?: 0.0
    val currencySymbol = if (currency == "NIS") "₪" else "$"

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // "THIS WEEK · 78%" together on the left
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (loading) "THIS WEEK · ···" else "THIS WEEK · $pct%",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.15.sp,
                    color = Text3Color,
                    fontFamily = SyneFont
                )
            }

            // Earned on the right
            if (earned > 0) {
                Text(
                    text = "$currencySymbol${"%.0f".format(earned)}",
                    fontFamily = DmMonoFont,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AccentColor
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Border2Color)
        ) {
            // While loading, leave the track bare rather than drawing a zero-width
            // accent fill — an empty accent bar is exactly the "0%" signal we're
            // trying not to send.
            if (!loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((pct / 100f).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(AccentColor)
                )
            }
        }
    }
}

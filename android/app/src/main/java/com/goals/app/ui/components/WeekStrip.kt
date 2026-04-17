package com.goals.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.goals.app.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun WeekStrip(
    weekStart: String,
    selectedDate: String,
    today: String,
    startDate: String?,
    onDaySelected: (String) -> Unit,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fmt = DateTimeFormatter.ISO_LOCAL_DATE
    val weekStartDate = LocalDate.parse(weekStart, fmt)
    val todayDate = LocalDate.parse(today, fmt)
    val minDate = startDate?.let { LocalDate.parse(it, fmt) }

    val days = (0..6).map { weekStartDate.plusDays(it.toLong()) }
    val canGoPrev = minDate == null || weekStartDate.isAfter(minDate)
    val canGoNext = weekStartDate.plusWeeks(1) <= todayDate

    var calendarOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        // Date picker input — full width, opens calendar below
        DatePickerInput(
            selectedDate = selectedDate,
            today = today,
            minDate = startDate,
            open = calendarOpen,
            onToggle = { calendarOpen = !calendarOpen },
            onClose = { calendarOpen = false },
            onSelect = { date ->
                onDaySelected(date)
                calendarOpen = false
            }
        )

        Spacer(Modifier.height(8.dp))

        // Week strip row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Prev nav button
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Border2Color, RoundedCornerShape(6.dp))
                    .then(if (canGoPrev) Modifier.clickable { onPrevWeek() } else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "‹",
                    fontSize = 16.sp,
                    color = if (canGoPrev) Text2Color else Text3Color.copy(alpha = 0.3f),
                    lineHeight = 16.sp
                )
            }

            // Day buttons
            days.forEach { date ->
                val dateStr = date.format(fmt)
                val isSelected = dateStr == selectedDate
                val isToday = dateStr == today
                val isFuture = date.isAfter(todayDate)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) AccentDim else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) AccentColor else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .then(if (!isFuture) Modifier.clickable { onDaySelected(dateStr) } else Modifier)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                                .uppercase().take(3),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.1.sp,
                            color = if (isSelected) AccentColor else Text3Color,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = date.dayOfMonth.toString(),
                            fontFamily = DmMonoFont,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = when {
                                isSelected -> AccentColor
                                isToday -> TextColor
                                isFuture -> Text3Color
                                else -> Text2Color
                            },
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Next nav button
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Border2Color, RoundedCornerShape(6.dp))
                    .then(if (canGoNext) Modifier.clickable { onNextWeek() } else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "›",
                    fontSize = 16.sp,
                    color = if (canGoNext) Text2Color else Text3Color.copy(alpha = 0.3f),
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun DatePickerInput(
    selectedDate: String,
    today: String,
    minDate: String?,
    open: Boolean,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    onSelect: (String) -> Unit
) {
    val fmt = DateTimeFormatter.ISO_LOCAL_DATE
    val todayDate = LocalDate.parse(today, fmt)
    val minLocalDate = minDate?.let { LocalDate.parse(it, fmt) }
    val selected = LocalDate.parse(selectedDate, fmt)
    val isToday = selectedDate == today

    var viewMonth by remember(selectedDate) { mutableStateOf(selected.withDayOfMonth(1)) }

    Column {
        // Input row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Surface2Color)
                .border(1.dp, Border2Color, RoundedCornerShape(6.dp))
                .clickable { onToggle() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selected.format(DateTimeFormatter.ofPattern("dd MMM yyyy")),
                fontFamily = DmMonoFont,
                fontSize = 12.sp,
                color = TextColor
            )
            // Today reset icon — accent when not on today, dim otherwise
            Text(
                text = "⌂",
                fontSize = 16.sp,
                color = if (!isToday) AccentColor else Text3Color.copy(alpha = 0.5f),
                modifier = if (!isToday) Modifier.clickable(onClick = { onSelect(today) }) else Modifier
            )
        }

        // Calendar popup — floats above content via Popup
        if (open) {
            Popup(
                onDismissRequest = { onClose() },
                properties = PopupProperties(focusable = true)
            ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onClose() },
                contentAlignment = Alignment.TopStart
            ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceColor,
                border = BorderStroke(1.dp, Border2Color),
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 120.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Month nav header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val canGoPrevMonth = minLocalDate == null ||
                            viewMonth.isAfter(minLocalDate.withDayOfMonth(1))
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, Border2Color, RoundedCornerShape(6.dp))
                                .then(if (canGoPrevMonth) Modifier.clickable { viewMonth = viewMonth.minusMonths(1) } else Modifier),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("‹", fontSize = 14.sp, color = if (canGoPrevMonth) Text2Color else Text3Color.copy(alpha = 0.3f))
                        }
                        Text(
                            text = viewMonth.format(DateTimeFormatter.ofPattern("MMM yyyy")).uppercase(),
                            fontFamily = SyneFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 0.08.sp,
                            color = TextColor
                        )
                        val canGoNextMonth = viewMonth.isBefore(todayDate.withDayOfMonth(1))
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, Border2Color, RoundedCornerShape(6.dp))
                                .then(if (canGoNextMonth) Modifier.clickable { viewMonth = viewMonth.plusMonths(1) } else Modifier),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("›", fontSize = 14.sp, color = if (canGoNextMonth) Text2Color else Text3Color.copy(alpha = 0.3f))
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Day-of-week headers
                    Row(modifier = Modifier.fillMaxWidth()) {
                        listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa").forEach { dow ->
                            Text(
                                text = dow,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.08.sp,
                                color = Text3Color,
                                fontFamily = SyneFont
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Build grid: Sunday-start padding
                    val firstOfMonth = viewMonth
                    val startPad = firstOfMonth.dayOfWeek.value % 7  // Sun=0
                    val daysInMonth = viewMonth.lengthOfMonth()
                    val cells = mutableListOf<LocalDate?>()
                    repeat(startPad) { cells.add(null) }
                    for (d in 1..daysInMonth) cells.add(viewMonth.withDayOfMonth(d))
                    while (cells.size % 7 != 0) cells.add(null)

                    cells.chunked(7).forEach { week ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            week.forEach { day ->
                                if (day == null) {
                                    Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                                } else {
                                    val dateStr = day.format(fmt)
                                    val isSel = dateStr == selectedDate
                                    val isTod = dateStr == today
                                    val isDisabled = day.isAfter(todayDate) ||
                                        (minLocalDate != null && day.isBefore(minLocalDate))

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f)
                                            .padding(2.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                when {
                                                    isSel -> AccentColor
                                                    else -> Color.Transparent
                                                }
                                            )
                                            .then(if (!isDisabled) Modifier.clickable { onSelect(dateStr) } else Modifier),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = day.dayOfMonth.toString(),
                                            fontFamily = DmMonoFont,
                                            fontSize = 11.sp,
                                            color = when {
                                                isSel -> BgColor
                                                isDisabled -> Text3Color.copy(alpha = 0.3f)
                                                isTod -> AccentColor
                                                else -> Text2Color
                                            },
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } // Surface
            } // Box
            } // Popup
        }
    }
}

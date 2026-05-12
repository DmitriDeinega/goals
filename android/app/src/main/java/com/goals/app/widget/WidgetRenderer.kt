package com.goals.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.goals.app.R
import com.goals.app.data.models.GoalWeek
import com.goals.app.data.models.GoalWeekSnapshot
import com.goals.app.viewmodel.computeGoalStats
import com.goals.app.viewmodel.computeWeekSummary
import com.goals.app.viewmodel.getDaysUpTo
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.floor

object WidgetRenderer {

    private val slotIds = intArrayOf(
        R.id.row_slot_0,
        R.id.row_slot_1,
        R.id.row_slot_2,
        R.id.row_slot_3,
    )

    private val dayCellIds = intArrayOf(
        R.id.day_0, R.id.day_1, R.id.day_2, R.id.day_3, R.id.day_4, R.id.day_5, R.id.day_6
    )
    private val dayLabelIds = intArrayOf(
        R.id.day_label_0, R.id.day_label_1, R.id.day_label_2, R.id.day_label_3,
        R.id.day_label_4, R.id.day_label_5, R.id.day_label_6
    )
    private val dayNumIds = intArrayOf(
        R.id.day_num_0, R.id.day_num_1, R.id.day_num_2, R.id.day_num_3,
        R.id.day_num_4, R.id.day_num_5, R.id.day_num_6
    )

    private const val COLOR_ACCENT = 0xFFC8F135.toInt()
    private const val COLOR_TEXT = 0xFFF0F0F0.toInt()
    private const val COLOR_TEXT_SECONDARY = 0xFF888888.toInt()
    private const val COLOR_TEXT_DIM = 0xFF444444.toInt()

    fun buildRoot(context: Context, appWidgetId: Int, snapshot: WidgetSnapshot): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_main)
        applyListAdapters(context, rv, appWidgetId)
        applyClickTemplate(context, rv, appWidgetId)
        return rv
    }

    /**
     * Builds the single ListView item for R.id.header_list. Contains all the
     * stuff that was previously inline in widget_main (day strip, chevrons,
     * THIS WEEK %, reward, progress bar). All click handling uses fillInIntent
     * because views inside a collection item cannot use setOnClickPendingIntent.
     */
    fun buildHeaderItem(context: Context, snapshot: WidgetSnapshot): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_header_item)
        renderHeaderContent(rv, snapshot)
        applyHeaderFillInIntents(rv, snapshot)
        return rv
    }

    fun buildRow(context: Context, snapshot: WidgetSnapshot, goalWeek: GoalWeek): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_row)
        val live = snapshot.goals.find { it.id == goalWeek.goalId }
        val snap = goalWeek.snapshot
        val type = snap.type.ifEmpty { live?.type ?: "daily" }
        val isNegative = snap.isNegative
        val timesPerDay = snap.timesPerDay ?: live?.timesPerDay ?: 1
        val slotCount = (if (type == "daily") timesPerDay else 1).coerceIn(1, slotIds.size)
        val today = WidgetClock.today(snapshot)
        val selected = snapshot.selectedDate.ifEmpty { today }
        val log = snapshot.logs.find { it.goalId == goalWeek.goalId && it.date == selected }
        val slots: List<Boolean> = log?.slots ?: List(slotCount) { isNegative }

        val weekStart = snapshot.weekStart
        val weekEnd = if (weekStart.isNotEmpty())
            LocalDate.parse(weekStart, DateTimeFormatter.ISO_LOCAL_DATE)
                .plusDays(6).format(DateTimeFormatter.ISO_LOCAL_DATE)
        else weekStart
        val cutoff = if (weekEnd < today) weekEnd else today
        val weekDays = if (weekStart.isNotEmpty()) getDaysUpTo(weekStart, cutoff) else emptyList()
        val effectiveSnap = GoalWeekSnapshot(
            name = snap.name,
            type = type,
            isNegative = isNegative,
            timesPerDay = timesPerDay,
            timesPerWeek = snap.timesPerWeek ?: live?.timesPerWeek,
            rewardRules = snap.rewardRules.ifEmpty { live?.rewardRules ?: emptyList() },
            order = snap.order
        )
        val stats = computeGoalStats(effectiveSnap, goalWeek.goalId, snapshot.logs, weekDays)
        val currency = snapshot.settings?.currency ?: "USD"
        val symbol = if (currency == "NIS") "₪" else "$"
        val rewardText = if (stats.earnedReward > 0) "$symbol${fmtMoney(stats.earnedReward)}" else ""

        rv.setTextViewText(R.id.row_reward, rewardText)
        rv.setTextViewText(R.id.row_progress, "${stats.completions}/${stats.totalSlots}")
        rv.setTextViewText(R.id.row_name, snap.name.ifEmpty { live?.name ?: "" })
        rv.setViewVisibility(R.id.row_avoid, if (isNegative) View.VISIBLE else View.GONE)

        for (i in slotIds.indices) {
            val id = slotIds[i]
            if (i < slotCount) {
                rv.setViewVisibility(id, View.VISIBLE)
                val slotValue = slots.getOrNull(i) ?: isNegative
                val on = if (isNegative) !slotValue else slotValue
                val bgRes = when {
                    on && isNegative -> R.drawable.widget_btn_bg_red
                    on -> R.drawable.widget_btn_bg_accent
                    else -> R.drawable.widget_btn_bg_surface
                }
                val iconRes = when {
                    on && isNegative -> R.drawable.widget_ic_close_dark
                    on -> R.drawable.widget_ic_check_dark
                    else -> R.drawable.widget_ic_empty
                }
                rv.setInt(id, "setBackgroundResource", bgRes)
                rv.setImageViewResource(id, iconRes)
                val toggling = snapshot.isToggling(goalWeek.goalId, selected, i)
                rv.setFloat(id, "setAlpha", if (toggling) 0.5f else 1.0f)

                val fillIn = Intent().apply {
                    putExtra(WidgetActionReceiver.EXTRA_ACTION, WidgetActionReceiver.ACTION_TOGGLE)
                    putExtra(WidgetActionReceiver.EXTRA_GOAL_ID, goalWeek.goalId)
                    putExtra(WidgetActionReceiver.EXTRA_DATE, selected)
                    putExtra(WidgetActionReceiver.EXTRA_SLOT_INDEX, i)
                    data = Uri.parse("widget://goals/toggle/${goalWeek.goalId}/$selected/$i")
                }
                rv.setOnClickFillInIntent(id, fillIn)
            } else {
                rv.setViewVisibility(id, View.GONE)
            }
        }

        return rv
    }

    private fun renderHeaderContent(rv: RemoteViews, snapshot: WidgetSnapshot) {
        val today = WidgetClock.today(snapshot)
        val selected = snapshot.selectedDate.ifEmpty { today }
        val currency = snapshot.settings?.currency ?: "USD"
        val symbol = if (currency == "NIS") "₪" else "$"
        val summary = if (snapshot.weekStart.isNotEmpty() && snapshot.settings != null) {
            val weekEnd = LocalDate.parse(snapshot.weekStart, DateTimeFormatter.ISO_LOCAL_DATE)
                .plusDays(6).format(DateTimeFormatter.ISO_LOCAL_DATE)
            computeWeekSummary(
                goals = snapshot.goals,
                goalWeeks = snapshot.goalWeeks,
                logs = snapshot.logs,
                weekStart = snapshot.weekStart,
                weekEnd = weekEnd,
                today = today
            )
        } else null
        val pct = (summary?.pct ?: 0).coerceIn(0, 100)
        val earned = summary?.totalEarned ?: 0.0

        rv.setTextViewText(R.id.header_label, "THIS WEEK · $pct%")
        rv.setProgressBar(R.id.header_progress, 100, pct, false)
        if (earned > 0) {
            rv.setTextViewText(R.id.header_reward, "$symbol${fmtMoney(earned)}")
            rv.setViewVisibility(R.id.header_reward, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.header_reward, View.GONE)
        }
        val weekStart = if (snapshot.weekStart.isNotEmpty()) snapshot.weekStart
            else WidgetDates.weekStartFor(selected, snapshot.settings?.firstDayOfWeek)
        applyDayStrip(rv, weekStart, selected, today)
        val canGoNext = WidgetDates.nextSelectedDate(
            selected,
            today,
            snapshot.settings?.firstDayOfWeek
        ) != null
        rv.setInt(
            R.id.nav_next,
            "setTextColor",
            if (canGoNext) COLOR_TEXT_SECONDARY else COLOR_TEXT_DIM
        )
        val canGoPrev = WidgetDates.prevSelectedDate(
            selected,
            snapshot.settings?.firstDayOfWeek,
            snapshot.settings?.startDate
        ) != null
        rv.setInt(
            R.id.nav_prev,
            "setTextColor",
            if (canGoPrev) COLOR_TEXT_SECONDARY else COLOR_TEXT_DIM
        )
    }

    private fun applyDayStrip(
        rv: RemoteViews,
        weekStart: String,
        selectedDate: String,
        today: String
    ) {
        val startDate = LocalDate.parse(weekStart, DateTimeFormatter.ISO_LOCAL_DATE)
        val todayDate = LocalDate.parse(today, DateTimeFormatter.ISO_LOCAL_DATE)
        for (i in 0..6) {
            val date = startDate.plusDays(i.toLong())
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val isSelected = dateStr == selectedDate
            val isToday = dateStr == today
            val isFuture = date.isAfter(todayDate)

            rv.setTextViewText(
                dayLabelIds[i],
                date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
                    .uppercase().take(3)
            )
            rv.setTextViewText(dayNumIds[i], date.dayOfMonth.toString())

            val labelColor = if (isSelected) COLOR_ACCENT else COLOR_TEXT_DIM
            val numColor = when {
                isSelected -> COLOR_ACCENT
                isToday -> COLOR_TEXT
                isFuture -> COLOR_TEXT_DIM
                else -> COLOR_TEXT_SECONDARY
            }
            rv.setTextColor(dayLabelIds[i], labelColor)
            rv.setTextColor(dayNumIds[i], numColor)
            rv.setInt(
                dayCellIds[i],
                "setBackgroundResource",
                if (isSelected) R.drawable.widget_day_selected else 0
            )
        }
    }

    private fun applyHeaderFillInIntents(rv: RemoteViews, snapshot: WidgetSnapshot) {
        val today = WidgetClock.today(snapshot)
        val selected = snapshot.selectedDate.ifEmpty { today }
        val weekStart = if (snapshot.weekStart.isNotEmpty()) snapshot.weekStart
            else WidgetDates.weekStartFor(selected, snapshot.settings?.firstDayOfWeek)
        val startDate = LocalDate.parse(weekStart, DateTimeFormatter.ISO_LOCAL_DATE)
        val todayDate = LocalDate.parse(today, DateTimeFormatter.ISO_LOCAL_DATE)

        rv.setOnClickFillInIntent(
            R.id.nav_prev,
            fillIn(WidgetActionReceiver.ACTION_NAV_PREV, "widget://goals/nav/prev")
        )
        rv.setOnClickFillInIntent(
            R.id.nav_next,
            fillIn(WidgetActionReceiver.ACTION_NAV_NEXT, "widget://goals/nav/next")
        )
        for (i in 0..6) {
            val date = startDate.plusDays(i.toLong())
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val isFuture = date.isAfter(todayDate)
            if (!isFuture) {
                val intent = Intent().apply {
                    putExtra(WidgetActionReceiver.EXTRA_ACTION, WidgetActionReceiver.ACTION_NAV_DAY)
                    putExtra(WidgetActionReceiver.EXTRA_DATE, dateStr)
                    data = Uri.parse("widget://goals/day/$dateStr")
                }
                rv.setOnClickFillInIntent(dayCellIds[i], intent)
            } else {
                // No fill-in for future days — taps fall through to the template,
                // which has no action set by us, and the receiver's onReceive will
                // ignore intents missing EXTRA_ACTION.
                rv.setOnClickFillInIntent(dayCellIds[i], Intent())
            }
        }
        rv.setOnClickFillInIntent(
            R.id.header_summary,
            fillIn(WidgetActionReceiver.ACTION_LAUNCH_APP, "widget://goals/launch")
        )
    }

    private fun fillIn(action: String, dataUri: String): Intent = Intent().apply {
        putExtra(WidgetActionReceiver.EXTRA_ACTION, action)
        data = Uri.parse(dataUri)
    }

    private fun applyListAdapters(context: Context, rv: RemoteViews, appWidgetId: Int) {
        val headerIntent = Intent(context, WidgetHeaderRemoteViewsService::class.java).apply {
            data = Uri.parse("widget://goals/header/$appWidgetId")
        }
        rv.setRemoteAdapter(R.id.header_list, headerIntent)

        val listIntent = Intent(context, WidgetRemoteViewsService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse("widget://goals/list/$appWidgetId")
        }
        rv.setRemoteAdapter(R.id.goal_list, listIntent)
    }

    private fun applyClickTemplate(context: Context, rv: RemoteViews, appWidgetId: Int) {
        val template = Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetActionReceiver.INTENT_ACTION
        }
        val pi = PendingIntent.getBroadcast(
            context,
            appWidgetId,
            template,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        rv.setPendingIntentTemplate(R.id.goal_list, pi)
        rv.setPendingIntentTemplate(R.id.header_list, pi)
    }

    private fun fmtMoney(v: Double): String =
        if (v == floor(v)) "%.0f".format(v) else "%.2f".format(v)
}

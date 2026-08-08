package com.goals.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.goals.app.ui.components.ToastOverlay
import com.goals.app.ui.components.rememberHoverState
import com.goals.app.ui.goals.GoalsScreen
import com.goals.app.ui.theme.*
import com.goals.app.ui.today.TodayScreen
import com.goals.app.viewmodel.AppViewModel
import com.goals.app.viewmodel.computeWeekSummary
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Date the widget was showing when the user tapped to open the app. Consumed
    // once by GoalsApp on resume, then cleared. null = launched normally (→ today).
    private val launchDate = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchDate.value = intent?.getStringExtra(EXTRA_LAUNCH_DATE)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(BgColor.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(BgColor.toArgb())
        )
        setContent {
            GoalsTheme {
                GoalsApp(launchDate = launchDate)
            }
        }
    }

    // FLAG_ACTIVITY_CLEAR_TOP reuses this instance when the app is already running,
    // so a widget tap arrives here rather than onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_LAUNCH_DATE)?.let { launchDate.value = it }
    }

    companion object {
        const val EXTRA_LAUNCH_DATE = "launch_date"
    }
}

@Composable
fun GoalsApp(
    viewModel: AppViewModel = hiltViewModel(),
    launchDate: MutableState<String?> = mutableStateOf(null),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val fmt = DateTimeFormatter.ISO_LOCAL_DATE
    val tabs = listOf("today", "goals")
    val pagerState = rememberPagerState { tabs.size }
    val tab = tabs[pagerState.currentPage]
    // Start with device today so the first composition has a parseable date; once
    // settings load we re-align to the server-tz today (which may differ).
    // Saveable, not plain remember: these must survive Activity recreation
    // (rotation, config change, process-death restore). With `remember` a rotation
    // reset hasResumedOnce and snapped the user back to today — the same bug as the
    // minimize/resume clobber, just via a different trigger.
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now().format(fmt)) }
    var alignedToServer by rememberSaveable { mutableStateOf(false) }

    val settings = uiState.settings
    val firstDay = settings?.firstDayOfWeek ?: "sunday"

    LaunchedEffect(uiState.today) {
        if (!alignedToServer && uiState.today.isNotEmpty()) {
            selectedDate = uiState.today
            alignedToServer = true
        }
    }

    // Kill SSE on pause, full reload on resume.
    //
    // The selected date is only ever set here on a *cold* start (→ today) or when a
    // widget tap hands us a date. Returning from the background must preserve whatever
    // day the user was on: repeatOnLifecycle re-runs this block on every resume, and
    // unconditionally resetting to today was throwing away the user's selection every
    // time the app was minimized and reopened.
    //
    // onNewIntent sets launchDate before onResume, so reading it here catches a warm
    // widget tap too.
    // Lives in the ViewModel, not rememberSaveable: it must survive rotation (so a
    // rotation keeps the user's day) but NOT process death (so being killed in the
    // background and relaunched lands on today rather than restoring a stale day).
    // This effect is keyed only on `lifecycle`, so it launches before settings arrive
    // and would otherwise capture firstDay's "sunday" default forever — sending Monday
    // users the wrong week on every resume.
    val currentFirstDay by rememberUpdatedState(firstDay)
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val fromWidget = launchDate.value
            val freshStart = !viewModel.hasResumedOnce
            val target = when {
                !fromWidget.isNullOrEmpty() -> fromWidget
                // Fresh start: prefer the server-tz today. It's usually not loaded
                // yet, so fall back to the device date and leave alignedToServer
                // false — the today-sync effect then corrects it once settings land.
                freshStart -> viewModel.uiState.value.today
                else -> null  // returning from background — keep the user's selection
            }
            if (!target.isNullOrEmpty()) {
                selectedDate = target
                alignedToServer = true  // don't let the today-sync effect clobber it
            } else if (freshStart) {
                selectedDate = LocalDate.now().format(fmt)
                alignedToServer = false  // let the server's today win when it arrives
            }
            launchDate.value = null
            viewModel.markResumed()
            // Reload the week we're actually showing, not just the current one.
            viewModel.onResume(preserveWeekStart = getWeekStart(selectedDate, currentFirstDay))
            try {
                awaitCancellation()
            } finally {
                viewModel.onPause()
            }
        }
    }

    // No separate initial loadData() here: the RESUMED effect above already loads on
    // first resume, and with the correct week to preserve. Having both meant two
    // concurrent /api/init calls on every cold start, whose responses could land in
    // either order.

    val currency = settings?.currency ?: "NIS"
    val today = uiState.today
    val appTitle = com.goals.app.BuildConfig.FLAVOR.uppercase().let {
        if (it == "DEV") "GOALS DEV" else "GOALS"
    }

    // Compute weekStart from selectedDate
    val weekStart = remember(selectedDate, firstDay) {
        getWeekStart(selectedDate, firstDay)
    }
    val weekEnd = remember(weekStart) {
        LocalDate.parse(weekStart, fmt).plusDays(6).format(fmt)
    }

    // Load week data whenever the week we want and the week we have disagree.
    // Keyed on both: loadData() replaces uiState.week with the *current* week, so a
    // widget tap onto a past date could leave weekStart pointing at the tapped week
    // while the loaded data was the current one. Keying only on weekStart meant the
    // effect never re-ran to correct it, and the UI sat at "0%" until the user
    // navigated to another week and back.
    val visibleWeekStart = uiState.week.weekStart
    LaunchedEffect(weekStart, visibleWeekStart) {
        if (visibleWeekStart != weekStart) {
            viewModel.loadWeek(weekStart)
        }
    }

    val weekReady = visibleWeekStart == weekStart
    val weekSummary = if (weekReady) {
        computeWeekSummary(uiState.goals, uiState.week.goalWeeks, uiState.week.logs, weekStart, weekEnd, today)
    } else null

    if (uiState.loading) {
        Box(
            modifier = Modifier.fillMaxSize().background(BgColor),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = AccentColor)
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(BgColor)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // Header
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.icon),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = appTitle,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.15.sp,
                            color = Text3Color,
                            fontFamily = SyneFont
                        )
                    }
                }
            }

            // Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 0.dp),
            ) {
                tabs.forEachIndexed { index, tabId ->
                    TabItem(
                        label = tabId.uppercase(),
                        isActive = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(index) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            HorizontalDivider(color = BorderColor, thickness = 1.dp)

            // Content — swipeable pager
            var isRefreshing by remember { mutableStateOf(false) }
            LaunchedEffect(uiState.loading) { if (!uiState.loading) isRefreshing = false }

            @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { isRefreshing = true; viewModel.loadData(preserveWeekStart = weekStart) },
                modifier = Modifier.weight(1f),
            ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { page ->
                when (tabs[page]) {
                    "today" -> TodayScreen(
                        inFlightToggles = uiState.inFlightToggles,
                        goals = uiState.goals,
                        // While a week is loading, show no rows rather than the previous
                        // week's — stale counts beside the new week's dates read as real
                        // data for the wrong week.
                        goalWeeks = if (weekReady) uiState.week.goalWeeks else emptyList(),
                        logs = if (weekReady) uiState.week.logs else emptyList(),
                        selectedDate = selectedDate,
                        // Always the week we intend to show. Falling back to the loaded
                        // week made the day strip jump to the old week mid-navigation.
                        weekStart = weekStart,
                        today = today,
                        settings = settings,
                        weekSummary = weekSummary,
                        weekLoading = !weekReady,
                        currency = currency,
                        onDaySelected = { date -> selectedDate = date },
                        onPrevWeek = {
                            // Send last day of previous week (matches web: weekStart.subtract(1,'day'))
                            val lastDayOfPrevWeek = LocalDate.parse(weekStart, fmt).minusDays(1)
                            selectedDate = lastDayOfPrevWeek.format(fmt)
                        },
                        onNextWeek = {
                            // Send last day of next week, capped at today (matches web)
                            val lastDayOfNextWeek = LocalDate.parse(weekStart, fmt).plusWeeks(1).plusDays(6)
                            selectedDate = minOf(lastDayOfNextWeek.format(fmt), today)
                        },
                        onToggle = { goalId, date, slotIndex, currentValue ->
                            viewModel.toggle(goalId, date, slotIndex, currentValue)
                        },
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    "goals" -> GoalsScreen(
                        goals = uiState.goals,
                        goalWeeks = uiState.week.goalWeeks,
                        onAddGoal = { req -> viewModel.addGoal(req) },
                        onUpdateGoal = { id, req -> viewModel.editGoal(id, req) },
                        onDeleteGoal = { id -> viewModel.deleteGoal(id) },
                        onSetEnabled = { id, enabled -> viewModel.setEnabled(id, enabled) },
                        onReorder = { ids -> viewModel.reorder(ids) },
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            } // HorizontalPager
            } // PullToRefreshBox
        }

        // Toast overlay
        ToastOverlay(
            message = uiState.toast,
            isError = true,
            onDismiss = { viewModel.clearToast() }
        )
    }
}

@Composable
private fun TabItem(label: String, isActive: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (source, hovered) = rememberHoverState()
    Box(
        modifier = modifier
            .hoverable(source)
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.08.sp,
                color = if (isActive) TextColor else if (hovered.value) TextColor else Text3Color,
                fontFamily = SyneFont
            )
            if (isActive) {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(2.dp)
                        .background(AccentColor)
                )
            } else {
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

fun getWeekStart(date: String, firstDay: String): String {
    val fmt = DateTimeFormatter.ISO_LOCAL_DATE
    val d = LocalDate.parse(date, fmt)
    return if (firstDay == "monday") {
        val dow = d.dayOfWeek.value  // Mon=1, Sun=7
        val diff = if (dow == 7) -6L else (1 - dow).toLong()
        d.plusDays(diff).format(fmt)
    } else {
        // Sunday-based week
        val dow = d.dayOfWeek.value % 7  // Sun=0, Mon=1, ..., Sat=6
        d.minusDays(dow.toLong()).format(fmt)
    }
}

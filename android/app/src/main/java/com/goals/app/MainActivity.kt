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
    var selectedDate by remember { mutableStateOf(LocalDate.now().format(fmt)) }
    var alignedToServer by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.today) {
        if (!alignedToServer && uiState.today.isNotEmpty()) {
            selectedDate = uiState.today
            alignedToServer = true
        }
    }

    // Kill SSE on pause, full reload on resume. Normally we reset the selected date
    // to today on resume; but if we were opened from the widget we land on the date
    // the widget was showing instead. onNewIntent sets launchDate before onResume,
    // so reading it here picks up a warm-launch tap too.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val fromWidget = launchDate.value
            if (!fromWidget.isNullOrEmpty()) {
                selectedDate = fromWidget
                alignedToServer = true  // don't let the today-sync effect clobber it
                launchDate.value = null
            } else {
                val serverToday = viewModel.uiState.value.today
                selectedDate = serverToday.ifEmpty { LocalDate.now().format(fmt) }
            }
            viewModel.onResume()
            try {
                awaitCancellation()
            } finally {
                viewModel.onPause()
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadData()
    }

    val settings = uiState.settings
    val firstDay = settings?.firstDayOfWeek ?: "sunday"
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

    // Load week data when weekStart changes
    val visibleWeekStart = uiState.week.weekStart
    LaunchedEffect(weekStart) {
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
                        goalWeeks = uiState.week.goalWeeks,
                        logs = uiState.week.logs,
                        selectedDate = selectedDate,
                        weekStart = if (weekReady) weekStart else (visibleWeekStart ?: weekStart),
                        today = today,
                        settings = settings,
                        weekSummary = weekSummary,
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

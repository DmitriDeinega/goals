using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Goals_Windows.Models.Api;
using Goals_Windows.Services;
using Goals_Windows.Services.Api;
using Goals_Windows.Services.State;
using Microsoft.Extensions.Logging;
using Microsoft.UI.Dispatching;
using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Globalization;
using System.Linq;
using System.Threading.Tasks;

namespace Goals_Windows.ViewModels;

/// <summary>Backs the Today page. Subscribes to <see cref="IGoalsState"/> and
/// re-projects the snapshot into UI-friendly view models for the WeekStrip,
/// WeekSummary, and goals list whenever it changes.</summary>
public partial class MainPageViewModel : ObservableObject, IDisposable
{
    private readonly IGoalsState _state;
    private readonly ToggleService _toggle;
    private readonly GoalsApiClient _api;
    private readonly ILogger<MainPageViewModel> _logger;

    private static readonly string[] DayNamesSun = { "SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT" };
    private static readonly string[] DayNamesMon = { "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN" };

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ShowLoadingBar))]
    [NotifyPropertyChangedFor(nameof(ShowEmptyState))]
    public partial bool Hydrated { get; set; }

    [ObservableProperty] public partial string SelectedDate { get; set; } = "";
    [ObservableProperty] public partial string SelectedDateLabel { get; set; } = "";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(WeekSummaryLabel))]
    [NotifyPropertyChangedFor(nameof(WeekEarnedVisible))]
    public partial int WeekPct { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(WeekEarnedText))]
    [NotifyPropertyChangedFor(nameof(WeekEarnedVisible))]
    public partial double WeekEarned { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(WeekEarnedText))]
    public partial string Currency { get; set; } = "USD";

    /// <summary>First date the user has been tracking goals — backend setting.
    /// Used as MinDate on the date picker so the user can't scroll past it.</summary>
    [ObservableProperty] public partial string? StartDate { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(TodayIconForeground))]
    public partial bool CanGoToday { get; set; }

    [ObservableProperty] public partial bool CanGoPrevWeek { get; set; }
    [ObservableProperty] public partial bool CanGoNextWeek { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ShowEmptyState))]
    public partial int RowCount { get; set; }

    public bool ShowLoadingBar => !Hydrated;
    public bool ShowEmptyState => Hydrated && RowCount == 0;
    public string WeekSummaryLabel => $"THIS WEEK · {WeekPct}%";
    public bool WeekEarnedVisible => WeekEarned > 0;
    public string WeekEarnedText => WeekEarned > 0 ? FormatMoney(WeekEarned, Currency) : "";
    /// <summary>Today-icon color: lime when navigable, dim text3 when we're
    /// already on today. Web/Android grey it out; using brush (not opacity)
    /// avoids the "blink" the user sees when re-rendering after a day change.</summary>
    public Microsoft.UI.Xaml.Media.Brush TodayIconForeground
    {
        get
        {
            var res = Microsoft.UI.Xaml.Application.Current.Resources;
            var key = CanGoToday ? "GoalsAccentBrush" : "GoalsText3Brush";
            if (res.TryGetValue(key, out var v) && v is Microsoft.UI.Xaml.Media.Brush b) return b;
            return new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Transparent);
        }
    }

    public ObservableCollection<DayCellViewModel> WeekDays { get; } = new();
    public ObservableCollection<GoalRowViewModel> Rows { get; } = new();

    /// <summary>Currently loaded week's first day. Snapshot has only one
    /// week's GoalWeeks/Logs at a time; this drives the chevron commands.</summary>
    private string _loadedWeekStart = "";

    public MainPageViewModel(
        IGoalsState state,
        ToggleService toggle,
        GoalsApiClient api,
        ILogger<MainPageViewModel> logger)
    {
        _state = state;
        _toggle = toggle;
        _api = api;
        _logger = logger;
        _state.Changed += OnStateChanged;
        Rebuild(_state.Current);
    }

    [RelayCommand]
    private async Task SelectDay(string? date)
    {
        if (string.IsNullOrEmpty(date)) return;
        SelectedDate = date;
        Rebuild(_state.Current);
        await Task.CompletedTask;
    }

    [RelayCommand]
    private async Task GoToday()
    {
        if (!CanGoToday) return;
        var today = GoalsClock.Today(_state.Current.Settings);
        if (string.IsNullOrEmpty(today)) return;
        await NavigateToDate(today);
    }

    [RelayCommand]
    private async Task PrevWeek()
    {
        if (string.IsNullOrEmpty(_loadedWeekStart)) return;
        // Land on the LAST day of the previous week (Saturday for sunday-start,
        // Sunday for monday-start) — matches the web's behaviour.
        var prevWeekStart = DateOnly.Parse(_loadedWeekStart).AddDays(-7);
        var prevWeekEnd = prevWeekStart.AddDays(6).ToString("yyyy-MM-dd");
        await NavigateToDate(prevWeekEnd);
    }

    [RelayCommand]
    private async Task NextWeek()
    {
        if (string.IsNullOrEmpty(_loadedWeekStart)) return;
        var nextWeekStart = DateOnly.Parse(_loadedWeekStart).AddDays(7);
        var today = GoalsClock.Today(_state.Current.Settings);
        var firstDay = _state.Current.Settings?.FirstDayOfWeek ?? "sunday";
        var todayWeekStart = GoalsStats.WeekStartFor(today, firstDay);
        if (string.CompareOrdinal(nextWeekStart.ToString("yyyy-MM-dd"), todayWeekStart) > 0) return;
        // Land on the LAST available day in the next week: today's date if
        // we're navigating into the current week, else Saturday of that week.
        var nextWeekEnd = nextWeekStart.AddDays(6);
        var todayDate = DateOnly.Parse(today);
        var targetDate = nextWeekEnd > todayDate ? today : nextWeekEnd.ToString("yyyy-MM-dd");
        await NavigateToDate(targetDate);
    }

    private async Task NavigateToDate(string targetDate)
    {
        var firstDay = _state.Current.Settings?.FirstDayOfWeek ?? "sunday";
        var targetWeekStart = GoalsStats.WeekStartFor(targetDate, firstDay);

        // If the target week is already loaded, just update the selection.
        if (targetWeekStart == _loadedWeekStart)
        {
            SelectedDate = targetDate;
            Rebuild(_state.Current);
            return;
        }

        // Otherwise fetch the week's data and apply.
        try
        {
            var data = await _api.WeekDataAsync(targetWeekStart);
            _state.ApplyWeekData(data);
            SelectedDate = targetDate;
            Rebuild(_state.Current);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to load week {Week}", targetWeekStart);
        }
    }

    private void OnStateChanged(AppSnapshot snapshot)
    {
        if (App.DispatcherQueue?.HasThreadAccess == true)
        {
            Rebuild(snapshot);
        }
        else
        {
            App.DispatcherQueue?.TryEnqueue(DispatcherQueuePriority.Normal, () => Rebuild(snapshot));
        }
    }

    private void Rebuild(AppSnapshot snapshot)
    {
        Hydrated = snapshot.Hydrated;
        Currency = snapshot.Settings?.Currency ?? "USD";
        StartDate = snapshot.Settings?.StartDate;

        var today = GoalsClock.Today(snapshot.Settings);
        if (string.IsNullOrEmpty(SelectedDate)) SelectedDate = today;
        SelectedDateLabel = FormatSelectedDateLabel(SelectedDate);
        CanGoToday = SelectedDate != today;

        var firstDay = snapshot.Settings?.FirstDayOfWeek ?? "sunday";
        var weekStart = GoalsStats.WeekStartFor(SelectedDate, firstDay);
        var weekEnd = DateOnly.Parse(weekStart).AddDays(6).ToString("yyyy-MM-dd");
        _loadedWeekStart = snapshot.GoalWeeks.FirstOrDefault()?.WeekStart ?? weekStart;

        // Chevron availability.
        var startDate = TryParseDate(snapshot.Settings?.StartDate);
        var prevWeekStart = DateOnly.Parse(weekStart).AddDays(-7);
        CanGoPrevWeek = startDate is null || prevWeekStart >= startDate.Value
            || string.CompareOrdinal(weekStart, startDate.Value.ToString("yyyy-MM-dd")) > 0;
        var todayWeekStart = GoalsStats.WeekStartFor(today, firstDay);
        CanGoNextWeek = string.CompareOrdinal(weekStart, todayWeekStart) < 0;

        RebuildWeekDays(snapshot, weekStart, today, firstDay);
        RebuildWeekSummary(snapshot, weekStart, weekEnd, today);
        RebuildGoalRows(snapshot, today, weekStart, weekEnd);
        RowCount = Rows.Count;
    }

    private void RebuildWeekDays(AppSnapshot snapshot, string weekStart, string today, string firstDay)
    {
        var names = firstDay == "monday" ? DayNamesMon : DayNamesSun;
        var start = DateOnly.Parse(weekStart);
        var todayDate = DateOnly.Parse(today);
        DateOnly? startDate = TryParseDate(snapshot.Settings?.StartDate);

        if (WeekDays.Count != 7)
        {
            WeekDays.Clear();
            for (int i = 0; i < 7; i++)
            {
                var d = start.AddDays(i);
                var dateStr = d.ToString("yyyy-MM-dd");
                WeekDays.Add(new DayCellViewModel(dateStr, names[i], d.Day.ToString(), SelectDayCommand));
            }
        }

        for (int i = 0; i < 7; i++)
        {
            var d = start.AddDays(i);
            var dateStr = d.ToString("yyyy-MM-dd");
            var cell = WeekDays[i];
            cell.UpdateBasics(dateStr, names[i], d.Day.ToString());
            cell.IsSelected = dateStr == SelectedDate;
            cell.IsToday = dateStr == today;
            cell.IsDisabled = d > todayDate || (startDate is not null && d < startDate.Value);
        }
    }

    private void RebuildWeekSummary(AppSnapshot snapshot, string weekStart, string weekEnd, string today)
    {
        var summary = GoalsStats.ComputeWeekSummary(snapshot, weekStart, weekEnd, today);
        WeekPct = summary.Pct;
        WeekEarned = summary.TotalEarned;
    }

    private void RebuildGoalRows(AppSnapshot snapshot, string today, string weekStart, string weekEnd)
    {
        var liveById = snapshot.Goals.ToDictionary(g => g.Id);
        var ordered = snapshot.GoalWeeks
            .Where(gw => gw.Enabled)
            .Select(gw => EffectiveGoals.Build(gw, liveById.GetValueOrDefault(gw.GoalId)))
            .OrderBy(g => g.Order)
            .ToList();

        string cutoff = string.CompareOrdinal(weekEnd, today) < 0 ? weekEnd : today;
        var weekDays = GoalsStats.GetDaysUpTo(weekStart, cutoff);

        var logsForSelectedDate = snapshot.Logs
            .Where(l => l.Date == SelectedDate)
            .ToDictionary(l => l.GoalId, l => l);

        var existing = Rows.ToDictionary(r => r.GoalId);
        var desired = ordered.Select(g => g.Id).ToList();

        foreach (var row in Rows.Where(r => !desired.Contains(r.GoalId)).ToList())
        {
            Rows.Remove(row);
        }

        if (!desired.SequenceEqual(Rows.Select(r => r.GoalId)))
        {
            Rows.Clear();
            foreach (var goal in ordered)
            {
                var row = existing.TryGetValue(goal.Id, out var keep)
                    ? keep
                    : new GoalRowViewModel(goal.Id, goal.Order, _toggle);
                UpdateRow(row, goal, snapshot, today, weekDays, logsForSelectedDate);
                Rows.Add(row);
            }
        }
        else
        {
            foreach (var goal in ordered)
            {
                UpdateRow(existing[goal.Id], goal, snapshot, today, weekDays, logsForSelectedDate);
            }
        }
    }

    private void UpdateRow(
        GoalRowViewModel row,
        Goal goal,
        AppSnapshot snapshot,
        string today,
        IReadOnlyList<string> weekDays,
        IReadOnlyDictionary<string, Log> logsForSelectedDate)
    {
        int slotCount = goal.Type switch
        {
            GoalType.Daily => goal.TimesPerDay ?? 1,
            GoalType.WeeklyX => 1,
            _ => 1
        };

        bool isNeg = goal.IsNegative;
        bool[] daySlots = new bool[slotCount];
        bool defaultValue = isNeg;
        for (int i = 0; i < slotCount; i++) daySlots[i] = defaultValue;

        if (logsForSelectedDate.TryGetValue(goal.Id, out var dayLog))
        {
            for (int i = 0; i < slotCount && i < dayLog.Slots.Count; i++)
            {
                daySlots[i] = dayLog.Slots[i];
            }
        }

        bool[] toggling = new bool[slotCount];
        for (int i = 0; i < slotCount; i++)
        {
            toggling[i] = snapshot.IsToggling(goal.Id, SelectedDate, i);
        }

        var stats = GoalsStats.Compute(goal, snapshot.Logs, weekDays);
        row.UpdateFrom(
            goal,
            daySlots,
            stats.Completions,
            stats.TotalSlots,
            stats.EarnedReward,
            Currency,
            SelectedDate,
            toggling);
    }

    private static DateOnly? TryParseDate(string? iso)
    {
        if (string.IsNullOrWhiteSpace(iso)) return null;
        return DateOnly.TryParse(iso, out var d) ? d : null;
    }

    private static string FormatSelectedDateLabel(string isoDate)
    {
        if (string.IsNullOrEmpty(isoDate)) return "";
        var d = DateOnly.Parse(isoDate);
        return d.ToString("dd MMM yyyy", CultureInfo.InvariantCulture);
    }

    private static string FormatMoney(double amount, string currency)
    {
        string symbol = currency switch
        {
            "NIS" => "₪",
            "USD" => "$",
            "EUR" => "€",
            "GBP" => "£",
            _ => currency + " "
        };
        return amount == Math.Floor(amount)
            ? $"{symbol}{amount:0}"
            : $"{symbol}{amount:0.##}";
    }

    public void Dispose()
    {
        _state.Changed -= OnStateChanged;
    }
}

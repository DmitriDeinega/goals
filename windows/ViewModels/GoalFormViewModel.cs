using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Goals_Windows.Models.Api;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;

namespace Goals_Windows.ViewModels;

/// <summary>State for the goal create/edit dialog. Validation mirrors
/// backend/app/models.py — keep in sync when those rules change.</summary>
public partial class GoalFormViewModel : ObservableObject
{
    public enum Action { Cancel, Save, Delete }

    [ObservableProperty] public partial string Name { get; set; } = "";

    /// <summary>true = daily, false = weekly_x. Drives the toggle-button group.</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsWeekly))]
    [NotifyPropertyChangedFor(nameof(DailyVisibility))]
    [NotifyPropertyChangedFor(nameof(WeeklyVisibility))]
    [NotifyPropertyChangedFor(nameof(MaxCompletions))]
    [NotifyPropertyChangedFor(nameof(DailyButtonBackground))]
    [NotifyPropertyChangedFor(nameof(DailyButtonForeground))]
    [NotifyPropertyChangedFor(nameof(DailyButtonBorder))]
    [NotifyPropertyChangedFor(nameof(WeeklyButtonBackground))]
    [NotifyPropertyChangedFor(nameof(WeeklyButtonForeground))]
    [NotifyPropertyChangedFor(nameof(WeeklyButtonBorder))]
    public partial bool IsDaily { get; set; } = true;

    [ObservableProperty] public partial bool IsNegative { get; set; }

    [ObservableProperty] public partial double TimesPerDay { get; set; } = 1;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(MaxCompletions))]
    public partial double TimesPerWeek { get; set; } = 3;

    /// <summary>Per-week enabled flag — toggled by the switch in the dialog
    /// header. <see cref="EnabledChanged"/> tells the page whether to call
    /// <c>SetEnabledAsync</c> after saving.</summary>
    [ObservableProperty] public partial bool Enabled { get; set; } = true;
    public bool EnabledChanged { get; private set; }
    private bool _enabledAtLoad = true;

    public bool IsEditMode { get; private set; }
    public string EditingGoalId { get; private set; } = "";
    public int? EditingVersion { get; private set; }
    public int EditingOrder { get; private set; }
    public ObservableCollection<RewardRuleViewModel> RewardRules { get; } = new();

    /// <summary>Result the dialog closed with; the page reads this to decide
    /// whether to save, delete, or do nothing.</summary>
    public Action PendingAction { get; set; } = Action.Cancel;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasError))]
    public partial string Error { get; set; } = "";

    public bool HasError => !string.IsNullOrEmpty(Error);
    public string DialogTitle => IsEditMode ? "EDIT GOAL" : "NEW GOAL";
    public bool IsWeekly => !IsDaily;
    public Microsoft.UI.Xaml.Visibility DailyVisibility =>
        IsDaily ? Microsoft.UI.Xaml.Visibility.Visible : Microsoft.UI.Xaml.Visibility.Collapsed;
    public Microsoft.UI.Xaml.Visibility WeeklyVisibility =>
        IsWeekly ? Microsoft.UI.Xaml.Visibility.Visible : Microsoft.UI.Xaml.Visibility.Collapsed;
    public Microsoft.UI.Xaml.Visibility EditActionsVisibility =>
        IsEditMode ? Microsoft.UI.Xaml.Visibility.Visible : Microsoft.UI.Xaml.Visibility.Collapsed;

    /// <summary>Rule cap (the "/N" on each row). Daily goals always cap at 7
    /// (days in the week — TimesPerDay doesn't change that). Weekly goals
    /// cap at TimesPerWeek, reading as /0 while the input is empty (NaN).</summary>
    public int MaxCompletions
    {
        get
        {
            if (IsDaily) return 7;
            return double.IsNaN(TimesPerWeek) ? 0 : (int)TimesPerWeek;
        }
    }

    private void SyncRuleMaxCompletions()
    {
        int max = MaxCompletions;
        foreach (var r in RewardRules) r.MaxCompletions = max;
    }

    partial void OnIsDailyChanged(bool value) => SyncRuleMaxCompletions();
    partial void OnTimesPerWeekChanged(double value) => SyncRuleMaxCompletions();

    // Toggle-button styling so the active option uses the accent palette.
    public Microsoft.UI.Xaml.Media.Brush DailyButtonBackground => Brush(IsDaily ? "GoalsAccentDimBrush" : "GoalsSurfaceBrush");
    public Microsoft.UI.Xaml.Media.Brush DailyButtonForeground => Brush(IsDaily ? "GoalsAccentBrush" : "GoalsText2Brush");
    public Microsoft.UI.Xaml.Media.Brush DailyButtonBorder => Brush(IsDaily ? "GoalsAccentBrush" : "GoalsBorder2Brush");
    public Microsoft.UI.Xaml.Media.Brush WeeklyButtonBackground => Brush(IsWeekly ? "GoalsAccentDimBrush" : "GoalsSurfaceBrush");
    public Microsoft.UI.Xaml.Media.Brush WeeklyButtonForeground => Brush(IsWeekly ? "GoalsAccentBrush" : "GoalsText2Brush");
    public Microsoft.UI.Xaml.Media.Brush WeeklyButtonBorder => Brush(IsWeekly ? "GoalsAccentBrush" : "GoalsBorder2Brush");

    public void LoadForCreate()
    {
        IsEditMode = false;
        EditingGoalId = "";
        EditingVersion = null;
        EditingOrder = 0;
        Name = "";
        IsDaily = true;
        IsNegative = false;
        // Start with the times-per-* fields empty so the user explicitly
        // enters a value rather than us pre-populating a default.
        TimesPerDay = double.NaN;
        TimesPerWeek = double.NaN;
        Enabled = true;
        _enabledAtLoad = true;
        EnabledChanged = false;
        RewardRules.Clear();
        Error = "";
        PendingAction = Action.Cancel;
        OnPropertyChanged(nameof(DialogTitle));
        OnPropertyChanged(nameof(EditActionsVisibility));
    }

    public void LoadForEdit(Goal goal)
    {
        IsEditMode = true;
        EditingGoalId = goal.Id;
        EditingVersion = goal.Version;
        EditingOrder = goal.Order;
        Name = goal.Name;
        IsDaily = goal.Type == GoalType.Daily;
        IsNegative = goal.IsNegative;
        TimesPerDay = goal.TimesPerDay ?? 1;
        TimesPerWeek = goal.TimesPerWeek ?? 3;
        Enabled = goal.Enabled;
        _enabledAtLoad = goal.Enabled;
        EnabledChanged = false;
        RewardRules.Clear();
        int max = MaxCompletions;
        foreach (var r in goal.RewardRules.OrderBy(r => r.MinCompletions))
        {
            RewardRules.Add(new RewardRuleViewModel
            {
                MinCompletions = r.MinCompletions,
                RewardAmount = r.RewardAmount,
                MaxCompletions = max,
            });
        }
        Error = "";
        PendingAction = Action.Cancel;
        OnPropertyChanged(nameof(DialogTitle));
        OnPropertyChanged(nameof(EditActionsVisibility));
    }

    partial void OnEnabledChanged(bool value)
    {
        EnabledChanged = value != _enabledAtLoad;
    }

    [RelayCommand]
    private void SetDaily()
    {
        IsDaily = true;
        // Every type-switch leaves both inputs empty — the user explicitly
        // re-enters the value rather than carrying over the previous one.
        TimesPerDay = double.NaN;
        TimesPerWeek = double.NaN;
    }

    [RelayCommand]
    private void SetWeekly()
    {
        IsDaily = false;
        TimesPerDay = double.NaN;
        TimesPerWeek = double.NaN;
    }

    [RelayCommand]
    private void AddRule()
    {
        // Sensible defaults: next unused min_completions, reward 1.
        var used = RewardRules.Select(r => (int)r.MinCompletions).ToHashSet();
        int next = 1;
        while (used.Contains(next) && next <= MaxCompletions) next++;
        RewardRules.Add(new RewardRuleViewModel
        {
            MinCompletions = next,
            RewardAmount = 1,
            MaxCompletions = MaxCompletions,
        });
    }

    [RelayCommand]
    private void RemoveRule(RewardRuleViewModel? rule)
    {
        if (rule is not null) RewardRules.Remove(rule);
    }

    public bool Validate()
    {
        if (string.IsNullOrWhiteSpace(Name))
        {
            Error = "Name is required.";
            return false;
        }
        if (IsWeekly && (double.IsNaN(TimesPerWeek) || TimesPerWeek < 1 || TimesPerWeek > 7))
        {
            Error = "Times per week must be between 1 and 7.";
            return false;
        }
        if (IsDaily && (double.IsNaN(TimesPerDay) || TimesPerDay < 1))
        {
            Error = "Times per day must be at least 1.";
            return false;
        }
        int max = MaxCompletions;
        var seen = new HashSet<int>();
        for (int i = 0; i < RewardRules.Count; i++)
        {
            var rule = RewardRules[i];
            int mc = (int)rule.MinCompletions;
            if (mc < 1)
            {
                Error = $"Rule {i + 1}: completions must be at least 1.";
                return false;
            }
            if (mc > max)
            {
                Error = $"Rule {i + 1}: completions can't exceed {max}.";
                return false;
            }
            if (!seen.Add(mc))
            {
                Error = $"Rule {i + 1}: duplicate completions value {mc}.";
                return false;
            }
            if (rule.RewardAmount <= 0)
            {
                Error = $"Rule {i + 1}: reward must be greater than 0.";
                return false;
            }
        }
        Error = "";
        return true;
    }

    public GoalCreate ToCreate() => new(
        Name: Name.Trim(),
        Type: IsWeekly ? GoalType.WeeklyX : GoalType.Daily,
        IsNegative: IsNegative,
        TimesPerWeek: IsWeekly ? (int)TimesPerWeek : null,
        TimesPerDay: IsDaily ? (int)TimesPerDay : null,
        RewardRules: BuildRules(),
        Order: EditingOrder);

    public GoalUpdate ToUpdate() => new(
        Name: Name.Trim(),
        Type: IsWeekly ? GoalType.WeeklyX : GoalType.Daily,
        IsNegative: IsNegative,
        TimesPerWeek: IsWeekly ? (int)TimesPerWeek : null,
        TimesPerDay: IsDaily ? (int)TimesPerDay : null,
        RewardRules: BuildRules(),
        Version: EditingVersion);

    private IReadOnlyList<RewardRule> BuildRules() =>
        RewardRules
            .Select(r => new RewardRule((int)r.MinCompletions, r.RewardAmount))
            .OrderBy(r => r.MinCompletions)
            .ToList();

    private static Microsoft.UI.Xaml.Media.Brush Brush(string key)
    {
        var res = Microsoft.UI.Xaml.Application.Current.Resources;
        if (res.TryGetValue(key, out var value) && value is Microsoft.UI.Xaml.Media.Brush b) return b;
        return new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Transparent);
    }
}

using CommunityToolkit.Mvvm.ComponentModel;
using Goals_Windows.Models.Api;
using Microsoft.UI.Xaml;

namespace Goals_Windows.ViewModels;

/// <summary>One card on the Goals tab. Just data — actions (edit, delete,
/// pause, reorder) live in the form dialog. The whole card is clickable
/// to open the editor; drag-and-drop is handled by the parent ListView.</summary>
public partial class GoalCardViewModel : ObservableObject
{
    [ObservableProperty] public partial string Name { get; set; } = "";
    [ObservableProperty] public partial string Meta { get; set; } = "";
    [ObservableProperty] public partial Goal? Goal { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(NegativeBadgeVisibility))]
    public partial bool IsNegative { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(PausedBadgeVisibility))]
    [NotifyPropertyChangedFor(nameof(NameOpacity))]
    public partial bool IsPaused { get; set; }

    public Visibility NegativeBadgeVisibility => IsNegative ? Visibility.Visible : Visibility.Collapsed;
    public Visibility PausedBadgeVisibility   => IsPaused   ? Visibility.Visible : Visibility.Collapsed;
    public double NameOpacity => IsPaused ? 0.4 : 1.0;

    public string GoalId { get; }
    public int Order { get; }

    public GoalCardViewModel(string goalId, int order)
    {
        GoalId = goalId;
        Order = order;
    }

    public void UpdateFrom(Goal goal, bool enabledForWeek)
    {
        Goal = goal;
        Name = goal.Name;
        IsNegative = goal.IsNegative;
        IsPaused = !enabledForWeek;
        Meta = BuildMeta(goal);
    }

    private static string BuildMeta(Goal goal)
    {
        string typeText = goal.Type switch
        {
            GoalType.Daily   => (goal.TimesPerDay is > 1)
                ? $"{goal.TimesPerDay}× / day"
                : "Daily",
            GoalType.WeeklyX => $"{goal.TimesPerWeek}× / week",
            _ => ""
        };
        int rules = goal.RewardRules.Count;
        if (rules == 0) return typeText;
        return $"{typeText} · {rules} reward rule{(rules == 1 ? "" : "s")}";
    }
}

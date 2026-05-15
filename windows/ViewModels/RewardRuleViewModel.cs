using CommunityToolkit.Mvvm.ComponentModel;

namespace Goals_Windows.ViewModels;

/// <summary>One reward-rule row in the goal form's editable list.
/// MinCompletions and RewardAmount are bound TwoWay to NumberBox inputs;
/// validation runs at save time against the form's max-completions context.</summary>
public partial class RewardRuleViewModel : ObservableObject
{
    [ObservableProperty] public partial double MinCompletions { get; set; } = 1;
    [ObservableProperty] public partial double RewardAmount { get; set; } = 1;

    /// <summary>Max completions for the current goal type (1..N). Set by the
    /// form VM so each row can render the "/N" divisor next to its input,
    /// matching the web's `.reward-rule-row .rule-label`.</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(MaxLabel))]
    public partial int MaxCompletions { get; set; } = 7;

    public string MaxLabel => $"/{MaxCompletions} →";
}

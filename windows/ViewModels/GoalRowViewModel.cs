using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Goals_Windows.Models.Api;
using Goals_Windows.Services;
using Microsoft.UI.Xaml;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Threading.Tasks;

namespace Goals_Windows.ViewModels;

/// <summary>One row in the Today goals list. Matches the web's <c>.goal-row</c>
/// layout: <c>[earned] [X/Y progress] [name + inline red 'avoid' badge] [circle toggle buttons]</c>.</summary>
public partial class GoalRowViewModel : ObservableObject
{
    private readonly ToggleService _toggle;

    [ObservableProperty] public partial string Name { get; set; } = "";
    [ObservableProperty] public partial string ProgressText { get; set; } = "0/0";
    [ObservableProperty] public partial string EarnedText { get; set; } = "";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(NegativeTagVisibility))]
    public partial bool IsNegative { get; set; }

    public Visibility NegativeTagVisibility => IsNegative ? Visibility.Visible : Visibility.Collapsed;

    public string GoalId { get; }
    public int Order { get; }
    public string SelectedDate { get; private set; } = "";

    public ObservableCollection<SlotViewModel> Slots { get; } = new();

    public GoalRowViewModel(string goalId, int order, ToggleService toggle)
    {
        GoalId = goalId;
        Order = order;
        _toggle = toggle;
    }

    public void UpdateFrom(
        Goal goal,
        IReadOnlyList<bool> daySlots,
        int completions,
        int totalSlots,
        double earnedReward,
        string currency,
        string selectedDate,
        bool[] toggling)
    {
        Name = goal.Name;
        IsNegative = goal.IsNegative;
        ProgressText = $"{completions}/{totalSlots}";
        EarnedText = earnedReward > 0 ? FormatMoney(earnedReward, currency) : "";
        SelectedDate = selectedDate;

        int desiredCount = daySlots.Count;
        while (Slots.Count < desiredCount)
        {
            Slots.Add(new SlotViewModel(Slots.Count, ToggleSlotCommand));
        }
        while (Slots.Count > desiredCount)
        {
            Slots.RemoveAt(Slots.Count - 1);
        }
        for (int i = 0; i < desiredCount; i++)
        {
            Slots[i].IsOn = daySlots[i];
            Slots[i].IsNegative = goal.IsNegative;
            Slots[i].IsToggling = i < toggling.Length && toggling[i];
        }
    }

    [RelayCommand]
    private async Task ToggleSlot(SlotViewModel? slot)
    {
        if (slot is null) return;
        var newValue = !slot.IsOn;
        await _toggle.ToggleAsync(GoalId, SelectedDate, slot.SlotIndex, newValue);
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
        // Match the web: integer if whole, else 2 decimals
        return amount == System.Math.Floor(amount)
            ? $"{symbol}{amount:0}"
            : $"{symbol}{amount:0.##}";
    }
}

using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;

namespace Goals_Windows.ViewModels;

/// <summary>Per-slot state for a goal row. <see cref="IsOn"/> is the raw
/// backend slot value — for positive goals "true = completed", for negative
/// goals "true = avoided (good)". <see cref="Filled"/> is the display state:
/// for positive goals it equals IsOn (filled means completed), for negative
/// goals it equals !IsOn (filled means failed). The toggle command flips the
/// raw IsOn, matching the web's onClick(value) pattern.</summary>
public partial class SlotViewModel : ObservableObject
{
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(Filled))]
    [NotifyPropertyChangedFor(nameof(BackgroundBrush))]
    [NotifyPropertyChangedFor(nameof(BorderBrushColor))]
    [NotifyPropertyChangedFor(nameof(IconText))]
    [NotifyPropertyChangedFor(nameof(IconBrush))]
    public partial bool IsOn { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ButtonOpacity))]
    [NotifyPropertyChangedFor(nameof(IsButtonEnabled))]
    public partial bool IsToggling { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(Filled))]
    [NotifyPropertyChangedFor(nameof(BackgroundBrush))]
    [NotifyPropertyChangedFor(nameof(BorderBrushColor))]
    [NotifyPropertyChangedFor(nameof(IconText))]
    [NotifyPropertyChangedFor(nameof(IconBrush))]
    public partial bool IsNegative { get; set; }

    public int SlotIndex { get; }
    public IAsyncRelayCommand<SlotViewModel> ToggleCommand { get; }

    public SlotViewModel(int slotIndex, IAsyncRelayCommand<SlotViewModel> toggleCommand)
    {
        SlotIndex = slotIndex;
        ToggleCommand = toggleCommand;
    }

    /// <summary>Whether to render the slot as "marked" — filled with color and
    /// showing an icon. Positive: filled when completed. Negative: filled when
    /// failed (i.e. raw value is false / "didn't avoid").</summary>
    public bool Filled => IsNegative ? !IsOn : IsOn;

    public double ButtonOpacity   => IsToggling ? 0.5 : 1.0;
    public bool   IsButtonEnabled => !IsToggling;

    public Brush BackgroundBrush => Resource(Filled
        ? (IsNegative ? "GoalsRedDimBrush" : "GoalsAccentDimBrush")
        : "ButtonBackgroundTransparent");

    public Brush BorderBrushColor => Resource(Filled
        ? (IsNegative ? "GoalsRedBrush" : "GoalsAccentBrush")
        : "GoalsBorder2Brush");

    public string IconText => !Filled ? "" : (IsNegative ? "✗" : "✓");

    public Brush IconBrush => Resource(IsNegative ? "GoalsRedBrush" : "GoalsAccentBrush");

    private static Brush Resource(string key)
    {
        var res = Application.Current.Resources;
        if (res.TryGetValue(key, out var value) && value is Brush b) return b;
        return new SolidColorBrush(Microsoft.UI.Colors.Transparent);
    }
}

using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;

namespace Goals_Windows.ViewModels;

/// <summary>One day in the 7-day WeekStrip. Mirrors the web's
/// <c>.day-btn</c>: an outlined button with a small uppercase day name and
/// a larger day number. Selected days get an accent border + tint; future or
/// before-start-date days are disabled at low opacity.</summary>
public partial class DayCellViewModel : ObservableObject
{
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(BackgroundBrush))]
    [NotifyPropertyChangedFor(nameof(BorderBrushColor))]
    [NotifyPropertyChangedFor(nameof(NameBrush))]
    [NotifyPropertyChangedFor(nameof(NumberBrush))]
    public partial bool IsSelected { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(NumberBrush))]
    public partial bool IsToday { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(Opacity))]
    [NotifyPropertyChangedFor(nameof(IsEnabled))]
    public partial bool IsDisabled { get; set; }

    /// <summary>True while the pointer is over the cell. Lets non-selected
    /// cells preview the accent paint on hover (border + day name + number),
    /// matching the web's `.day-btn:hover` rules.</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(BorderBrushColor))]
    [NotifyPropertyChangedFor(nameof(NameBrush))]
    [NotifyPropertyChangedFor(nameof(NumberBrush))]
    public partial bool IsHovered { get; set; }

    public bool IsEnabled => !IsDisabled;

    [ObservableProperty] public partial string Date { get; set; }
    [ObservableProperty] public partial string DayName { get; set; }
    [ObservableProperty] public partial string DayNumber { get; set; }
    public IAsyncRelayCommand<string> SelectCommand { get; }

    public DayCellViewModel(
        string date,
        string dayName,
        string dayNumber,
        IAsyncRelayCommand<string> selectCommand)
    {
        Date = date;
        DayName = dayName;
        DayNumber = dayNumber;
        SelectCommand = selectCommand;
    }

    /// <summary>Update the date/name/number fields without recreating the
    /// instance, so the parent ObservableCollection can recycle the same
    /// cell across week changes.</summary>
    public void UpdateBasics(string date, string dayName, string dayNumber)
    {
        Date = date;
        DayName = dayName;
        DayNumber = dayNumber;
    }

    public double Opacity => IsDisabled ? 0.2 : 1.0;

    public Brush BackgroundBrush => IsSelected
        ? Resource("GoalsAccentDimBrush")
        : Resource("ButtonBackgroundTransparent");

    private bool HoverActive => IsHovered && !IsSelected && !IsDisabled;

    public Brush BorderBrushColor => (IsSelected || HoverActive)
        ? Resource("GoalsAccentBrush")
        : Resource("ButtonBackgroundTransparent");

    public Brush NameBrush => (IsSelected || HoverActive)
        ? Resource("GoalsAccentBrush")
        : Resource("GoalsText3Brush");

    public Brush NumberBrush => (IsSelected || HoverActive)
        ? Resource("GoalsAccentBrush")
        : (IsToday ? Resource("GoalsTextBrush") : Resource("GoalsText2Brush"));

    private static Brush Resource(string key)
    {
        var res = Application.Current.Resources;
        if (res.TryGetValue(key, out var value) && value is Brush b) return b;
        return new SolidColorBrush(Microsoft.UI.Colors.Transparent);
    }
}

using Goals_Windows.Controls;
using Goals_Windows.ViewModels;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using System.Collections.Generic;
using System.ComponentModel;
using System.Numerics;

namespace Goals_Windows.Views;

/// <summary>Today tab — shows the goals list with toggleable slots. The
/// ViewModel subscribes to <see cref="Goals_Windows.Services.State.IGoalsState"/>
/// via the singleton, so it MUST be disposed on navigation away or the
/// transient VM (and this page) will leak across tab switches.</summary>
public sealed partial class TodayPage : Page
{
    public MainPageViewModel ViewModel { get; }

    public TodayPage()
    {
        ViewModel = App.Host.Services.GetRequiredService<MainPageViewModel>();
        InitializeComponent();
        ViewModel.PropertyChanged += OnViewModelPropertyChanged;
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        ViewModel.PropertyChanged -= OnViewModelPropertyChanged;
        ViewModel.Dispose();
        base.OnNavigatedFrom(e);
    }

    private void OnViewModelPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(MainPageViewModel.WeekPct)) UpdateProgressFill();
    }

    /// <summary>Day-cell tap. ItemsRepeater doesn't propagate DataContext to
    /// template roots, so we read the VM from <c>fe.Tag</c> (bound in XAML via
    /// <c>Tag="{x:Bind}"</c>). Skip disabled cells (future / before start).</summary>
    private void OnDayTapped(object sender, TappedRoutedEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.Tag is DayCellViewModel day && day.IsEnabled)
        {
            if (day.SelectCommand.CanExecute(day.Date)) day.SelectCommand.Execute(day.Date);
        }
    }

    /// <summary>Wire hover on the day Border after it loads. Hovering shifts
    /// the border + text to the accent (matches the web's `.day-btn:hover`).
    /// Selected/disabled cells skip the hover preview — they already have
    /// their own paint. Hand cursor is also applied here.</summary>
    private void OnDayLoaded(object sender, RoutedEventArgs e)
    {
        if (sender is not Border b) return;
        CursorHelper.SetHand(b);
        b.PointerEntered -= OnDayPointerEntered;
        b.PointerExited  -= OnDayPointerExited;
        b.PointerEntered += OnDayPointerEntered;
        b.PointerExited  += OnDayPointerExited;
    }

    private void OnDayPointerEntered(object sender, PointerRoutedEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.Tag is DayCellViewModel day) day.IsHovered = true;
    }

    private void OnDayPointerExited(object sender, PointerRoutedEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.Tag is DayCellViewModel day) day.IsHovered = false;
    }

    private void OnTodayBtnLoaded(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement fe)
        {
            UpdateCenterPoint(fe);
            CursorHelper.SetHand(fe);
        }
    }

    private void OnTodayBtnPointerEntered(object sender, PointerRoutedEventArgs e)
    {
        // We're already on today → the button does nothing, no visual reaction.
        if (!ViewModel.CanGoToday) return;
        if (sender is FrameworkElement fe)
        {
            UpdateCenterPoint(fe);
            fe.Scale = new Vector3(1.15f, 1.15f, 1f);
        }
        TodayIconGlyph.Foreground = (Brush)Application.Current.Resources["GoalsAccentBrush"];
    }

    private void OnTodayBtnPointerExited(object sender, PointerRoutedEventArgs e)
    {
        if (sender is FrameworkElement fe) fe.Scale = new Vector3(1f, 1f, 1f);
        TodayIconGlyph.Foreground = ViewModel.TodayIconForeground;
    }

    private void OnChevronLoaded(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement fe) CursorHelper.SetHand(fe);
    }

    private void OnDateInputLoaded(object sender, RoutedEventArgs e)
    {
        if (sender is not Border b) return;
        CursorHelper.SetHand(b);
        b.PointerEntered += (_, _) =>
            b.BorderBrush = (Brush)Application.Current.Resources["GoalsText3Brush"];
        b.PointerExited += (_, _) =>
            b.BorderBrush = (Brush)Application.Current.Resources["GoalsBorder2Brush"];
    }

    /// <summary>Tap on the date label opens the CalendarView Flyout. Don't
    /// open if the tap originated on the Home button (separate action).</summary>
    private void OnDateInputTapped(object sender, TappedRoutedEventArgs e)
    {
        if (e.OriginalSource is FrameworkElement src)
        {
            DependencyObject? walker = src;
            while (walker is not null)
            {
                if (walker is Button) return;
                walker = Microsoft.UI.Xaml.Media.VisualTreeHelper.GetParent(walker);
            }
        }
        Microsoft.UI.Xaml.Controls.Primitives.FlyoutBase.ShowAttachedFlyout(DateInputBorder);
    }

    // While we're seeding the CalendarView on Opened, the SelectedDates.Add
    // call fires SelectedDatesChanged — which would Hide() the flyout we just
    // opened. Flag lets the change handler distinguish "user picked a day"
    // from "we're pre-populating".
    private bool _seedingCalendar;

    private void OnDateFlyoutOpened(object sender, object e)
    {
        _seedingCalendar = true;
        try
        {
            DateCalendar.SelectedDates.Clear();

            // MaxDate = end of the current month so May renders fully (was
            // snapping to April because it treated May "partially past max"
            // when MaxDate=today). MinDate = start_date from settings so the
            // user can't scroll back before the goal-tracking start. Future
            // days within the current month are blacked out per-item.
            var today = System.DateTime.Today;
            int daysInMonth = System.DateTime.DaysInMonth(today.Year, today.Month);
            var monthEnd = new System.DateTime(today.Year, today.Month, daysInMonth);
            DateCalendar.MaxDate = new System.DateTimeOffset(monthEnd, System.TimeSpan.Zero);
            if (!string.IsNullOrEmpty(ViewModel.StartDate)
                && DateOnly.TryParseExact(ViewModel.StartDate, "yyyy-MM-dd", out var startD))
            {
                DateCalendar.MinDate = new System.DateTimeOffset(
                    startD.Year, startD.Month, startD.Day, 0, 0, 0, System.TimeSpan.Zero);
            }

            Goals_Windows.Controls.AppLog.Write(
                $"[DatePicker] flyout opened. SelectedDate={ViewModel.SelectedDate}, MaxDate={DateCalendar.MaxDate:yyyy-MM-dd}");
            if (DateOnly.TryParseExact(ViewModel.SelectedDate, "yyyy-MM-dd", out var sel))
            {
                var dto = new System.DateTimeOffset(sel.Year, sel.Month, sel.Day, 0, 0, 0, System.TimeSpan.Zero);
                DateCalendar.SelectedDates.Add(dto);
                DateCalendar.SetDisplayDate(dto);
                DispatcherQueue.TryEnqueue(
                    Microsoft.UI.Dispatching.DispatcherQueuePriority.Low,
                    () =>
                    {
                        DateCalendar.SetDisplayDate(dto);
                        // Cells may be recycled from a previous open with a
                        // stale background — walk the tree and repaint so
                        // the circle lands on the VM's current selection,
                        // not whatever the cells had last time. Deferred to
                        // Low priority so the SetDisplayDate's layout pass
                        // finishes first and the cells exist.
                        RepaintAllVisibleDayItems(dto);
                    });
                Goals_Windows.Controls.AppLog.Write($"[DatePicker] display dto={dto:yyyy-MM-dd}");
            }
        }
        finally { _seedingCalendar = false; }
    }

    /// <summary>WinUI 3's per-day customization hook. Phase 0 fires once per
    /// item — blacks out future days, and paints the selected day's background
    /// lime. The chrome has no `CalendarViewSelectedBackground` property, so
    /// the selected indicator (ring vs fill) has to be drawn on the item
    /// itself; CalendarViewDayItem inherits Control, so its Background paints
    /// behind the chrome's text. Items are virtualized — we clear stale lime
    /// on non-selected items so a recycled cell doesn't carry over.</summary>
    private void OnCalendarDayChanging(CalendarView sender, CalendarViewDayItemChangingEventArgs args)
    {
        if (args.Phase != 0) return;
        var today = System.DateTime.Today;
        args.Item.IsBlackout = args.Item.Date.Date > today;

        // Read selected date from the ViewModel rather than
        // DateCalendar.SelectedDates: Phase 0 can fire BEFORE
        // OnDateFlyoutOpened reseats SelectedDates to the actual day, so the
        // chrome's transient "today is selected by default" state would
        // otherwise win. The VM is the single source of truth.
        var itemDate = args.Item.Date.Date;
        bool isSelected = false;
        if (DateOnly.TryParseExact(ViewModel.SelectedDate, "yyyy-MM-dd", out var sel))
        {
            isSelected = sel.Year == itemDate.Year
                      && sel.Month == itemDate.Month
                      && sel.Day == itemDate.Day;
        }
        args.Item.Background = isSelected
            ? (Brush)Application.Current.Resources["GoalsAccentBrush"]
            : new SolidColorBrush(Microsoft.UI.Colors.Transparent);
    }

    private void OnCalendarDateSelected(CalendarView sender, CalendarViewSelectedDatesChangedEventArgs args)
    {
        if (_seedingCalendar) return;
        if (args.AddedDates.Count == 0) return;
        var dt = args.AddedDates[0].DateTime;
        var iso = dt.ToString("yyyy-MM-dd");
        if (ViewModel.SelectDayCommand.CanExecute(iso))
        {
            ViewModel.SelectDayCommand.Execute(iso);
        }
        // CalendarViewDayItemChanging only fires on Phase 0 (first prepare
        // of a virtualised cell). When the user picks a new day in the
        // same month, the existing cells stay in view and their
        // args.Item.Background sticks at whatever we last painted. Walk
        // the visual tree and re-paint every visible day so the accent
        // circle moves with the new selection.
        RepaintAllVisibleDayItems(dt);
        DateFlyout.Hide();
    }

    private void RepaintAllVisibleDayItems(System.DateTimeOffset newSelected)
    {
        var accent = (Brush)Application.Current.Resources["GoalsAccentBrush"];
        var transparent = new SolidColorBrush(Microsoft.UI.Colors.Transparent);
        foreach (var item in FindAllDescendants<CalendarViewDayItem>(DateCalendar))
        {
            item.Background = item.Date.Date == newSelected.Date ? accent : transparent;
        }
    }

    private static System.Collections.Generic.IEnumerable<T> FindAllDescendants<T>(DependencyObject root)
        where T : DependencyObject
    {
        int n = Microsoft.UI.Xaml.Media.VisualTreeHelper.GetChildrenCount(root);
        for (int i = 0; i < n; i++)
        {
            var child = Microsoft.UI.Xaml.Media.VisualTreeHelper.GetChild(root, i);
            if (child is T match) yield return match;
            foreach (var deeper in FindAllDescendants<T>(child)) yield return deeper;
        }
    }

    private static void UpdateCenterPoint(FrameworkElement fe)
    {
        fe.CenterPoint = new Vector3((float)(fe.ActualWidth / 2), (float)(fe.ActualHeight / 2), 0f);
    }

    /// <summary>Anchor the fill to the left and recompute its width whenever
    /// the bar resizes or the percentage changes. Built-in ProgressBar's
    /// determinate animation pinches in from both edges; the web shrinks
    /// only from the right, so we paint our own.</summary>
    private void OnProgressSizeChanged(object sender, SizeChangedEventArgs e) => UpdateProgressFill();

    private void UpdateProgressFill()
    {
        double pct = System.Math.Clamp(ViewModel.WeekPct, 0, 100) / 100.0;
        ProgressFill.Width = ProgressBg.ActualWidth * pct;
    }

    // After a click, suppress hover paint on that slot until the pointer
    // actually leaves it. Without this the slot flashes the hover preview
    // (the OPPOSITE of what was just applied), which reads as "the action
    // didn't take" even though it did. Keyed by element reference so each
    // realized item tracks its own state.
    private readonly HashSet<FrameworkElement> _suppressHoverAfterClick = new();

    private void OnSlotLoaded(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement fe)
        {
            UpdateCenterPoint(fe);
            CursorHelper.SetHandRecursive(fe);
        }
    }

    private void OnSlotPointerEntered(object sender, PointerRoutedEventArgs e)
    {
        if (sender is not Border b || b.Tag is not SlotViewModel slot) return;
        // Re-apply Hand cursor here too — WinUI doesn't always re-evaluate
        // ProtectedCursor after layout changes (e.g. when the icon TextBlock's
        // content flips between "✓" and ""), so the cursor can stick on the
        // default arrow until the next pointer move.
        CursorHelper.SetHand(b);
        if (_suppressHoverAfterClick.Contains(b)) return;
        UpdateCenterPoint(b);
        b.Scale = new Vector3(1.1f, 1.1f, 1f);
        if (slot.Filled) return;          // already showing its color
        b.Background  = Brush(slot.IsNegative ? "GoalsRedDimBrush" : "GoalsAccentDimBrush");
        b.BorderBrush = Brush(slot.IsNegative ? "GoalsRedBrush"    : "GoalsAccentBrush");
    }

    private void OnSlotPointerExited(object sender, PointerRoutedEventArgs e)
    {
        if (sender is not Border b || b.Tag is not SlotViewModel slot) return;
        _suppressHoverAfterClick.Remove(b);
        b.Scale = new Vector3(1f, 1f, 1f);
        // Snap back to the VM's current brushes (which already reflect any
        // toggle that happened while we were hovering).
        b.Background  = slot.BackgroundBrush;
        b.BorderBrush = slot.BorderBrushColor;
    }

    private void OnSlotTapped(object sender, TappedRoutedEventArgs e)
    {
        if (sender is not Border b || b.Tag is not SlotViewModel slot || slot.IsToggling) return;
        _suppressHoverAfterClick.Add(b);
        b.Scale = new Vector3(1f, 1f, 1f);
        b.Background  = slot.BackgroundBrush;
        b.BorderBrush = slot.BorderBrushColor;
        slot.ToggleCommand.Execute(slot);
    }

    private static Brush Brush(string key)
    {
        var res = Application.Current.Resources;
        return res.TryGetValue(key, out var v) && v is Brush b
            ? b
            : new SolidColorBrush(Microsoft.UI.Colors.Transparent);
    }
}

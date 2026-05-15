using Goals_Windows.Controls;
using Goals_Windows.ViewModels;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using System.Numerics;

namespace Goals_Windows.Views;

public sealed partial class GoalFormDialog : ContentDialog
{
    private const double TitleBarReserve = 48;

    public GoalFormViewModel ViewModel { get; }

    public GoalFormDialog(GoalFormViewModel viewModel)
    {
        ViewModel = viewModel;
        InitializeComponent();
        Opened += OnDialogOpened;
        Closing += OnDialogClosing;
    }

    private Border? _bgElement;
    private UIElement? _rootVisual;
    private bool _closingAnimationRan;

    /// <summary>Slide the card back UP off screen when dismissing. Defer the
    /// close itself so the animation has time to play.</summary>
    private async void OnDialogClosing(ContentDialog sender, ContentDialogClosingEventArgs args)
    {
        if (_closingAnimationRan) return;
        if (_bgElement is null) return;
        args.Cancel = true;
        _closingAnimationRan = true;

        if (_rootVisual is not null)
        {
            _rootVisual.RemoveHandler(UIElement.PointerPressedEvent, (PointerEventHandler)OnLayoutRootPressed);
            _rootVisual = null;
        }

        var tt = _bgElement.RenderTransform as Microsoft.UI.Xaml.Media.TranslateTransform
                 ?? new Microsoft.UI.Xaml.Media.TranslateTransform();
        _bgElement.RenderTransform = tt;
        var anim = new Microsoft.UI.Xaml.Media.Animation.DoubleAnimation
        {
            To = -((double)(_bgElement.ActualHeight + TitleBarReserve + 50)),
            Duration = new Microsoft.UI.Xaml.Duration(System.TimeSpan.FromMilliseconds(220)),
            EasingFunction = new Microsoft.UI.Xaml.Media.Animation.CubicEase
            {
                EasingMode = Microsoft.UI.Xaml.Media.Animation.EasingMode.EaseIn,
            },
        };
        Microsoft.UI.Xaml.Media.Animation.Storyboard.SetTarget(anim, tt);
        Microsoft.UI.Xaml.Media.Animation.Storyboard.SetTargetProperty(anim, "Y");
        var sb = new Microsoft.UI.Xaml.Media.Animation.Storyboard();
        sb.Children.Add(anim);
        var tcs = new System.Threading.Tasks.TaskCompletionSource<bool>();
        sb.Completed += (_, _) => tcs.TrySetResult(true);
        sb.Begin();
        await tcs.Task;
        Hide();   // will re-fire Closing but our flag short-circuits
    }

    /// <summary>Top-align the dialog (vs centered) and run a slide-down
    /// animation. ContentDialog hard-codes `VerticalAlignment="Center"` on
    /// `BackgroundElement` (generic.xaml line 29881) and has no theme key,
    /// so we walk the template after Opened to override it. Click-outside
    /// dismiss is wired on the smoke backdrop (no LightDismissOverlayMode
    /// exists on ContentDialog). The smoke leaves the title-bar region
    /// uncovered so the user can still see / drag the window chrome.</summary>
    private void OnDialogOpened(ContentDialog sender, ContentDialogOpenedEventArgs args)
    {
        if (GetTemplateChild("BackgroundElement") is Border bg)
        {
            _bgElement = bg;
            bg.VerticalAlignment = VerticalAlignment.Top;
            bg.Margin = new Thickness(0, TitleBarReserve, 0, 0);

            // Slide-down animation: translate the card from -fullHeight up to 0
            // over 220ms with an ease-out curve. Mirrors `.sheet.slideDown` on
            // the web (frontend/src/index.css `@keyframes slideDown`).
            var tt = new Microsoft.UI.Xaml.Media.TranslateTransform { Y = -800 };
            bg.RenderTransform = tt;
            var anim = new Microsoft.UI.Xaml.Media.Animation.DoubleAnimation
            {
                To = 0,
                Duration = new Microsoft.UI.Xaml.Duration(System.TimeSpan.FromMilliseconds(220)),
                EasingFunction = new Microsoft.UI.Xaml.Media.Animation.CubicEase
                {
                    EasingMode = Microsoft.UI.Xaml.Media.Animation.EasingMode.EaseOut,
                },
            };
            Microsoft.UI.Xaml.Media.Animation.Storyboard.SetTarget(anim, tt);
            Microsoft.UI.Xaml.Media.Animation.Storyboard.SetTargetProperty(anim, "Y");
            var sb = new Microsoft.UI.Xaml.Media.Animation.Storyboard();
            sb.Children.Add(anim);
            sb.Begin();
        }
        // GetTemplateChild("SmokeLayerBackground") returns null even though
        // generic.xaml line 29880 has that name — the smoke Rectangle lives
        // in LayoutRoot's NameScope. Walk LayoutRoot's children to find it
        // and wire dismiss handlers directly on the smoke.
        if (GetTemplateChild("LayoutRoot") is Grid layoutRoot)
        {
            // PointerPressed often gets marked Handled by child controls
            // before it bubbles up. handledEventsToo:true catches it anyway.
            layoutRoot.AddHandler(
                UIElement.PointerPressedEvent,
                new PointerEventHandler(OnLayoutRootPressed),
                handledEventsToo: true);
            Goals_Windows.Controls.AppLog.Write("[Dialog] LayoutRoot handler attached (handledEventsToo)");

            if (FindDescendantByName(layoutRoot, "SmokeLayerBackground") is Rectangle smoke)
            {
                smoke.Margin = new Thickness(0, TitleBarReserve, 0, 0);
                smoke.IsHitTestVisible = true;
                smoke.AddHandler(
                    UIElement.PointerPressedEvent,
                    new PointerEventHandler(OnSmokePressed),
                    handledEventsToo: true);
                smoke.Tapped += OnSmokeTapped;
                Goals_Windows.Controls.AppLog.Write("[Dialog] smoke handlers attached (handledEventsToo)");
            }
            else
            {
                Goals_Windows.Controls.AppLog.Write("[Dialog] SmokeLayerBackground NOT FOUND in LayoutRoot subtree");
            }
        }
        if (GetTemplateChild("Container") is Border container)
        {
            container.AddHandler(
                UIElement.PointerPressedEvent,
                new PointerEventHandler(OnLayoutRootPressed),
                handledEventsToo: true);
            Goals_Windows.Controls.AppLog.Write("[Dialog] Container handler attached (handledEventsToo)");
        }
        // WinUI 3 ContentDialog hosts itself in a Popup created at Show time.
        // The smoke Rectangle is a sibling of our template root inside that
        // Popup's child subtree — NOT a descendant of LayoutRoot or anything
        // GetTemplateChild can reach, and not under XamlRoot.Content either.
        // Enumerate open popups against this dialog's XamlRoot, find the one
        // hosting us, and attach the press handler to its child root.
        // Defer so the popup is fully enumerable.
        DispatcherQueue.TryEnqueue(() =>
        {
            if (XamlRoot is null) return;
            foreach (var popup in VisualTreeHelper.GetOpenPopupsForXamlRoot(XamlRoot))
            {
                if (popup.Child is FrameworkElement popupRoot)
                {
                    popupRoot.AddHandler(
                        UIElement.PointerPressedEvent,
                        new PointerEventHandler(OnLayoutRootPressed),
                        handledEventsToo: true);
                    _rootVisual = popupRoot;
                    Goals_Windows.Controls.AppLog.Write(
                        $"[Dialog] popup handler attached on {popupRoot.GetType().Name}");
                }
            }
            if (_rootVisual is null)
                Goals_Windows.Controls.AppLog.Write("[Dialog] no open popups found");
        });
    }

    private void OnSmokePressed(object sender, PointerRoutedEventArgs e)
    {
        Goals_Windows.Controls.AppLog.Write("[Dialog] smoke PointerPressed → Hide()");
        ViewModel.PendingAction = GoalFormViewModel.Action.Cancel;
        Hide();
    }

    private void OnSmokeTapped(object sender, TappedRoutedEventArgs e)
    {
        Goals_Windows.Controls.AppLog.Write("[Dialog] smoke Tapped → Hide()");
        ViewModel.PendingAction = GoalFormViewModel.Action.Cancel;
        Hide();
    }

    /// <summary>Click-outside dismiss. Geometric check against the card
    /// (BackgroundElement) — if the press lands outside its 0..W × 0..H box,
    /// dismiss. Walking the visual tree was unreliable because the smoke
    /// layer isn't actually a descendant of the dialog's own template.</summary>
    private void OnLayoutRootPressed(object sender, PointerRoutedEventArgs e)
    {
        if (_bgElement is null) return;
        var pt = e.GetCurrentPoint(_bgElement).Position;
        double w = _bgElement.ActualWidth;
        double h = _bgElement.ActualHeight;
        bool outside = pt.X < 0 || pt.Y < 0 || pt.X > w || pt.Y > h;
        Goals_Windows.Controls.AppLog.Write(
            $"[Dialog] press from {sender.GetType().Name} ({pt.X:F0},{pt.Y:F0}) card=0..{w:F0}×0..{h:F0} outside={outside}");
        if (!outside) return;
        ViewModel.PendingAction = GoalFormViewModel.Action.Cancel;
        Hide();
    }

    private void OnSaveClick(object sender, RoutedEventArgs e)
    {
        if (!ViewModel.Validate()) return;
        ViewModel.PendingAction = GoalFormViewModel.Action.Save;
        Hide();
    }

    private void OnCancelClick(object sender, RoutedEventArgs e)
    {
        ViewModel.PendingAction = GoalFormViewModel.Action.Cancel;
        Hide();
    }

    private void OnDeleteClick(object sender, RoutedEventArgs e)
    {
        ViewModel.PendingAction = GoalFormViewModel.Action.Delete;
        Hide();
    }

    private void OnRemoveRuleClick(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is RewardRuleViewModel rule)
        {
            ViewModel.RewardRules.Remove(rule);
        }
    }

    /// <summary>Tapping outside a NumberBox moves focus away so its compact
    /// spinner buttons collapse. Tapped on TextBox/NumberBox is marked handled
    /// by those controls, so this only fires for "background" taps.</summary>
    private void OnRootTapped(object sender, TappedRoutedEventArgs e)
    {
        if (IsDescendantOfNumberBox(e.OriginalSource as DependencyObject)) return;
        SaveBtn?.Focus(FocusState.Programmatic);
    }

    private static bool IsDescendantOfNumberBox(DependencyObject? node)
    {
        while (node is not null)
        {
            if (node is NumberBox) return true;
            node = VisualTreeHelper.GetParent(node);
        }
        return false;
    }

    /// <summary>Dashed Add-Rule wrapper: shift the stroke to the accent on hover
    /// so it tracks the same lime hover language used everywhere else. The
    /// Button itself is borderless; the dashed Rectangle behind it is what
    /// the user sees, so we paint it directly.</summary>
    private void OnAddRuleHoverEnter(object sender, PointerRoutedEventArgs e)
    {
        AddRuleDash.Stroke = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["GoalsAccentBrush"];
    }

    private void OnAddRuleHoverExit(object sender, PointerRoutedEventArgs e)
    {
        AddRuleDash.Stroke = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["GoalsBorder2Brush"];
    }

    private void OnTypeOptionLoaded(object sender, RoutedEventArgs e)
    {
        if (sender is not Border b) return;
        CursorHelper.SetHand(b);
        b.PointerEntered += OnTypeOptionEntered;
        b.PointerExited  += OnTypeOptionExited;
    }

    /// <summary>Hover paint for Daily/Weekly: border + text shift to accent;
    /// the background stays exactly as the bound brush (no flash). Selected
    /// option is already accent-everything, so hover is a no-op.</summary>
    private void OnTypeOptionEntered(object sender, PointerRoutedEventArgs e)
    {
        if (sender is not Border b) return;
        bool isSelected = ReferenceEquals(b, DailyOption) ? ViewModel.IsDaily : ViewModel.IsWeekly;
        if (isSelected) return;
        var accent = (Brush)Application.Current.Resources["GoalsAccentBrush"];
        b.BorderBrush = accent;
        if (b.Child is TextBlock tb) tb.Foreground = accent;
    }

    private void OnTypeOptionExited(object sender, PointerRoutedEventArgs e)
    {
        if (sender is not Border b) return;
        bool isDaily = ReferenceEquals(b, DailyOption);
        b.BorderBrush = isDaily ? ViewModel.DailyButtonBorder : ViewModel.WeeklyButtonBorder;
        if (b.Child is TextBlock tb)
            tb.Foreground = isDaily ? ViewModel.DailyButtonForeground : ViewModel.WeeklyButtonForeground;
    }

    private void OnDailyTapped(object sender, TappedRoutedEventArgs e)
    {
        ViewModel.SetDailyCommand.Execute(null);
    }

    private void OnWeeklyTapped(object sender, TappedRoutedEventArgs e)
    {
        ViewModel.SetWeeklyCommand.Execute(null);
    }

    /// <summary>Delete: brighten on hover (opacity 0.7 → 1.0). Mirrors the
    /// web's `.btn-delete-goal:hover { opacity: 1 }`. No size or color change.</summary>
    private void OnDeleteButtonLoaded(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement fe) return;
        CursorHelper.SetHand(fe);
        fe.PointerEntered += (_, _) => fe.Opacity = 1.0;
        fe.PointerExited  += (_, _) => fe.Opacity = 0.7;
    }

    /// <summary>Save / Cancel: hand cursor + scale the INNER TextBlock 1.1×
    /// on hover, so the glyphs grow but the button container stays fixed
    /// (no overflow into the panel below). Matches the web's font-size bump.</summary>
    private void OnSaveButtonLoaded(object sender, RoutedEventArgs e)
    {
        if (sender is not Control c) return;
        CursorHelper.SetHand(c);
        c.PointerEntered += (_, _) => ScaleTextBlock(SaveText, 1.1f);
        c.PointerExited  += (_, _) => ScaleTextBlock(SaveText, 1f);
    }

    private void OnCancelButtonLoaded(object sender, RoutedEventArgs e)
    {
        if (sender is not Control c) return;
        CursorHelper.SetHand(c);
        c.PointerEntered += (_, _) => ScaleTextBlock(CancelText, 1.1f);
        c.PointerExited  += (_, _) => ScaleTextBlock(CancelText, 1f);
    }

    private static void ScaleTextBlock(TextBlock tb, float scale)
    {
        tb.CenterPoint = new Vector3((float)(tb.ActualWidth / 2), (float)(tb.ActualHeight / 2), 0f);
        tb.Scale = new Vector3(scale, scale, 1f);
    }

    /// <summary>NumberBox only commits Value on focus loss / Enter — so the
    /// `/N` divisor on each reward rule stays stale while the user is still
    /// typing. Reach into the NumberBox's inner TextBox (template part
    /// "InputBox") and listen to TextChanged so we can push the new value
    /// to the VM on every keystroke.</summary>
    private void OnTimesPerWeekLoaded(object sender, RoutedEventArgs e)
    {
        if (sender is not NumberBox nb) return;
        nb.ApplyTemplate();
        DispatcherQueue.TryEnqueue(() =>
        {
            if (FindDescendantByName(nb, "InputBox") is TextBox tb)
            {
                tb.TextChanged -= OnTimesPerWeekTextChanged;
                tb.TextChanged += OnTimesPerWeekTextChanged;
            }
            if (FindDescendantByName(nb, "DeleteButton") is FrameworkElement btn)
            {
                btn.Visibility = Visibility.Collapsed;
                btn.Width = 0;
                btn.IsHitTestVisible = false;
            }
        });
    }

    private void OnTimesPerWeekTextChanged(object sender, TextChangedEventArgs e)
    {
        if (sender is not TextBox tb) return;
        if (double.TryParse(tb.Text, out double v) && v >= 1 && v <= 7)
        {
            ViewModel.TimesPerWeek = v;
        }
    }

    private static FrameworkElement? FindDescendantByName(DependencyObject parent, string name)
    {
        int count = VisualTreeHelper.GetChildrenCount(parent);
        for (int i = 0; i < count; i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is FrameworkElement fe && fe.Name == name) return fe;
            var found = FindDescendantByName(child, name);
            if (found is not null) return found;
        }
        return null;
    }

    /// <summary>The TextBox/NumberBox X clear button. Its `DeleteButtonStyle`
    /// is declared inside the system template's `Grid.Resources` with x:Name
    /// (not x:Key), so an App.xaml style override is invisible to it. The
    /// only reliable way is to find the element by template name after the
    /// control loads and force it hidden in code.</summary>
    private void OnInputLoaded_HideDeleteButton(object sender, RoutedEventArgs e)
    {
        if (sender is not Control c) return;
        c.ApplyTemplate();
        DispatcherQueue.TryEnqueue(() =>
        {
            // For NumberBox, the DeleteButton lives inside the inner TextBox
            // (template part "InputBox"). FindDescendantByName walks past it
            // either way.
            if (FindDescendantByName(c, "DeleteButton") is FrameworkElement btn)
            {
                btn.Visibility = Visibility.Collapsed;
                btn.Width = 0;
                btn.MinWidth = 0;
                btn.IsHitTestVisible = false;
            }
        });
    }

    /// <summary>Hand cursor only — no scale, no other visual hover.</summary>
    private void OnCursorOnlyLoaded(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement fe) CursorHelper.SetHand(fe);
    }

    private static void OnScaleEntered(object sender, PointerRoutedEventArgs e)
    {
        if (sender is FrameworkElement fe)
        {
            UpdateCenterPoint(fe);
            fe.Scale = new Vector3(1.06f, 1.06f, 1f);
        }
    }

    private static void OnScaleExited(object sender, PointerRoutedEventArgs e)
    {
        if (sender is FrameworkElement fe) fe.Scale = new Vector3(1f, 1f, 1f);
    }

    private static void UpdateCenterPoint(FrameworkElement fe)
    {
        fe.CenterPoint = new Vector3((float)(fe.ActualWidth / 2), (float)(fe.ActualHeight / 2), 0f);
    }
}

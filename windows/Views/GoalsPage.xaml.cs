using Goals_Windows.ViewModels;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;

namespace Goals_Windows.Views;

/// <summary>Goals tab — drag-to-reorder list, click a card to edit.</summary>
public sealed partial class GoalsPage : Page
{
    public GoalsPageViewModel ViewModel { get; }

    public GoalsPage()
    {
        ViewModel = App.Host.Services.GetRequiredService<GoalsPageViewModel>();
        InitializeComponent();
    }

    private void OnCardClicked(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is GoalCardViewModel card)
        {
            _ = ViewModel.OpenEditAsync(card);
        }
    }

    private void OnCardDragCompleted(ListViewBase sender, DragItemsCompletedEventArgs args)
    {
        // After the user releases a drag, ViewModel.Cards has already been
        // reordered in place by the ListView. Persist the new order.
        _ = ViewModel.PersistReorderAsync();
    }

    /// <summary>Card hover: shift border to the accent on PointerEntered.
    /// Wired in code-behind via Loaded — declaring PointerEntered on a
    /// DataTemplate-rooted Border directly in XAML trips the XAML compiler
    /// (silent exit code 1, no diagnostic), so we hook the events here.</summary>
    private void OnCardLoaded(object sender, RoutedEventArgs e)
    {
        if (sender is Border b)
        {
            b.PointerEntered -= OnCardPointerEntered;
            b.PointerExited  -= OnCardPointerExited;
            b.PointerEntered += OnCardPointerEntered;
            b.PointerExited  += OnCardPointerExited;
        }
    }

    private void OnCardPointerEntered(object sender, PointerRoutedEventArgs e)
    {
        if (sender is Border b) b.BorderBrush = Brush("GoalsAccentBrush");
    }

    private void OnCardPointerExited(object sender, PointerRoutedEventArgs e)
    {
        if (sender is Border b) b.BorderBrush = Brush("GoalsBorderBrush");
    }

    private void OnNewGoalGridLoaded(object sender, RoutedEventArgs e)
    {
        if (sender is Grid g)
        {
            g.PointerEntered -= OnNewGoalHoverEnter;
            g.PointerExited  -= OnNewGoalHoverExit;
            g.PointerEntered += OnNewGoalHoverEnter;
            g.PointerExited  += OnNewGoalHoverExit;
            // Override the inner Button's hover paint so only the dashed
            // Rectangle responds to hover — the Button itself stays flat.
            if (NewGoalBtn is not null)
            {
                NewGoalBtn.Resources["ButtonBackgroundPointerOver"]  = new SolidColorBrush(Microsoft.UI.Colors.Transparent);
                NewGoalBtn.Resources["ButtonBackgroundPressed"]      = new SolidColorBrush(Microsoft.UI.Colors.Transparent);
                NewGoalBtn.Resources["ButtonBorderBrushPointerOver"] = new SolidColorBrush(Microsoft.UI.Colors.Transparent);
                NewGoalBtn.Resources["ButtonBorderBrushPressed"]     = new SolidColorBrush(Microsoft.UI.Colors.Transparent);
                NewGoalBtn.Resources["ButtonForegroundPointerOver"]  = Brush("GoalsAccentBrush");
                NewGoalBtn.Resources["ButtonForegroundPressed"]      = Brush("GoalsAccentBrush");
            }
        }
    }

    private void OnNewGoalHoverEnter(object sender, PointerRoutedEventArgs e)
    {
        NewGoalDash.Stroke = Brush("GoalsAccentBrush");
    }

    private void OnNewGoalHoverExit(object sender, PointerRoutedEventArgs e)
    {
        NewGoalDash.Stroke = Brush("GoalsBorder2Brush");
    }

    private static Brush Brush(string key)
    {
        var res = Application.Current.Resources;
        return res.TryGetValue(key, out var v) && v is Brush b
            ? b
            : new SolidColorBrush(Microsoft.UI.Colors.Transparent);
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        ViewModel.Dispose();
        base.OnNavigatedFrom(e);
    }
}

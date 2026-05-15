using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Goals_Windows.Models.Api;
using Goals_Windows.Services;
using Goals_Windows.Services.State;
using Goals_Windows.Views;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml.Controls;
using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;

namespace Goals_Windows.ViewModels;

/// <summary>Goals tab view model. Owns the card list, opens the form dialog
/// for create/edit/delete, and persists DnD reorder. Card click → edit.</summary>
public partial class GoalsPageViewModel : ObservableObject, IDisposable
{
    private readonly IGoalsState _state;
    private readonly GoalsCrudService _crud;
    private readonly IServiceProvider _serviceProvider;
    private bool _suppressRebuild;

    [ObservableProperty] public partial bool ShowEmptyState { get; set; }

    public ObservableCollection<GoalCardViewModel> Cards { get; } = new();

    public GoalsPageViewModel(IGoalsState state, GoalsCrudService crud, IServiceProvider serviceProvider)
    {
        _state = state;
        _crud = crud;
        _serviceProvider = serviceProvider;
        _state.Changed += OnStateChanged;
        // Surface CRUD failures the service used to swallow — web + Android
        // toast on the same conditions, Windows was the only client that
        // failed silently after logging.
        _crud.ErrorOccurred += OnCrudError;
        Rebuild(_state.Current);
    }

    private void OnCrudError(string message)
    {
        var dispatcher = App.DispatcherQueue;
        if (dispatcher?.HasThreadAccess == true) { ShowErrorDialog(message); }
        else { dispatcher?.TryEnqueue(DispatcherQueuePriority.Normal, () => ShowErrorDialog(message)); }
    }

    private async void ShowErrorDialog(string message)
    {
        var xamlRoot = App.Window?.Content?.XamlRoot;
        if (xamlRoot is null) return;
        var dialog = new ContentDialog
        {
            Title = "Something went wrong",
            Content = message,
            CloseButtonText = "OK",
            XamlRoot = xamlRoot,
        };
        try { await dialog.ShowAsync(); } catch { /* dialog stacking — swallow */ }
    }

    [RelayCommand]
    private async Task NewGoal()
    {
        var vm = _serviceProvider.GetRequiredService<GoalFormViewModel>();
        vm.LoadForCreate();
        await ShowFormAsync(vm);
    }

    public async Task OpenEditAsync(GoalCardViewModel card)
    {
        if (card.Goal is null) return;
        var vm = _serviceProvider.GetRequiredService<GoalFormViewModel>();
        vm.LoadForEdit(card.Goal);
        await ShowFormAsync(vm);
    }

    /// <summary>Called after a drag completes; ListView has already reordered
    /// <see cref="Cards"/>. Push the new order to the backend.</summary>
    public async Task PersistReorderAsync()
    {
        var moves = Cards
            .Select((c, i) => (c.GoalId, NewOrder: i))
            .ToList();
        try
        {
            _suppressRebuild = true;
            await _crud.ReorderAsync(moves);
        }
        finally
        {
            _suppressRebuild = false;
            Rebuild(_state.Current);
        }
    }

    private async Task ShowFormAsync(GoalFormViewModel vm)
    {
        var dialog = new GoalFormDialog(vm) { XamlRoot = App.Window.Content.XamlRoot };
        await dialog.ShowAsync();

        switch (vm.PendingAction)
        {
            case GoalFormViewModel.Action.Save:
                if (vm.IsEditMode)
                {
                    await _crud.UpdateAsync(vm.EditingGoalId, vm.ToUpdate());
                    if (vm.EnabledChanged)
                    {
                        await _crud.SetEnabledAsync(vm.EditingGoalId, vm.Enabled);
                    }
                }
                else
                {
                    var nextOrder = Cards.Count == 0 ? 0 : Cards.Max(c => c.Order) + 1;
                    var body = vm.ToCreate() with { Order = nextOrder };
                    await _crud.CreateAsync(body);
                }
                break;

            case GoalFormViewModel.Action.Delete:
                if (vm.IsEditMode)
                {
                    var confirm = new ContentDialog
                    {
                        Title = "Delete goal?",
                        Content = $"\"{vm.Name}\" will be deleted along with all its logs. This cannot be undone.",
                        PrimaryButtonText = "Delete",
                        CloseButtonText = "Cancel",
                        DefaultButton = ContentDialogButton.Close,
                        XamlRoot = App.Window.Content.XamlRoot
                    };
                    if (await confirm.ShowAsync() == ContentDialogResult.Primary)
                    {
                        await _crud.DeleteAsync(vm.EditingGoalId);
                    }
                }
                break;
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

    /// <summary>Reconcile <see cref="Cards"/> with the snapshot. For a pure
    /// reorder (same set of goals, different order), this emits one
    /// `ObservableCollection.Move` per misplaced card — ListView's
    /// `ItemContainerTransitions` animates only those, instead of flashing
    /// the whole list as a `Clear()` + re-add would.</summary>
    private void Rebuild(AppSnapshot snapshot)
    {
        if (_suppressRebuild) return;

        var enabledIds = snapshot.GoalWeeks.Where(gw => gw.Enabled).Select(gw => gw.GoalId).ToHashSet();
        var ordered = snapshot.Goals.OrderBy(g => g.Order).ToList();
        var desiredIds = ordered.Select(g => g.Id).ToList();
        var desiredSet = desiredIds.ToHashSet();
        var existing = Cards.ToDictionary(c => c.GoalId);

        // 1. Remove deleted goals.
        for (int i = Cards.Count - 1; i >= 0; i--)
        {
            if (!desiredSet.Contains(Cards[i].GoalId)) Cards.RemoveAt(i);
        }

        // 2. Update existing cards; append newly-created ones (Move loop below
        //    will park them at their correct index).
        foreach (var goal in ordered)
        {
            if (!existing.TryGetValue(goal.Id, out var card))
            {
                card = new GoalCardViewModel(goal.Id, goal.Order);
                existing[goal.Id] = card;
                Cards.Add(card);
            }
            card.UpdateFrom(goal, enabledIds.Contains(goal.Id));
        }

        // 3. Reorder in place — Move each misplaced card to its target index.
        for (int target = 0; target < desiredIds.Count; target++)
        {
            var wantId = desiredIds[target];
            if (Cards[target].GoalId == wantId) continue;
            for (int j = target + 1; j < Cards.Count; j++)
            {
                if (Cards[j].GoalId == wantId)
                {
                    Cards.Move(j, target);
                    break;
                }
            }
        }

        ShowEmptyState = Cards.Count == 0;
    }

    public void Dispose()
    {
        _state.Changed -= OnStateChanged;
        _crud.ErrorOccurred -= OnCrudError;
    }
}

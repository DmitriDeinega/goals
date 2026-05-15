using Goals_Windows.Models.Api;
using Goals_Windows.Services.Api;
using Goals_Windows.Services.Session;
using Goals_Windows.Services.State;
using Microsoft.Extensions.Logging;
using System;
using System.Collections.Generic;
using System.Threading.Tasks;

namespace Goals_Windows.Services;

/// <summary>
/// Orchestrates Goal CRUD (and goal-week enable/disable + reorder) against
/// the backend with the same pessimistic-write conventions as
/// <see cref="ToggleService"/>: pass X-Sequence + X-Session-ID, apply the
/// returned <c>GoalChangedPayload</c> to <see cref="IGoalsState"/>, refetch
/// /api/init on a 409 conflict.
/// </summary>
public sealed class GoalsCrudService
{
    private readonly GoalsApiClient _api;
    private readonly IGoalsState _state;
    private readonly SessionIdProvider _session;
    private readonly ILogger<GoalsCrudService> _logger;

    /// <summary>Raised when a non-conflict CRUD failure occurs. The hosting
    /// VM is expected to subscribe and surface the message (toast / dialog),
    /// so failures aren't silently swallowed. 409 conflicts are NOT raised
    /// here — those go through <see cref="ResyncAsync"/> which is a
    /// recoverable, expected flow.</summary>
    public event Action<string>? ErrorOccurred;

    public GoalsCrudService(
        GoalsApiClient api,
        IGoalsState state,
        SessionIdProvider session,
        ILogger<GoalsCrudService> logger)
    {
        _api = api;
        _state = state;
        _session = session;
        _logger = logger;
    }

    public Task CreateAsync(GoalCreate body) => RunPayloadAsync(
        ct => _api.CreateGoalAsync(body, _state.Current.Sequence, _session.Get(), ct),
        "create");

    public Task UpdateAsync(string goalId, GoalUpdate body) => RunPayloadAsync(
        ct => _api.UpdateGoalAsync(goalId, body, _state.Current.Sequence, _session.Get(), ct),
        "update");

    public Task DeleteAsync(string goalId) => RunPayloadAsync(
        ct => _api.DeleteGoalAsync(goalId, _state.Current.Sequence, _session.Get(), ct),
        "delete");

    public Task SetEnabledAsync(string goalId, bool enabled) => RunPayloadAsync(
        ct => _api.SetGoalEnabledAsync(goalId, enabled, _state.Current.Sequence, _session.Get(), ct),
        "set-enabled");

    public async Task ReorderAsync(IReadOnlyList<(string GoalId, int NewOrder)> moves)
    {
        try
        {
            await _api.ReorderGoalsAsync(moves, _state.Current.Sequence, _session.Get());
            // Apply optimistically — the backend broadcasts per-goal "reordered"
            // events via SSE, but they arrive after this method returns, so the
            // page's post-reorder rebuild would otherwise read stale state and
            // snap the cards back to the old order. The SSE events are
            // idempotent against this update.
            _state.ApplyReorderOptimistic(moves);
        }
        catch (SequenceConflictException)
        {
            await ResyncAsync();
        }
        catch (OperationCanceledException)
        {
            // App shutdown or caller cancellation — let it propagate quietly.
            throw;
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Reorder failed");
            ErrorOccurred?.Invoke($"Reorder failed: {ex.Message}");
        }
    }

    private async Task RunPayloadAsync(
        Func<System.Threading.CancellationToken, Task<Goals_Windows.Models.Api.Events.GoalChangedPayload>> call,
        string verb)
    {
        try
        {
            var payload = await call(default);
            _state.ApplyGoalChanged(payload);
        }
        catch (SequenceConflictException)
        {
            await ResyncAsync();
        }
        catch (OperationCanceledException)
        {
            // App shutdown or caller cancellation — let it propagate quietly.
            throw;
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Goal {Verb} failed", verb);
            ErrorOccurred?.Invoke($"Goal {verb} failed: {ex.Message}");
        }
    }

    private async Task ResyncAsync()
    {
        _logger.LogInformation("Sequence conflict; resynchronizing via /api/init");
        try
        {
            var init = await _api.InitAsync();
            _state.ApplyInit(init);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Resync /api/init failed");
        }
    }
}

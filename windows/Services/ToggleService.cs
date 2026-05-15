using Goals_Windows.Models.Api;
using Goals_Windows.Services.Api;
using Goals_Windows.Services.Session;
using Goals_Windows.Services.State;
using Microsoft.Extensions.Logging;
using System;
using System.Threading.Tasks;

namespace Goals_Windows.Services;

/// <summary>
/// Orchestrates the pessimistic toggle flow. The state's in-flight set is set
/// before the network call and cleared after (success, conflict, or error).
/// On 409 we refetch /api/init — that's the simplest way to recover from any
/// kind of sequence drift; the response contains the full new snapshot.
/// </summary>
public sealed class ToggleService
{
    private readonly GoalsApiClient _api;
    private readonly IGoalsState _state;
    private readonly SessionIdProvider _session;
    private readonly ILogger<ToggleService> _logger;

    public ToggleService(
        GoalsApiClient api,
        IGoalsState state,
        SessionIdProvider session,
        ILogger<ToggleService> logger)
    {
        _api = api;
        _state = state;
        _session = session;
        _logger = logger;
    }

    public async Task ToggleAsync(string goalId, string date, int slotIndex, bool newValue)
    {
        _state.MarkToggling(goalId, date, slotIndex);
        try
        {
            var snapshot = _state.Current;
            var payload = await _api.ToggleAsync(
                new LogCreate(goalId, date, slotIndex, newValue),
                snapshot.Sequence,
                _session.Get());
            _state.ApplyLogChanged(payload);
        }
        catch (SequenceConflictException)
        {
            _logger.LogInformation("Sequence conflict; resynchronizing via /api/init");
            try
            {
                var init = await _api.InitAsync();
                _state.ApplyInit(init);
            }
            catch (Exception innerEx)
            {
                _logger.LogWarning(innerEx, "Resync /api/init failed");
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Toggle failed: goal={GoalId} date={Date} slot={Slot}",
                goalId, date, slotIndex);
        }
        finally
        {
            _state.ClearToggling(goalId, date, slotIndex);
        }
    }
}

using Goals_Windows.Models.Api;
using Goals_Windows.Models.Api.Events;
using System;

namespace Goals_Windows.Services.State;

public interface IGoalsState
{
    AppSnapshot Current { get; }
    event Action<AppSnapshot>? Changed;

    void ApplyInit(InitResponse init);
    void ApplyWeekData(WeekDataResponse data);
    void ApplyLogChanged(LogChangedPayload payload);
    void ApplyGoalChanged(GoalChangedPayload payload);
    void ApplyDayChanged(DayChangedPayload payload);

    /// <summary>Optimistically apply a drag-to-reorder batch — patches each
    /// goal's Order (and the matching GoalWeek.Snapshot.order) without bumping
    /// Sequence. The server's per-goal "reordered" SSE events arrive shortly
    /// after and are idempotent against this update. Without this, the
    /// finally-block rebuild after the reorder POST re-sorts the cards from
    /// stale state and the UI snaps back to the old order.</summary>
    void ApplyReorderOptimistic(System.Collections.Generic.IReadOnlyList<(string GoalId, int NewOrder)> moves);

    void MarkToggling(string goalId, string date, int slotIndex);
    void ClearToggling(string goalId, string date, int slotIndex);
}

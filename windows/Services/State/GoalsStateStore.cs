using Goals_Windows.Models.Api;
using Goals_Windows.Models.Api.Events;
using Microsoft.Extensions.Logging;
using System;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;

namespace Goals_Windows.Services.State;

/// <summary>
/// Thread-safe singleton snapshot store. All mutations go through Apply* methods
/// which CAS-replace an AtomicReference-style holder. Subscribers receive the
/// new snapshot via the <see cref="Changed"/> event, marshalled to the UI
/// thread by the caller (see how the SSE service dispatches).
/// </summary>
public sealed class GoalsStateStore : IGoalsState
{
    private readonly ILogger<GoalsStateStore> _logger;
    private AppSnapshot _current = AppSnapshot.Empty;
    private readonly Lock _lock = new();

    public GoalsStateStore(ILogger<GoalsStateStore> logger)
    {
        _logger = logger;
    }

    public AppSnapshot Current => Volatile.Read(ref _current);

    public event Action<AppSnapshot>? Changed;

    public void ApplyInit(InitResponse init)
    {
        Mutate(s => s with
        {
            Goals = init.Goals.ToImmutableList(),
            GoalWeeks = init.GoalWeeks.ToImmutableList(),
            Logs = init.Logs.ToImmutableList(),
            Settings = init.Settings,
            Sequence = init.Seq,
            Hydrated = true
        });
    }

    /// <summary>Replace the current week's GoalWeeks + Logs with another
    /// week's data, leaving Goals/Settings/Sequence alone. Triggered when
    /// the user navigates to a different week via the WeekStrip chevrons.</summary>
    public void ApplyWeekData(WeekDataResponse data)
    {
        Mutate(s => s with
        {
            GoalWeeks = data.GoalWeeks.ToImmutableList(),
            Logs = data.Logs.ToImmutableList()
        });
    }

    public void ApplyLogChanged(LogChangedPayload payload)
    {
        if (!ShouldApplySeq(payload.Seq)) return;
        Mutate(s =>
        {
            var updatedDates = payload.Logs.Select(l => l.Date).ToHashSet();
            var kept = s.Logs.Where(l => l.GoalId != payload.GoalId || !updatedDates.Contains(l.Date));
            return s with
            {
                Logs = kept.Concat(payload.Logs).ToImmutableList(),
                Sequence = Math.Max(s.Sequence, payload.Seq)
            };
        });
    }

    public void ApplyGoalChanged(GoalChangedPayload payload)
    {
        if (!ShouldApplySeq(payload.Seq)) return;
        Mutate(s => payload.Action switch
        {
            GoalChangedAction.Created => ApplyGoalCreated(s, payload),
            GoalChangedAction.Updated => ApplyGoalUpdated(s, payload),
            GoalChangedAction.Deleted => ApplyGoalDeleted(s, payload),
            GoalChangedAction.EnabledChanged => ApplyEnabledChanged(s, payload),
            GoalChangedAction.Reordered => ApplyGoalReordered(s, payload),
            _ => s
        });
    }

    public void MarkToggling(string goalId, string date, int slotIndex)
    {
        var key = AppSnapshot.ToggleKey(goalId, date, slotIndex);
        Mutate(s => s.InFlightToggles.Contains(key)
            ? s
            : s with { InFlightToggles = s.InFlightToggles.Add(key) });
    }

    public void ClearToggling(string goalId, string date, int slotIndex)
    {
        var key = AppSnapshot.ToggleKey(goalId, date, slotIndex);
        Mutate(s => !s.InFlightToggles.Contains(key)
            ? s
            : s with { InFlightToggles = s.InFlightToggles.Remove(key) });
    }

    public void ApplyDayChanged(DayChangedPayload payload)
    {
        if (!ShouldApplySeq(payload.Seq)) return;
        Mutate(s =>
        {
            var newDates = payload.Logs.Select(l => l.Date).ToHashSet();
            var kept = s.Logs.Where(l => !newDates.Contains(l.Date));
            return s with
            {
                Logs = kept.Concat(payload.Logs).ToImmutableList(),
                Sequence = Math.Max(s.Sequence, payload.Seq)
            };
        });
    }

    private bool ShouldApplySeq(long seq)
    {
        if (seq <= 0) return true;
        var current = Current;
        if (seq < current.Sequence)
        {
            _logger.LogDebug("Skipping stale event seq={Seq} < current={Current}", seq, current.Sequence);
            return false;
        }
        return true;
    }

    private void Mutate(Func<AppSnapshot, AppSnapshot> update)
    {
        AppSnapshot next;
        lock (_lock)
        {
            next = update(_current);
            _current = next;
        }
        try
        {
            Changed?.Invoke(next);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "State change subscriber threw");
        }
    }

    private static AppSnapshot ApplyGoalCreated(AppSnapshot s, GoalChangedPayload p)
    {
        var next = s;
        if (p.Goal is not null) next = next with { Goals = next.Goals.Add(p.Goal) };
        if (p.GoalWeek is not null) next = next with { GoalWeeks = next.GoalWeeks.Add(p.GoalWeek) };
        if (p.Logs is not null) next = next with { Logs = next.Logs.AddRange(p.Logs) };
        return next with { Sequence = Math.Max(s.Sequence, p.Seq) };
    }

    private static AppSnapshot ApplyGoalUpdated(AppSnapshot s, GoalChangedPayload p)
    {
        if (p.Goal is null) return s;
        var goals = s.Goals.RemoveAll(g => g.Id == p.Goal.Id).Add(p.Goal);
        var weeks = p.GoalWeek is null
            ? s.GoalWeeks
            : s.GoalWeeks.RemoveAll(w => w.GoalId == p.GoalWeek.GoalId && w.WeekStart == p.GoalWeek.WeekStart).Add(p.GoalWeek);
        var logs = s.Logs;
        if (p.Logs is not null)
        {
            var datesByGoal = p.Logs.Select(l => (l.GoalId, l.Date)).ToHashSet();
            logs = logs.RemoveAll(l => datesByGoal.Contains((l.GoalId, l.Date))).AddRange(p.Logs);
        }
        return s with { Goals = goals, GoalWeeks = weeks, Logs = logs, Sequence = Math.Max(s.Sequence, p.Seq) };
    }

    private static AppSnapshot ApplyGoalDeleted(AppSnapshot s, GoalChangedPayload p)
    {
        if (p.GoalId is null) return s;
        var goals = s.Goals.RemoveAll(g => g.Id == p.GoalId);
        var weeks = s.GoalWeeks.RemoveAll(w => w.GoalId == p.GoalId);
        var logs = s.Logs.RemoveAll(l => l.GoalId == p.GoalId);
        if (p.ReorderedGoals is not null)
        {
            var reorderMap = new Dictionary<string, int>();
            foreach (var entry in p.ReorderedGoals)
            {
                if (entry.TryGetProperty("goal_id", out var gid) && entry.TryGetProperty("new_order", out var no))
                {
                    var id = gid.GetString();
                    if (id is not null) reorderMap[id] = no.GetInt32();
                }
            }
            goals = goals
                .Select(g => reorderMap.TryGetValue(g.Id, out var ord) ? g with { Order = ord } : g)
                .ToImmutableList();
        }
        return s with { Goals = goals, GoalWeeks = weeks, Logs = logs, Sequence = Math.Max(s.Sequence, p.Seq) };
    }

    private static AppSnapshot ApplyEnabledChanged(AppSnapshot s, GoalChangedPayload p)
    {
        if (p.GoalWeek is null) return s;
        var weeks = s.GoalWeeks
            .RemoveAll(w => w.GoalId == p.GoalWeek.GoalId && w.WeekStart == p.GoalWeek.WeekStart)
            .Add(p.GoalWeek);
        return s with { GoalWeeks = weeks, Sequence = Math.Max(s.Sequence, p.Seq) };
    }

    /// <summary>Apply a single "reordered" event (sent as items in the SSE
    /// bulk-reorder array). Each event carries a goal_id and its new_order.
    /// Patches both the live Goal.Order AND the GoalWeek.Snapshot.order —
    /// the latter is what Today's effective-goal sort uses, so missing it
    /// leaves Today out of order even after the live list is updated.</summary>
    private static AppSnapshot ApplyGoalReordered(AppSnapshot s, GoalChangedPayload p)
    {
        if (p.GoalId is null || p.NewOrder is null) return s;
        int newOrder = p.NewOrder.Value;
        var goals = s.Goals
            .Select(g => g.Id == p.GoalId ? g with { Order = newOrder } : g)
            .ToImmutableList();
        var weeks = s.GoalWeeks
            .Select(gw => gw.GoalId == p.GoalId
                ? gw with { Snapshot = PatchSnapshotOrder(gw.Snapshot, newOrder) }
                : gw)
            .ToImmutableList();
        return s with { Goals = goals, GoalWeeks = weeks, Sequence = Math.Max(s.Sequence, p.Seq) };
    }

    public void ApplyReorderOptimistic(IReadOnlyList<(string GoalId, int NewOrder)> moves)
    {
        if (moves.Count == 0) return;
        Mutate(s =>
        {
            var lookup = moves.ToDictionary(m => m.GoalId, m => m.NewOrder);
            var goals = s.Goals
                .Select(g => lookup.TryGetValue(g.Id, out var order) ? g with { Order = order } : g)
                .ToImmutableList();
            var weeks = s.GoalWeeks
                .Select(gw => lookup.TryGetValue(gw.GoalId, out var order)
                    ? gw with { Snapshot = PatchSnapshotOrder(gw.Snapshot, order) }
                    : gw)
                .ToImmutableList();
            return s with { Goals = goals, GoalWeeks = weeks };
        });
    }

    private static JsonElement PatchSnapshotOrder(JsonElement snap, int newOrder)
    {
        if (snap.ValueKind != JsonValueKind.Object) return snap;
        var node = JsonNode.Parse(snap.GetRawText())?.AsObject();
        if (node is null) return snap;
        node["order"] = newOrder;
        var bytes = Encoding.UTF8.GetBytes(node.ToJsonString());
        using var doc = JsonDocument.Parse(bytes);
        return doc.RootElement.Clone();
    }
}

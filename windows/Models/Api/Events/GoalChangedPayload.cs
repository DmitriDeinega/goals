using System.Collections.Generic;
using System.Text.Json;

namespace Goals_Windows.Models.Api.Events;

public sealed record GoalChangedPayload(
    string Action,
    Goal? Goal,
    string? GoalId,
    GoalWeek? GoalWeek,
    IReadOnlyList<Log>? Logs,
    int? NewOrder,
    string? WeekStart,
    long Seq,
    IReadOnlyList<JsonElement>? ReorderedGoals);

public static class GoalChangedAction
{
    public const string Created = "created";
    public const string Updated = "updated";
    public const string Deleted = "deleted";
    public const string EnabledChanged = "enabled_changed";
    public const string Reordered = "reordered";
}

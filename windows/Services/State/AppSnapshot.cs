using Goals_Windows.Models.Api;
using System.Collections.Immutable;

namespace Goals_Windows.Services.State;

/// <summary>An immutable snapshot of the entire client state. Every state
/// change produces a new instance and notifies subscribers. Treat as
/// read-only — mutating an inner collection in place is a bug.</summary>
public sealed record AppSnapshot(
    ImmutableList<Goal> Goals,
    ImmutableList<GoalWeek> GoalWeeks,
    ImmutableList<Log> Logs,
    AppSettings? Settings,
    ImmutableHashSet<string> InFlightToggles,
    long Sequence,
    bool Hydrated)
{
    public static AppSnapshot Empty { get; } = new(
        Goals: ImmutableList<Goal>.Empty,
        GoalWeeks: ImmutableList<GoalWeek>.Empty,
        Logs: ImmutableList<Log>.Empty,
        Settings: null,
        InFlightToggles: ImmutableHashSet<string>.Empty,
        Sequence: 0,
        Hydrated: false);

    /// <summary>Key for an in-flight toggle. Same shape as Android widget's cache.</summary>
    public static string ToggleKey(string goalId, string date, int slotIndex) =>
        $"{goalId}|{date}|{slotIndex}";

    public bool IsToggling(string goalId, string date, int slotIndex) =>
        InFlightToggles.Contains(ToggleKey(goalId, date, slotIndex));
}

using System.Collections.Generic;

namespace Goals_Windows.Models.Api;

public sealed record InitResponse(
    IReadOnlyList<Goal> Goals,
    IReadOnlyList<GoalWeek> GoalWeeks,
    IReadOnlyList<Log> Logs,
    AppSettings Settings,
    long Seq);

public sealed record WeekDataResponse(
    string WeekStart,
    IReadOnlyList<GoalWeek> GoalWeeks,
    IReadOnlyList<Log> Logs);

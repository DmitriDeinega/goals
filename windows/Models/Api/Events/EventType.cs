namespace Goals_Windows.Models.Api.Events;

/// <summary>String constants matching the SSE `event:` field from the backend.</summary>
public static class EventType
{
    public const string LogChanged = "log_changed";
    public const string GoalChanged = "goal_changed";
    public const string DayChanged = "day_changed";
    public const string Ping = "ping";
}

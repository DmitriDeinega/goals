using Goals_Windows.Models.Api;
using System;

namespace Goals_Windows.Services;

/// <summary>Computes the server-relative "today" using the timezone from
/// <see cref="AppSettings"/>. Falls back to local time if no timezone is set.
/// Date is formatted as ISO yyyy-MM-dd to match the backend's string-typed
/// `date` field on logs.</summary>
public static class GoalsClock
{
    public static string Today(AppSettings? settings)
    {
        TimeZoneInfo tz;
        try
        {
            tz = !string.IsNullOrWhiteSpace(settings?.Timezone)
                ? TimeZoneInfo.FindSystemTimeZoneById(settings.Timezone)
                : TimeZoneInfo.Local;
        }
        catch (TimeZoneNotFoundException)
        {
            tz = TimeZoneInfo.Local;
        }
        var nowInTz = TimeZoneInfo.ConvertTime(DateTimeOffset.UtcNow, tz);
        return nowInTz.ToString("yyyy-MM-dd");
    }
}

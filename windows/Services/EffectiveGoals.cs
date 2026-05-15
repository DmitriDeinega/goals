using Goals_Windows.Models.Api;
using System;
using System.Collections.Generic;
using System.Text.Json;

namespace Goals_Windows.Services;

/// <summary>
/// Builds an "effective" <see cref="Goal"/> for a given week by preferring the
/// frozen <see cref="GoalWeek.Snapshot"/> properties and falling back to the
/// live goal. Mirrors the web's pattern in
/// <c>frontend/src/pages/TodayPage.jsx</c> / <c>computeWeekSummary</c>: when a
/// goal's config is edited mid-week, the historical snapshot drives display
/// and stats for that week, while live values cover anything the snapshot
/// happens to omit.
/// </summary>
public static class EffectiveGoals
{
    public static Goal Build(GoalWeek gw, Goal? live)
    {
        var snap = gw.Snapshot;

        return new Goal(
            Id:           gw.GoalId,
            Name:         GetString(snap, "name")          ?? live?.Name        ?? string.Empty,
            Type:         GetGoalType(snap, "type")        ?? live?.Type        ?? GoalType.Daily,
            IsNegative:   GetBool(snap, "is_negative")     ?? live?.IsNegative  ?? false,
            TimesPerWeek: GetInt(snap, "times_per_week")   ?? live?.TimesPerWeek,
            TimesPerDay:  GetInt(snap, "times_per_day")    ?? live?.TimesPerDay,
            RewardRules:  GetRewardRules(snap)             ?? live?.RewardRules ?? Array.Empty<RewardRule>(),
            Order:        GetInt(snap, "order")            ?? live?.Order       ?? 0,
            Enabled:      gw.Enabled,
            Version:      live?.Version ?? 0);
    }

    private static string? GetString(JsonElement snap, string key)
    {
        if (snap.ValueKind != JsonValueKind.Object) return null;
        if (!snap.TryGetProperty(key, out var prop)) return null;
        return prop.ValueKind == JsonValueKind.String ? prop.GetString() : null;
    }

    private static int? GetInt(JsonElement snap, string key)
    {
        if (snap.ValueKind != JsonValueKind.Object) return null;
        if (!snap.TryGetProperty(key, out var prop)) return null;
        if (prop.ValueKind == JsonValueKind.Number && prop.TryGetInt32(out var i)) return i;
        return null;
    }

    private static bool? GetBool(JsonElement snap, string key)
    {
        if (snap.ValueKind != JsonValueKind.Object) return null;
        if (!snap.TryGetProperty(key, out var prop)) return null;
        return prop.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            _ => null
        };
    }

    private static GoalType? GetGoalType(JsonElement snap, string key)
    {
        var s = GetString(snap, key);
        return s switch
        {
            "daily" => GoalType.Daily,
            "weekly_x" => GoalType.WeeklyX,
            _ => null
        };
    }

    private static IReadOnlyList<RewardRule>? GetRewardRules(JsonElement snap)
    {
        if (snap.ValueKind != JsonValueKind.Object) return null;
        if (!snap.TryGetProperty("reward_rules", out var prop)) return null;
        if (prop.ValueKind != JsonValueKind.Array) return null;
        var rules = new List<RewardRule>(prop.GetArrayLength());
        foreach (var rule in prop.EnumerateArray())
        {
            int? min = GetInt(rule, "min_completions");
            double? amount = null;
            if (rule.TryGetProperty("reward_amount", out var amt) && amt.ValueKind == JsonValueKind.Number)
            {
                amount = amt.GetDouble();
            }
            if (min is int m && amount is double a) rules.Add(new RewardRule(m, a));
        }
        return rules;
    }
}

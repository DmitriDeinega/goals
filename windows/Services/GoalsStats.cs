using Goals_Windows.Models.Api;
using Goals_Windows.Services.State;
using System;
using System.Collections.Generic;
using System.Linq;

namespace Goals_Windows.Services;

/// <summary>Ports of the JS <c>computeGoalStats</c> / <c>computeWeekSummary</c>
/// from frontend/src/hooks/useAppState.js. Same formulas — if you change them
/// here, sync the web/Android equivalents.</summary>
public static class GoalsStats
{
    public readonly record struct GoalStats(int Completions, int TotalSlots, double EarnedReward);

    public readonly record struct WeekSummary(int Pct, double TotalEarned);

    public static GoalStats Compute(Goal goal, IReadOnlyList<Log> allLogs, IReadOnlyList<string> weekDays)
    {
        bool isNeg = goal.IsNegative;
        var goalLogs = allLogs.Where(l => l.GoalId == goal.Id).ToList();
        int completions = 0;
        int totalSlots = 7;

        if (goal.Type == GoalType.Daily)
        {
            int tpd = goal.TimesPerDay ?? 1;
            if (tpd > 1)
            {
                completions = weekDays.Sum(d =>
                {
                    var log = goalLogs.FirstOrDefault(l => l.Date == d);
                    if (log is null) return 0;
                    bool success = isNeg
                        ? log.Slots.Any(s => s)       // at least one slot avoided
                        : log.Slots.All(s => s);      // all slots completed
                    return success ? 1 : 0;
                });
            }
            else if (isNeg)
            {
                var failedDays = goalLogs
                    .Where(l => l.Slots.Any(s => !s))
                    .Select(l => l.Date)
                    .ToHashSet();
                completions = weekDays.Count(d => !failedDays.Contains(d));
            }
            else
            {
                completions = goalLogs.Count(l => weekDays.Contains(l.Date) && l.Slots.Any(s => s));
            }
            totalSlots = 7;
        }
        else
        {
            int tpw = goal.TimesPerWeek ?? 7;
            if (isNeg)
            {
                var failedDays = goalLogs
                    .Where(l => l.Slots.Any(s => !s))
                    .Select(l => l.Date)
                    .ToHashSet();
                completions = weekDays.Count(d => !failedDays.Contains(d));
            }
            else
            {
                completions = goalLogs.Count(l => weekDays.Contains(l.Date) && l.Slots.Any(s => s));
            }
            totalSlots = tpw;
        }

        var sortedRules = goal.RewardRules.OrderBy(r => r.MinCompletions).ToList();
        int rewardCompletions = goal.Type == GoalType.WeeklyX
            ? Math.Min(completions, goal.TimesPerWeek ?? 7)
            : completions;
        double earned = sortedRules
            .Where(r => rewardCompletions >= r.MinCompletions)
            .Sum(r => r.RewardAmount);

        return new GoalStats(completions, totalSlots, earned);
    }

    /// <summary>Week summary built from the GoalWeek snapshots (historical
    /// goal config for this week) with live-goal fallback. Matches the web's
    /// pattern in <c>computeWeekSummary</c>.</summary>
    public static WeekSummary ComputeWeekSummary(
        AppSnapshot snapshot,
        string weekStart,
        string weekEnd,
        string today)
    {
        var liveById = snapshot.Goals.ToDictionary(g => g.Id);
        var activeGoals = snapshot.GoalWeeks
            .Where(gw => gw.Enabled)
            .Select(gw => EffectiveGoals.Build(gw, liveById.GetValueOrDefault(gw.GoalId)))
            .ToList();
        if (activeGoals.Count == 0) return new WeekSummary(0, 0);

        string cutoff = string.CompareOrdinal(weekEnd, today) < 0 ? weekEnd : today;
        var weekDays = GetDaysUpTo(weekStart, cutoff);

        double totalPct = 0;
        double totalEarned = 0;
        foreach (var goal in activeGoals)
        {
            var stats = Compute(goal, snapshot.Logs, weekDays);
            totalPct += Math.Min(stats.Completions, stats.TotalSlots) / (double)Math.Max(stats.TotalSlots, 1);
            totalEarned += stats.EarnedReward;
        }

        // JS Math.round rounds .5 toward +∞; C# default is banker's rounding.
        // AwayFromZero matches JS for non-negative inputs — and percent is
        // always non-negative here (totalPct ≥ 0, activeGoals.Count > 0), so
        // the three clients agree on the displayed %. The Kotlin client uses
        // `roundToInt()` (half-away-from-zero) which converges on the same
        // int for non-negative inputs as well. Triad audit 2026-05-14
        // verified equivalence — no code change needed across platforms.
        int pct = (int)Math.Round(totalPct / activeGoals.Count * 100, MidpointRounding.AwayFromZero);
        return new WeekSummary(pct, totalEarned);
    }

    public static IReadOnlyList<string> GetDaysUpTo(string weekStart, string cutoff)
    {
        var days = new List<string>();
        var current = DateOnly.Parse(weekStart);
        var end = DateOnly.Parse(cutoff);
        while (current <= end)
        {
            days.Add(current.ToString("yyyy-MM-dd"));
            current = current.AddDays(1);
        }
        return days;
    }

    public static string WeekStartFor(string date, string firstDayOfWeek)
    {
        var d = DateOnly.Parse(date);
        if (firstDayOfWeek == "monday")
        {
            int dow = ((int)d.DayOfWeek + 6) % 7; // 0=Mon..6=Sun
            return d.AddDays(-dow).ToString("yyyy-MM-dd");
        }
        int sundayOffset = (int)d.DayOfWeek;
        return d.AddDays(-sundayOffset).ToString("yyyy-MM-dd");
    }
}

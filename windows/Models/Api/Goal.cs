using System.Collections.Generic;

namespace Goals_Windows.Models.Api;

/// <summary>Mirrors backend `GoalOut`.</summary>
public sealed record Goal(
    string Id,
    string Name,
    GoalType Type,
    bool IsNegative,
    int? TimesPerWeek,
    int? TimesPerDay,
    IReadOnlyList<RewardRule> RewardRules,
    int Order,
    bool Enabled,
    int Version = 0);

/// <summary>Mirrors backend `GoalCreate`.</summary>
public sealed record GoalCreate(
    string Name,
    GoalType Type,
    bool IsNegative,
    int? TimesPerWeek,
    int? TimesPerDay,
    IReadOnlyList<RewardRule> RewardRules,
    int Order = 0);

/// <summary>Mirrors backend `GoalUpdate`. All fields optional — only the
/// ones supplied are changed. The `version` field is the optimistic-
/// concurrency token; the backend rejects mismatches.</summary>
public sealed record GoalUpdate(
    string? Name = null,
    GoalType? Type = null,
    bool? IsNegative = null,
    int? TimesPerWeek = null,
    int? TimesPerDay = null,
    IReadOnlyList<RewardRule>? RewardRules = null,
    int? Order = null,
    int? Version = null);

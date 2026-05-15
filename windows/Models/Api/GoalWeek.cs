using System.Collections.Generic;
using System.Text.Json;

namespace Goals_Windows.Models.Api;

/// <summary>Mirrors backend `GoalWeekOut`. `Snapshot` is the goal's config at
/// the week's creation time — kept as JsonElement because the shape is the
/// same as Goal minus a few fields and we rarely read individual properties
/// from the snapshot in the UI.</summary>
public sealed record GoalWeek(
    string GoalId,
    string WeekStart,
    bool Enabled,
    JsonElement Snapshot);

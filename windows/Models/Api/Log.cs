using System.Collections.Generic;

namespace Goals_Windows.Models.Api;

/// <summary>Mirrors backend `LogOut`.</summary>
public sealed record Log(string GoalId, string Date, IReadOnlyList<bool> Slots);

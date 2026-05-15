namespace Goals_Windows.Models.Api;

public sealed record LogCreate(string GoalId, string Date, int SlotIndex, bool Value);

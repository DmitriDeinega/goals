namespace Goals_Windows.Models.Api;

/// <summary>Backend returns settings as an open dict; we map the fields the
/// client actually reads. Unknown fields are ignored on deserialize.</summary>
public sealed record AppSettings(
    string? Timezone,
    string? Currency,
    string? FirstDayOfWeek,
    string? StartDate);

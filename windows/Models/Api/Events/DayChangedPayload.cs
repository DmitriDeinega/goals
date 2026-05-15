using System.Collections.Generic;

namespace Goals_Windows.Models.Api.Events;

public sealed record DayChangedPayload(
    string Date,
    IReadOnlyList<Log> Logs,
    long Seq);

using System.Collections.Generic;

namespace Goals_Windows.Models.Api.Events;

public sealed record LogChangedPayload(
    string GoalId,
    IReadOnlyList<Log> Logs,
    long Seq);

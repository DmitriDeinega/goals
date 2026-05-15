using Goals_Windows.Models.Api;
using Goals_Windows.Models.Api.Events;
using Microsoft.Extensions.Logging;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Json;
using System.Threading;
using System.Threading.Tasks;

namespace Goals_Windows.Services.Api;

/// <summary>Thrown when the server rejects a write with 409 — our X-Sequence
/// header didn't match the backend's current sequence. The caller should
/// refetch <c>/api/init</c> to resynchronize.</summary>
public sealed class SequenceConflictException : Exception
{
    public SequenceConflictException() : base("Server sequence conflict (409). Refresh required.") { }
}

/// <summary>HTTP client for the Goals backend. One singleton instance; HttpClient
/// itself is created via <see cref="HttpClient"/> default lifetime — fine for a
/// single backend, low traffic. If we ever talk to multiple hosts or need
/// rotating handlers, swap for IHttpClientFactory.</summary>
public sealed class GoalsApiClient : IDisposable
{
#if DEV
    public const string BaseUrl = "http://localhost:2200";
    public const string AppTitle = "GOALS DEV";
#elif PROD
    public const string BaseUrl = "http://63.181.3.204:2200";
    public const string AppTitle = "GOALS";
#else
#error "Build with -c DEV or -c PROD — Debug/Release are not configured."
#endif

    private readonly HttpClient _http;
    private readonly ILogger<GoalsApiClient> _logger;

    public GoalsApiClient(ILogger<GoalsApiClient> logger)
    {
        _logger = logger;
        _http = new HttpClient
        {
            BaseAddress = new Uri(BaseUrl),
            Timeout = TimeSpan.FromSeconds(15)
        };
    }

    public async Task<InitResponse> InitAsync(CancellationToken ct = default)
    {
        var response = await _http.GetFromJsonAsync<InitResponse>(
            "/api/init",
            GoalsJsonOptions.Default,
            ct);
        return response ?? throw new InvalidOperationException("Empty /api/init response");
    }

    public async Task<WeekDataResponse> WeekDataAsync(string weekStart, CancellationToken ct = default)
    {
        var response = await _http.GetFromJsonAsync<WeekDataResponse>(
            $"/api/week-data?week_start={Uri.EscapeDataString(weekStart)}",
            GoalsJsonOptions.Default,
            ct);
        return response ?? throw new InvalidOperationException("Empty /api/week-data response");
    }

    public async Task<GoalChangedPayload> CreateGoalAsync(GoalCreate body, long currentSequence, string sessionId, CancellationToken ct = default)
        => await SendChangePayloadAsync(HttpMethod.Post, "/api/goals/", body, currentSequence, sessionId, ct);

    public async Task<GoalChangedPayload> UpdateGoalAsync(string goalId, GoalUpdate body, long currentSequence, string sessionId, CancellationToken ct = default)
        => await SendChangePayloadAsync(HttpMethod.Put, $"/api/goals/{Uri.EscapeDataString(goalId)}", body, currentSequence, sessionId, ct);

    public async Task<GoalChangedPayload> DeleteGoalAsync(string goalId, long currentSequence, string sessionId, CancellationToken ct = default)
        => await SendChangePayloadAsync(HttpMethod.Delete, $"/api/goals/{Uri.EscapeDataString(goalId)}", null, currentSequence, sessionId, ct);

    public async Task<GoalChangedPayload> SetGoalEnabledAsync(string goalId, bool enabled, long currentSequence, string sessionId, CancellationToken ct = default)
        => await SendChangePayloadAsync(
            HttpMethod.Put,
            $"/api/weeks/{Uri.EscapeDataString(goalId)}/enabled",
            new { enabled },
            currentSequence,
            sessionId,
            ct);

    public async Task ReorderGoalsAsync(IReadOnlyList<(string GoalId, int NewOrder)> moves, long currentSequence, string sessionId, CancellationToken ct = default)
    {
        var body = moves.Select(m => new { goal_id = m.GoalId, new_order = m.NewOrder }).ToList();
        using var req = new HttpRequestMessage(HttpMethod.Put, "/api/goals/reorder/batch")
        {
            Content = JsonContent.Create(body, options: GoalsJsonOptions.Default)
        };
        req.Headers.TryAddWithoutValidation("X-Session-ID", sessionId);
        req.Headers.TryAddWithoutValidation("X-Sequence", currentSequence.ToString());
        using var response = await _http.SendAsync(req, ct);
        if (response.StatusCode == HttpStatusCode.Conflict) throw new SequenceConflictException();
        response.EnsureSuccessStatusCode();
    }

    private async Task<GoalChangedPayload> SendChangePayloadAsync(
        HttpMethod method,
        string path,
        object? body,
        long currentSequence,
        string sessionId,
        CancellationToken ct)
    {
        using var req = new HttpRequestMessage(method, path);
        if (body is not null)
        {
            req.Content = JsonContent.Create(body, body.GetType(), options: GoalsJsonOptions.Default);
        }
        req.Headers.TryAddWithoutValidation("X-Session-ID", sessionId);
        req.Headers.TryAddWithoutValidation("X-Sequence", currentSequence.ToString());

        using var response = await _http.SendAsync(req, ct);
        if (response.StatusCode == HttpStatusCode.Conflict) throw new SequenceConflictException();
        response.EnsureSuccessStatusCode();
        var payload = await response.Content.ReadFromJsonAsync<GoalChangedPayload>(
            GoalsJsonOptions.Default, ct);
        return payload ?? throw new InvalidOperationException($"Empty {path} response");
    }

    /// <summary>POST /api/logs/ with conflict detection. The X-Sequence header
    /// carries the client's current snapshot sequence; the backend returns 409
    /// if it has advanced past that value. The X-Session-ID header tells the
    /// backend to suppress the SSE/FCM echo to this client.</summary>
    public async Task<LogChangedPayload> ToggleAsync(
        LogCreate body,
        long currentSequence,
        string sessionId,
        CancellationToken ct = default)
    {
        using var req = new HttpRequestMessage(HttpMethod.Post, "/api/logs/")
        {
            Content = JsonContent.Create(body, options: GoalsJsonOptions.Default)
        };
        req.Headers.TryAddWithoutValidation("X-Session-ID", sessionId);
        req.Headers.TryAddWithoutValidation("X-Sequence", currentSequence.ToString());

        using var response = await _http.SendAsync(req, ct);
        if (response.StatusCode == HttpStatusCode.Conflict)
        {
            throw new SequenceConflictException();
        }
        response.EnsureSuccessStatusCode();
        var payload = await response.Content.ReadFromJsonAsync<LogChangedPayload>(
            GoalsJsonOptions.Default, ct);
        return payload ?? throw new InvalidOperationException("Empty /api/logs/ response");
    }

    public HttpClient Raw => _http;

    public void Dispose() => _http.Dispose();
}

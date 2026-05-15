using Goals_Windows.Models.Api.Events;
using Goals_Windows.Services.Api;
using Goals_Windows.Services.Session;
using Goals_Windows.Services.State;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.UI.Dispatching;
using System;
using System.Net.Http;
using System.Net.ServerSentEvents;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace Goals_Windows.Services.Sse;

/// <summary>
/// Background service that holds the SSE connection to <c>/api/events/</c>,
/// parses events, and applies them to <see cref="IGoalsState"/>. Reconnects
/// with exponential backoff on any failure. Pings are ignored. Events are
/// marshalled to the UI thread before the state mutation so subscribers can
/// touch UI directly inside their <c>Changed</c> handler.
///
/// Sequence handling matches the web's <c>useEvents</c>: every event must
/// advance <see cref="IGoalsState.Current"/>.Sequence by exactly 1. A
/// duplicate or stale event is skipped; a gap triggers a full /api/init
/// resync. Without this, two clients producing concurrent writes could leave
/// us silently behind.
/// </summary>
public sealed class GoalsSseService : BackgroundService
{
    private static readonly TimeSpan InitialBackoff = TimeSpan.FromSeconds(1);
    private static readonly TimeSpan MaxBackoff = TimeSpan.FromSeconds(30);

    private readonly GoalsApiClient _api;
    private readonly IGoalsState _state;
    private readonly SessionIdProvider _session;
    private readonly ILogger<GoalsSseService> _logger;

    /// <summary>Local sequence tracker — updated synchronously on the SSE
    /// consumer thread after we dispatch each apply. Reading
    /// <c>_state.Current.Sequence</c> for the gap check is racy because
    /// applies are queued on the UI thread; by the time the next event
    /// arrives, the store may not yet have advanced. Track it ourselves.</summary>
    private long _lastSeq;

    public GoalsSseService(
        GoalsApiClient api,
        IGoalsState state,
        SessionIdProvider session,
        ILogger<GoalsSseService> logger)
    {
        _api = api;
        _state = state;
        _session = session;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await HydrateInitialAsync(stoppingToken);

        var backoff = InitialBackoff;
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                _logger.LogInformation("Connecting SSE …");
                await ConsumeStreamAsync(stoppingToken);
                backoff = InitialBackoff;
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception ex)
            {
                // ±20% random jitter so multiple clients don't all reconnect
                // at the same instant after a server outage (thundering-herd).
                var jitterFactor = 1.0 + (Random.Shared.NextDouble() * 0.4 - 0.2);
                var jitteredMs = Math.Max(0, backoff.TotalMilliseconds * jitterFactor);
                var jittered = TimeSpan.FromMilliseconds(Math.Min(MaxBackoff.TotalMilliseconds, jitteredMs));
                _logger.LogWarning(ex, "SSE stream error; retrying in {Backoff}s", jittered.TotalSeconds);
                try { await Task.Delay(jittered, stoppingToken); } catch (OperationCanceledException) { return; }
                backoff = TimeSpan.FromTicks(Math.Min(MaxBackoff.Ticks, backoff.Ticks * 2));
            }
        }
    }

    private async Task HydrateInitialAsync(CancellationToken ct)
    {
        try
        {
            var init = await _api.InitAsync(ct);
            DispatchToUi(() => _state.ApplyInit(init));
            _lastSeq = init.Seq;
            _logger.LogInformation("Initial state hydrated. seq={Seq} goals={Count}", init.Seq, init.Goals.Count);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Initial /api/init failed; SSE will attempt anyway");
        }
    }

    private async Task ConsumeStreamAsync(CancellationToken ct)
    {
        var url = $"/api/events/?session_id={Uri.EscapeDataString(_session.Get())}";
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Accept.ParseAdd("text/event-stream");

        using var response = await _api.Raw.SendAsync(
            request,
            HttpCompletionOption.ResponseHeadersRead,
            ct);
        response.EnsureSuccessStatusCode();

        await using var stream = await response.Content.ReadAsStreamAsync(ct);
        var parser = SseParser.Create(stream);

        await foreach (var item in parser.EnumerateAsync(ct))
        {
            await HandleEventAsync(item.EventType, item.Data, ct);
        }
    }

    private async Task HandleEventAsync(string eventType, string data, CancellationToken ct)
    {
        if (eventType == EventType.Ping) return;

        long seq;
        try
        {
            seq = ExtractSeq(eventType, data);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to extract seq from event '{Type}'", eventType);
            return;
        }
        if (seq <= 0) return;

        long current = _lastSeq;
        if (seq <= current)
        {
            // Duplicate (seq == current) or stale (seq < current). Backend
            // usually suppresses our own writes via X-Session-ID, but ignore
            // here for safety.
            _logger.LogDebug("Stale or duplicate seq {Seq} <= current {Current}; skipping", seq, current);
            return;
        }
        if (current > 0 && seq > current + 1)
        {
            // Gap → another client wrote and we missed events. Refetch full
            // state to resync (matches the web's behaviour).
            _logger.LogWarning("Seq gap detected: current={Current}, got={Got}. Resyncing…", current, seq);
            await HydrateInitialAsync(ct);
            return;
        }

        ApplyEvent(eventType, data);
        // Advance our local seq pointer immediately so the next event's
        // gap check uses fresh state even though the store mutation is
        // marshalled to the UI thread asynchronously.
        _lastSeq = seq;
    }

    private void ApplyEvent(string eventType, string data)
    {
        try
        {
            switch (eventType)
            {
                case EventType.LogChanged:
                    var logChanged = JsonSerializer.Deserialize<LogChangedPayload>(data, GoalsJsonOptions.Default);
                    if (logChanged is not null) DispatchToUi(() => _state.ApplyLogChanged(logChanged));
                    break;

                case EventType.GoalChanged:
                    var trimmed = data.TrimStart();
                    if (trimmed.StartsWith('['))
                    {
                        var batch = JsonSerializer.Deserialize<GoalChangedPayload[]>(data, GoalsJsonOptions.Default);
                        if (batch is not null)
                        {
                            DispatchToUi(() =>
                            {
                                foreach (var p in batch) _state.ApplyGoalChanged(p);
                            });
                        }
                    }
                    else
                    {
                        var goalChanged = JsonSerializer.Deserialize<GoalChangedPayload>(data, GoalsJsonOptions.Default);
                        if (goalChanged is not null) DispatchToUi(() => _state.ApplyGoalChanged(goalChanged));
                    }
                    break;

                case EventType.DayChanged:
                    var dayChanged = JsonSerializer.Deserialize<DayChangedPayload>(data, GoalsJsonOptions.Default);
                    if (dayChanged is not null) DispatchToUi(() => _state.ApplyDayChanged(dayChanged));
                    break;

                default:
                    _logger.LogDebug("Unknown SSE event '{Type}' ignored", eventType);
                    break;
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to apply SSE event '{Type}'", eventType);
        }
    }

    /// <summary>Pull the seq out of an event's JSON payload without
    /// deserializing the full type. For the bulk-reorder array we use the
    /// FIRST element's seq for the gap check — each element advances the
    /// sequence by one, so the first must equal `current + 1`. Returning
    /// the max would falsely flag a gap.</summary>
    private static long ExtractSeq(string eventType, string data)
    {
        using var doc = JsonDocument.Parse(data);
        if (doc.RootElement.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in doc.RootElement.EnumerateArray())
            {
                if (item.TryGetProperty("seq", out var s) && s.ValueKind == JsonValueKind.Number)
                {
                    return s.GetInt64();
                }
            }
            return 0;
        }
        if (doc.RootElement.ValueKind == JsonValueKind.Object &&
            doc.RootElement.TryGetProperty("seq", out var sp) &&
            sp.ValueKind == JsonValueKind.Number)
        {
            return sp.GetInt64();
        }
        return 0;
    }

    private static void DispatchToUi(Action action)
    {
        var dispatcher = App.DispatcherQueue;
        if (dispatcher is null || dispatcher.HasThreadAccess)
        {
            action();
        }
        else
        {
            dispatcher.TryEnqueue(DispatcherQueuePriority.Normal, () => action());
        }
    }
}

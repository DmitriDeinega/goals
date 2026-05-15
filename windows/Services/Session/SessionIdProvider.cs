using Microsoft.Extensions.Logging;
using System;
using System.IO;

namespace Goals_Windows.Services.Session;

/// <summary>Provides a stable per-installation session id used in the
/// <c>X-Session-ID</c> header on writes and as the <c>session_id</c> query
/// param on the SSE stream. Persisted as a plain text file in LocalAppData so
/// it survives restarts and matches across the main app and the widget
/// (single process today, but file-based makes it future-proof if we split).</summary>
public sealed class SessionIdProvider
{
    private readonly ILogger<SessionIdProvider> _logger;
    private readonly string _path;
    private string? _id;

    public SessionIdProvider(ILogger<SessionIdProvider> logger)
    {
        _logger = logger;
        _path = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Goals",
            "session.id");
    }

    public string Get()
    {
        if (_id is not null) return _id;
        try
        {
            if (File.Exists(_path))
            {
                var existing = File.ReadAllText(_path).Trim();
                if (Guid.TryParse(existing, out _))
                {
                    _id = existing;
                    return _id;
                }
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to read session id; regenerating");
        }
        _id = Guid.NewGuid().ToString();
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            File.WriteAllText(_path, _id);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to persist session id");
        }
        return _id;
    }
}

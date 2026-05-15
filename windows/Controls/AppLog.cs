using System;
using System.IO;

namespace Goals_Windows.Controls;

/// <summary>Append-only file logger so we get diagnostic output even when not
/// running under the VS debugger. Trace.WriteLine works under F5; this also
/// works under `dev.ps1 cycle`. File: %TEMP%\goals-windows.log.</summary>
public static class AppLog
{
    private static readonly string LogPath = Path.Combine(Path.GetTempPath(), "goals-windows.log");
    private static readonly object _lock = new();

    public static void Write(string line)
    {
        var stamped = $"{DateTime.Now:HH:mm:ss.fff} {line}";
        System.Diagnostics.Trace.WriteLine(stamped);
        try
        {
            lock (_lock) File.AppendAllText(LogPath, stamped + Environment.NewLine);
        }
        catch { }
    }
}

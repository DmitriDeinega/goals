using Goals_Windows.Controls;
using Goals_Windows.Services.Api;
using Goals_Windows.Views;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using System;
using System.IO;
using System.Runtime.InteropServices;

namespace Goals_Windows;

internal static class TrayLog
{
    private static readonly string _path = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Goals", "logs", "tray.log");
    static TrayLog() { try { Directory.CreateDirectory(Path.GetDirectoryName(_path)!); } catch { } }
    public static void W(string msg)
    {
        try { File.AppendAllText(_path, $"{DateTime.Now:HH:mm:ss.fff} [{Environment.ProcessId}] {msg}{Environment.NewLine}"); }
        catch { }
    }
}

/// <summary>
/// Shell window. Hosts the title bar, the two-tab strip, and a Frame that
/// navigates between TodayPage and GoalsPage.
/// </summary>
public sealed partial class MainWindow : Window
{
    /// <summary>Bound to the tray menu's Quit item via x:Bind. Has to be an
    /// ICommand (not a routed Click) because H.NotifyIcon hosts the flyout
    /// in its own XamlRoot — Click events never propagate back to us.</summary>
    public System.Windows.Input.ICommand QuitCommand { get; }

    public MainWindow()
    {
        QuitCommand = new TrayCommand(QuitNow);

        InitializeComponent();

        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);

        TrayLog.W("ctor: wiring tray");
        // Left-click on the tray icon restores from collapse + brings the
        // window all the way to the foreground. Activate() alone often
        // leaves the window visible but behind whatever app currently has
        // focus — Windows enforces this anti-stealing behaviour. The
        // SetForegroundWindow Win32 call (paired with AllowSetForeground-
        // Window for the calling process) forces it forward.
        TrayIcon.LeftClickCommand = new TrayCommand(() =>
        {
            TrayLog.W("LeftClick: show + restore + foreground");
            AppWindow.Show();
            if (_collapsed) RestoreFromCollapse();
            this.Activate();
            var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
            ShowWindow(hwnd, SW_RESTORE);
            SetForegroundWindow(hwnd);
        });
        TrayIcon.RightClickCommand = new TrayCommand(() => TrayLog.W("RightClick fired"));
        TrayIcon.NoLeftClickDelay = true;

        // Title flips between "GOALS DEV" (Debug) and "GOALS" (Release) so the
        // user can tell which backend they're hitting at a glance — mirrors
        // Android's productFlavors-style separation.
        Title = GoalsApiClient.AppTitle;
        AppTitleBar.Title = GoalsApiClient.AppTitle;

        // Build an absolute path so packaged-app activation resolves the
        // icon reliably — relative paths used to work in the unpackaged
        // DEV flavor but the new packaged DEV launches from inside the
        // AppX install folder, and `AppWindow.SetIcon` silently no-ops when
        // it can't resolve the file (no exception thrown).
        var iconPath = System.IO.Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico");
        if (System.IO.File.Exists(iconPath)) AppWindow.SetIcon(iconPath);
        // 900×1100 leaves breathing room around the goal-edit dialog so it
        // doesn't fill the window edge-to-edge — matches the web's overlay
        // pattern where the sheet sits inside the viewport.
        AppWindow.Resize(new Windows.Graphics.SizeInt32(900, 1100));

        // Taskbar visibility is state-driven: full window → show in taskbar
        // like a normal app. Collapsed-to-icon → hidden so the desktop
        // widget doesn't claim a taskbar slot. Toggled inside the
        // collapse/restore paths.
        AppWindow.IsShownInSwitchers = true;

        // Hide the OS title bar (no system X) and drag the window manually
        // via pointer events on AppTitleBar — gives the same instant-drag
        // feel as the native title bar without the WM_NCLBUTTONDOWN
        // click-detect lag or non-client-area cursor swap.
        if (AppWindow.Presenter is OverlappedPresenter mp)
        {
            mp.IsMinimizable = false;
            mp.IsMaximizable = false;
            mp.SetBorderAndTitleBar(hasBorder: true, hasTitleBar: false);
        }

        // Recolor the system caption buttons (X) to match the accent so it
        // pairs with our custom ═ button next to it. Hover state stays
        // lime too; pressed darkens slightly. Background stays transparent
        // so the title bar color shows through.
        var accent = Windows.UI.Color.FromArgb(0xFF, 0xC8, 0xF1, 0x35);
        AppWindow.TitleBar.ButtonForegroundColor             = accent;
        AppWindow.TitleBar.ButtonHoverForegroundColor        = accent;
        AppWindow.TitleBar.ButtonPressedForegroundColor      = accent;
        AppWindow.TitleBar.ButtonInactiveForegroundColor     = accent;
        AppWindow.TitleBar.ButtonBackgroundColor             = Microsoft.UI.Colors.Transparent;
        AppWindow.TitleBar.ButtonHoverBackgroundColor        = Windows.UI.Color.FromArgb(0xFF, 0x2A, 0x2A, 0x2A);
        AppWindow.TitleBar.ButtonPressedBackgroundColor      = Windows.UI.Color.FromArgb(0xFF, 0x3A, 0x3A, 0x3A);
        AppWindow.TitleBar.ButtonInactiveBackgroundColor     = Microsoft.UI.Colors.Transparent;

        // Subclass the window proc to swallow the non-client right-click,
        // which is what would otherwise pop the system menu (Restore/Move/
        // Close) on the title bar. We have our own caption layout and
        // dedicated tray menu — the system one is just noise.
        InstallNcRightClickSwallow();

        // Defer initial navigation/tab paint to Activated — running it in
        // the constructor can paint before the visual tree is attached, so
        // the brushes set on the TabIndicators don't always land.
        Activated += OnFirstActivated;
    }

    private void OnCollapsedOverlayPointerEntered(object sender, Microsoft.UI.Xaml.Input.PointerRoutedEventArgs e)
    {
        // PointerEntered is more reliable than Loaded for the collapsed
        // overlay because the element starts Visibility="Collapsed" — its
        // child Image isn't realized in the visual tree until first show,
        // so a Loaded-based SetHandRecursive misses it and the Image's
        // default Arrow cursor wins on hover. Setting on enter applies
        // each time the pointer crosses in.
        if (sender is UIElement el) CursorHelper.SetHandRecursive(el);
    }

    private void OnCollapsedMinimizeClick(object sender, RoutedEventArgs e) => AppWindow.Hide();

    /// <summary>Hide-to-tray. The window vanishes; tray icon + process
    /// stay alive so the user can summon it back via left-click on tray.
    /// Used to be wired to the X — moved to a dedicated ─ button so X can
    /// be a true close (real quit).</summary>
    private void OnMinimizeClick(object sender, RoutedEventArgs e) => AppWindow.Hide();

    // ── Custom window drag ───────────────────────────────────────────────
    //
    // With the OS title bar disabled (SetBorderAndTitleBar(true, false))
    // there's no native drag handler. The classic WM_NCLBUTTONDOWN +
    // HTCAPTION trick worked but introduced visible lag (Windows enters
    // a click-vs-drag detection loop) and swapped the cursor to the
    // non-client arrow. Pointer-events + AppWindow.Move gives instant
    // 1:1 drag with the normal cursor.

    private bool _draggingWindow;
    private POINT _dragStartCursor;
    private Windows.Graphics.PointInt32 _dragStartWindowPos;

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT { public int X; public int Y; }

    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out POINT lpPoint);

    private void OnTitleBarPointerPressed(object sender, Microsoft.UI.Xaml.Input.PointerRoutedEventArgs e)
    {
        var p = e.GetCurrentPoint(AppTitleBar);
        if (!p.Properties.IsLeftButtonPressed) return;
        if (sender is not Microsoft.UI.Xaml.UIElement target) return;
        if (!target.CapturePointer(e.Pointer)) return;
        GetCursorPos(out _dragStartCursor);
        _dragStartWindowPos = AppWindow.Position;
        _draggingWindow = true;
        e.Handled = true;
    }

    private void OnTitleBarPointerMoved(object sender, Microsoft.UI.Xaml.Input.PointerRoutedEventArgs e)
    {
        if (!_draggingWindow) return;
        GetCursorPos(out var now);
        int dx = now.X - _dragStartCursor.X;
        int dy = now.Y - _dragStartCursor.Y;
        if (dx == 0 && dy == 0) return;
        AppWindow.Move(new Windows.Graphics.PointInt32(
            _dragStartWindowPos.X + dx, _dragStartWindowPos.Y + dy));
    }

    private void OnTitleBarPointerReleased(object sender, Microsoft.UI.Xaml.Input.PointerRoutedEventArgs e)
    {
        if (!_draggingWindow) return;
        _draggingWindow = false;
        if (sender is Microsoft.UI.Xaml.UIElement target)
            target.ReleasePointerCapture(e.Pointer);
    }


    private void QuitNow()
    {
        // Real exit — invoked from the tray Quit menu and the collapsed
        // icon's Close menu. Dispose the tray so the icon vanishes
        // immediately, then ask the App host to exit (which tears down
        // background services and ends the process).
        TrayLog.W("QuitNow: dispose tray + Exit");
        try { TrayIcon.Dispose(); } catch { }
        ((App)Application.Current).Exit();
    }

    private sealed class TrayCommand : System.Windows.Input.ICommand
    {
        private readonly Action _exec;
        public TrayCommand(Action exec) { _exec = exec; }
        public event EventHandler? CanExecuteChanged;
        public bool CanExecute(object? p) => true;
        public void Execute(object? p) => _exec();
    }


    // ── Collapse-to-icon ──────────────────────────────────────────────────

    private bool _collapsed;
    private Windows.Graphics.SizeInt32 _normalSize = new(900, 1100);
    // Pre-collapse presenter flags so a restore puts them back exactly.
    private bool _wasResizable = true;
    private bool _wasAlwaysOnTop;
    private bool _wasMaximizable = true;
    private bool _wasMinimizable = true;

    // Windows enforces a minimum width on OverlappedPresenter windows (the
    // exact value varies with DPI / Win build). 150 wasn't enough on this
    // machine — width was clamped wider than tall. 200 covers it and both
    // axes settle at the requested value → true square.
    private const int CollapsedSize = 200;
    private const int AnimDurationMs = 180;
    private Microsoft.UI.Dispatching.DispatcherQueueTimer? _animTimer;
    private DateTime _animStart;
    private Windows.Graphics.RectInt32 _animFrom;
    private Windows.Graphics.RectInt32 _animTo;
    private Action? _animOnComplete;

    private void OnCollapseClick(object sender, RoutedEventArgs e)
    {
        if (_collapsed) RestoreFromCollapse(); else CollapseToIcon();
    }

    private void OnRestoreClick(object sender, RoutedEventArgs e) => RestoreFromCollapse();

    public void CollapseToIcon()
    {
        if (_collapsed) return;
        _collapsed = true;

        _normalSize = AppWindow.Size;

        if (AppWindow.Presenter is OverlappedPresenter op)
        {
            _wasResizable    = op.IsResizable;
            _wasAlwaysOnTop  = op.IsAlwaysOnTop;
            _wasMaximizable  = op.IsMaximizable;
            _wasMinimizable  = op.IsMinimizable;
            op.SetBorderAndTitleBar(false, false);
            op.IsResizable    = false;
            op.IsMaximizable  = false;
            op.IsMinimizable  = false;
            // Collapsed icon sits behind other windows — desktop-widget feel.
            // Restore path raises it back to normal Z-order automatically
            // because MoveAndResize implicitly brings the window forward.
            op.IsAlwaysOnTop  = false;
        }
        // Collapsed = desktop widget, not a taskbar app. Hide it from
        // taskbar + Alt-Tab while collapsed; restore puts it back.
        AppWindow.IsShownInSwitchers = false;

        // Swap visuals up front; the size animation makes the icon "shrink
        // out" of the previous content. Shrinking origin is the current
        // top-left so the icon ends up where the window was.
        NormalLayout.Visibility     = Visibility.Collapsed;
        CollapsedOverlay.Visibility = Visibility.Visible;
        // Apply Hand cursor after the overlay's child Image is realized.
        // Doing it at construction misses the child (Collapsed elements
        // don't realize their visual tree); deferring to the next
        // dispatcher tick guarantees the layout pass has run.
        DispatcherQueue.TryEnqueue(() => CursorHelper.SetHandRecursive(CollapsedOverlay));
        // Re-declare the drag region — with no chrome the system has no
        // title bar to drag, so the icon itself becomes the drag handle.
        // The Button's Click still fires on a no-drag press because WinUI
        // only initiates drag once the pointer crosses a small threshold.
        SetTitleBar(CollapsedOverlay);

        var pos = AppWindow.Position;
        var size = AppWindow.Size;
        var from = new Windows.Graphics.RectInt32(pos.X, pos.Y, size.Width, size.Height);
        var to   = new Windows.Graphics.RectInt32(pos.X, pos.Y, CollapsedSize, CollapsedSize);
        // After the shrink animation finishes, push to HWND_BOTTOM so the
        // icon settles behind every other window. From there it stays at
        // the bottom unless explicitly raised (tray left-click → restore).
        AnimateWindow(from, to, onComplete: SendIconToBack);
    }

    private void SendIconToBack()
    {
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
        // SWP_NOMOVE|NOSIZE: rect already set by the animation; just change
        // Z-order. SWP_NOACTIVATE: don't steal focus from whatever was
        // active when we collapsed.
        SetWindowPos(hwnd, HWND_BOTTOM, 0, 0, 0, 0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
    }

    private static readonly IntPtr HWND_BOTTOM = new(1);
    private const uint SWP_NOSIZE = 0x0001;
    private const uint SWP_NOMOVE = 0x0002;
    private const uint SWP_NOACTIVATE = 0x0010;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetWindowPos(
        IntPtr hWnd, IntPtr hWndInsertAfter,
        int X, int Y, int cx, int cy, uint uFlags);

    // ── Right-click on title bar = system menu, which we don't want ───────
    //
    // The default title-bar right-click pops the system menu (Restore /
    // Move / Size / Minimize / Maximize / Close). We've already stripped
    // min/max and the user has a custom tray menu for Quit — the system
    // popup adds nothing but visual noise. Subclassing the window proc
    // and swallowing WM_NCRBUTTONUP for the caption hit-test area is the
    // cleanest path; WinUI 3 doesn't expose a managed hook for this.

    private const int GWLP_WNDPROC = -4;
    private const uint WM_NCRBUTTONUP = 0x00A5;
    private const uint WM_NCRBUTTONDOWN = 0x00A4;
    private const int HTCAPTION = 2;
    private const int SW_RESTORE = 9;

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW", SetLastError = true)]
    private static extern IntPtr GetWindowLongPtr(IntPtr hWnd, int nIndex);
    [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW", SetLastError = true)]
    private static extern IntPtr SetWindowLongPtr(IntPtr hWnd, int nIndex, IntPtr dwNewLong);
    [DllImport("user32.dll")]
    private static extern IntPtr CallWindowProc(IntPtr lpPrevWndFunc, IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

    private delegate IntPtr WndProcDelegate(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);
    private WndProcDelegate? _wndProc;
    private IntPtr _prevWndProc;

    private void InstallNcRightClickSwallow()
    {
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
        _wndProc = NcRightClickFilter;
        _prevWndProc = SetWindowLongPtr(hwnd, GWLP_WNDPROC,
            Marshal.GetFunctionPointerForDelegate(_wndProc));
    }

    private IntPtr NcRightClickFilter(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        if ((msg == WM_NCRBUTTONUP || msg == WM_NCRBUTTONDOWN)
            && wParam.ToInt32() == HTCAPTION)
        {
            // While collapsed, the overlay IS the title bar (SetTitleBar)
            // so right-click lands here before XAML can dispatch it as a
            // ContextFlyout — manually open our flyout on UP.
            if (_collapsed && msg == WM_NCRBUTTONUP && CollapsedOverlay.ContextFlyout is not null)
            {
                DispatcherQueue.TryEnqueue(() =>
                {
                    try { CollapsedOverlay.ContextFlyout.ShowAt(CollapsedOverlay); }
                    catch { /* timing edge — flyout host not ready */ }
                });
            }
            // Always swallow — no system menu on either state.
            return IntPtr.Zero;
        }
        return CallWindowProc(_prevWndProc, hWnd, msg, wParam, lParam);
    }


    public void RestoreFromCollapse()
    {
        if (!_collapsed) return;
        _collapsed = false;

        // Anchor the expansion to the icon's current top-left, then clamp
        // so the expanded window stays on-screen. If expanding right/down
        // would overflow, pull the top-left back along that axis. This
        // matches user intuition: the icon "grows" from where it sits.
        var iconRect = new Windows.Graphics.RectInt32(
            AppWindow.Position.X, AppWindow.Position.Y, CollapsedSize, CollapsedSize);
        var display = DisplayArea.GetFromPoint(
            new Windows.Graphics.PointInt32(iconRect.X + iconRect.Width / 2,
                                            iconRect.Y + iconRect.Height / 2),
            DisplayAreaFallback.Nearest);
        var work = display.WorkArea;
        int newW = Math.Min(_normalSize.Width, work.Width);
        int newH = Math.Min(_normalSize.Height, work.Height);
        int newX = iconRect.X;
        int newY = iconRect.Y;
        if (newX + newW > work.X + work.Width)  newX = work.X + work.Width  - newW;
        if (newY + newH > work.Y + work.Height) newY = work.Y + work.Height - newH;
        if (newX < work.X) newX = work.X;
        if (newY < work.Y) newY = work.Y;

        var from = iconRect;
        var to   = new Windows.Graphics.RectInt32(newX, newY, newW, newH);

        // Restore visuals BEFORE animating so the chrome paints in sync
        // with the size growth, not as a hop after. hasTitleBar stays
        // false — we use a custom title bar (no OS caption buttons).
        if (AppWindow.Presenter is OverlappedPresenter op)
        {
            op.SetBorderAndTitleBar(hasBorder: true, hasTitleBar: false);
            op.IsResizable    = _wasResizable;
            op.IsMaximizable  = _wasMaximizable;
            op.IsMinimizable  = _wasMinimizable;
            op.IsAlwaysOnTop  = _wasAlwaysOnTop;
        }
        NormalLayout.Visibility     = Visibility.Visible;
        CollapsedOverlay.Visibility = Visibility.Collapsed;
        // Hand the drag region back to the normal title bar.
        SetTitleBar(AppTitleBar);
        // Back to a normal app → reclaim the taskbar slot.
        AppWindow.IsShownInSwitchers = true;

        AnimateWindow(from, to, onComplete: null);
    }

    /// <summary>Smoothly animate the window's rect from one size+position to
    /// another using DispatcherQueueTimer (~16ms ticks) and ease-out-cubic.
    /// WinUI 3 doesn't expose Composition animations for the window frame
    /// itself, so we drive AppWindow.MoveAndResize per tick.</summary>
    private void AnimateWindow(Windows.Graphics.RectInt32 from, Windows.Graphics.RectInt32 to, Action? onComplete)
    {
        _animFrom = from;
        _animTo = to;
        _animOnComplete = onComplete;
        _animStart = DateTime.UtcNow;

        if (_animTimer is null)
        {
            _animTimer = DispatcherQueue.CreateTimer();
            _animTimer.Interval = TimeSpan.FromMilliseconds(16);
            _animTimer.Tick += OnAnimTick;
        }
        if (!_animTimer.IsRunning) _animTimer.Start();
    }

    private void OnAnimTick(Microsoft.UI.Dispatching.DispatcherQueueTimer sender, object args)
    {
        var elapsed = (DateTime.UtcNow - _animStart).TotalMilliseconds;
        double t = Math.Min(1.0, elapsed / AnimDurationMs);
        // ease-out cubic — quick start, soft landing
        double k = 1 - Math.Pow(1 - t, 3);

        int x = (int)(_animFrom.X + (_animTo.X - _animFrom.X) * k);
        int y = (int)(_animFrom.Y + (_animTo.Y - _animFrom.Y) * k);
        int w = (int)(_animFrom.Width  + (_animTo.Width  - _animFrom.Width)  * k);
        int h = (int)(_animFrom.Height + (_animTo.Height - _animFrom.Height) * k);
        AppWindow.MoveAndResize(new Windows.Graphics.RectInt32(x, y, w, h));

        if (t >= 1.0)
        {
            sender.Stop();
            AppWindow.MoveAndResize(_animTo);  // snap to exact target
            _animOnComplete?.Invoke();
            _animOnComplete = null;
        }
    }

    private bool _firstActivated;
    private void OnFirstActivated(object sender, WindowActivatedEventArgs e)
    {
        if (_firstActivated) return;
        _firstActivated = true;
        Activated -= OnFirstActivated;
        RootFrame.Navigate(typeof(TodayPage));
        SelectTab("today");
    }

    private void OnTabTodayClick(object sender, RoutedEventArgs e) => SelectTab("today");
    private void OnTabGoalsClick(object sender, RoutedEventArgs e) => SelectTab("goals");

    private bool _todayActive = true;

    private void SelectTab(string tag)
    {
        var (target, todayActive) = tag switch
        {
            "goals" => (typeof(GoalsPage), false),
            _ => (typeof(TodayPage), true),
        };

        if (RootFrame.CurrentSourcePageType != target)
        {
            RootFrame.Navigate(target);
        }

        _todayActive = todayActive;
        ApplyTabBrushes(hoverTab: null);
    }

    /// <summary>Active tab → white text + lime underline. Hovered (non-active)
    /// tab → white text, no underline. Idle non-active tab → grey text.
    /// Matches the web's `.tab:hover` ⇒ `color: var(--text)` rule.</summary>
    private void ApplyTabBrushes(Button? hoverTab)
    {
        var accent = (Brush)Application.Current.Resources["GoalsAccentBrush"];
        var text   = (Brush)Application.Current.Resources["GoalsTextBrush"];
        var dim    = (Brush)Application.Current.Resources["GoalsText2Brush"];
        var clear  = new SolidColorBrush(Microsoft.UI.Colors.Transparent);

        TabToday.Foreground          = _todayActive || hoverTab == TabToday ? text   : dim;
        TabGoals.Foreground          = !_todayActive || hoverTab == TabGoals ? text  : dim;
        TabTodayIndicator.Background = _todayActive ? accent : clear;
        TabGoalsIndicator.Background = _todayActive ? clear  : accent;
    }

    private void OnTabLoaded(object sender, RoutedEventArgs e)
    {
        if (sender is Button b) CursorHelper.SetHand(b);
    }

    private void OnTabPointerEntered(object sender, Microsoft.UI.Xaml.Input.PointerRoutedEventArgs e)
        => ApplyTabBrushes(sender as Button);

    private void OnTabPointerExited(object sender, Microsoft.UI.Xaml.Input.PointerRoutedEventArgs e)
        => ApplyTabBrushes(null);
}

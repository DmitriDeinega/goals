using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using System.Reflection;

namespace Goals_Windows.Controls;

/// <summary>Sets the system Hand cursor on a UIElement that doesn't expose
/// ProtectedCursor publicly (Border is sealed; most layout panels don't
/// surface it). Reflection is unfortunate but the alternatives are either
/// a custom ControlTemplate or wrapping every clickable element in a
/// dedicated subclass — both heavier.</summary>
public static class CursorHelper
{
    private static readonly PropertyInfo? _protectedCursor = typeof(UIElement)
        .GetProperty("ProtectedCursor", BindingFlags.Instance | BindingFlags.NonPublic);

    public static void SetHand(UIElement element)
    {
        if (_protectedCursor is null) return;
        try
        {
            var cursor = InputSystemCursor.Create(InputSystemCursorShape.Hand);
            _protectedCursor.SetValue(element, cursor);
        }
        catch (System.Exception ex)
        {
            System.Diagnostics.Trace.WriteLine($"[CursorHelper] SetHand failed: {ex.Message}");
        }
    }

    /// <summary>Apply Hand to <paramref name="root"/> and every descendant.
    /// Needed because cursor lookup walks the topmost-hit element; if a
    /// child UIElement doesn't have ProtectedCursor set, it defaults to
    /// the system Arrow and overrides the parent's Hand.</summary>
    public static void SetHandRecursive(UIElement root)
    {
        SetHand(root);
        if (root is not DependencyObject dep) return;
        int n = Microsoft.UI.Xaml.Media.VisualTreeHelper.GetChildrenCount(dep);
        for (int i = 0; i < n; i++)
        {
            if (Microsoft.UI.Xaml.Media.VisualTreeHelper.GetChild(dep, i) is UIElement child)
                SetHandRecursive(child);
        }
    }
}

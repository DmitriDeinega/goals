using Microsoft.UI.Input;
using Microsoft.UI.Xaml.Controls;

namespace Goals_Windows.Controls;

/// <summary>Button subclass that paints the system Hand cursor on hover.
/// <see cref="Microsoft.UI.Xaml.UIElement.ProtectedCursor"/> is protected, so
/// it can't be set inline in XAML — only from a derived class's constructor.</summary>
public sealed class HandButton : Button
{
    public HandButton()
    {
        ProtectedCursor = InputSystemCursor.Create(InputSystemCursorShape.Hand);
    }
}

using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Goals_Windows.Controls;

/// <summary>NumberBox subclass that swaps the inner TextBox (template part
/// "InputBox") to use `NoClearButtonTextBoxStyle` — kills the X clear button
/// + the hover-background VSM. The default NumberBox hosts a TextBox styled
/// by `NumberBoxTextBoxStyle`, which has the same animation-precedence
/// `ButtonStates` storyboard that resists every code-behind workaround.</summary>
public sealed class CleanNumberBox : NumberBox
{
    protected override void OnApplyTemplate()
    {
        base.OnApplyTemplate();
        if (GetTemplateChild("InputBox") is TextBox tb &&
            Application.Current.Resources["NoClearButtonTextBoxStyle"] is Style clean)
        {
            tb.Style = clean;
        }
    }
}

using Avalonia.Controls;
using Avalonia.Input;

namespace PulseSend.Windows.Views;

public partial class PairDialog : Window
{
    public PairDialog()
    {
        InitializeComponent();
        CodeBox.TextChanging += OnTextChanging;
        OkButton.Click += (_, _) => Close(CodeBox.Text?.Trim());
        CancelButton.Click += (_, _) => Close(null);
    }

    private void OnTextChanging(object? sender, TextChangingEventArgs e)
    {
        if (CodeBox.Text is not { Length: > 0 } text)
        {
            return;
        }
        var filtered = new string(text.Where(char.IsDigit).ToArray());
        if (filtered.Length > 6)
        {
            filtered = filtered.Substring(0, 6);
        }
        if (filtered != text)
        {
            CodeBox.Text = filtered;
        }
    }
}







using Avalonia.Controls;
using Avalonia.Interactivity;

namespace PulseSend.Windows.Views;

public partial class FullTextDialog : Window
{
    public FullTextDialog()
    {
        InitializeComponent();
        CloseButton.Click += OnCloseClicked;
    }

    public string Message
    {
        get => FullTextBlock.Text ?? string.Empty;
        set => FullTextBlock.Text = value;
    }

    private void OnCloseClicked(object? sender, RoutedEventArgs e)
    {
        Close();
    }
}

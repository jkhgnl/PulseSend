using Avalonia.Controls;
using Avalonia.Interactivity;

namespace PulseSend.Windows.Views;

public partial class TransferErrorDialog : Window
{
    public TransferErrorDialog()
    {
        InitializeComponent();
        OkButton.Click += OnOkClicked;
    }

    public string Message
    {
        get => MessageText.Text;
        set => MessageText.Text = value;
    }

    private void OnOkClicked(object? sender, RoutedEventArgs e)
    {
        Close();
    }
}

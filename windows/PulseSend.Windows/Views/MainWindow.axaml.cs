using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using PulseSend.Windows.ViewModels;
using PulseSend.Windows.Views;
using System.Threading.Tasks;

namespace PulseSend.Windows.Views;

public partial class MainWindow : Window
{
    private readonly MainViewModel _viewModel;

    public MainWindow()
    {
        InitializeComponent();
        _viewModel = new MainViewModel();
        DataContext = _viewModel;

        _viewModel.PairCodePrompt = PromptPairCodeAsync;
        _viewModel.FilePicker = PickFileAsync;
        _viewModel.AlertMessageRequested = message =>
        {
            _ = Dispatcher.UIThread.InvokeAsync(() => ShowTransferErrorAsync(message));
        };

        Closing += OnClosing;
    }

    public bool AllowClose { get; set; }

    private void OnClosing(object? sender, WindowClosingEventArgs e)
    {
        if (AllowClose)
        {
            return;
        }
        e.Cancel = true;
        Hide();
    }

    private async Task<string?> PromptPairCodeAsync()
    {
        var dialog = new PairDialog();
        return await dialog.ShowDialog<string?>(this);
    }

    private async Task<string?> PickFileAsync()
    {
        var provider = StorageProvider;
        if (provider == null)
        {
            return null;
        }
        var options = new FilePickerOpenOptions
        {
            Title = "选择文件",
            AllowMultiple = false
        };
        var files = await provider.OpenFilePickerAsync(options);
        var file = files.FirstOrDefault();
        return file?.TryGetLocalPath();
    }

    private Task ShowTransferErrorAsync(string message)
    {
        if (string.IsNullOrWhiteSpace(message))
        {
            return Task.CompletedTask;
        }
        var dialog = new TransferErrorDialog
        {
            Message = message
        };
        return dialog.ShowDialog(this);
    }
}

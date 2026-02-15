using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using PulseSend.Windows.ViewModels;
using System.Collections.Generic;
using PulseSend.Windows.Views;
using System.Linq;
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
        _viewModel.FolderPicker = PickFolderAsync;
        _viewModel.CopyTextRequested = CopyTextAsync;
        _viewModel.FullTextRequested = ShowFullTextAsync;
        _viewModel.AlertMessageRequested = message =>
        {
            _ = Dispatcher.UIThread.InvokeAsync(() => ShowTransferErrorAsync(message));
        };

        AddHandler(DragDrop.DragOverEvent, OnDragOver);
        AddHandler(DragDrop.DropEvent, OnDrop);
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

    private Task ShowFullTextAsync(string message)
    {
        if (string.IsNullOrWhiteSpace(message))
        {
            return Task.CompletedTask;
        }
        var dialog = new FullTextDialog
        {
            Message = message
        };
        return dialog.ShowDialog(this);
    }

    private async Task<string?> PickFolderAsync()
    {
        var provider = StorageProvider;
        if (provider == null)
        {
            return null;
        }
        var folders = await provider.OpenFolderPickerAsync(new FolderPickerOpenOptions
        {
            Title = "选择接收文件保存目录",
            AllowMultiple = false
        });
        var folder = folders.FirstOrDefault();
        return folder?.TryGetLocalPath();
    }

    private async Task CopyTextAsync(string text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return;
        }
        var clipboard = Clipboard;
        if (clipboard != null)
        {
            await clipboard.SetTextAsync(text);
        }
    }

    private void OnDragOver(object? sender, DragEventArgs e)
    {
        var files = e.Data.GetFiles();
        e.DragEffects = files is null ? DragDropEffects.None : DragDropEffects.Copy;
        e.Handled = true;
    }

    private void OnDrop(object? sender, DragEventArgs e)
    {
        var files = e.Data.GetFiles();
        if (files is null)
        {
            return;
        }
        var paths = new List<string>();
        foreach (var file in files.OfType<IStorageFile>())
        {
            var path = file.TryGetLocalPath();
            if (!string.IsNullOrWhiteSpace(path))
            {
                paths.Add(path);
            }
        }
        if (paths.Count > 0)
        {
            _viewModel.EnqueueFiles(paths);
        }
        e.Handled = true;
    }
}

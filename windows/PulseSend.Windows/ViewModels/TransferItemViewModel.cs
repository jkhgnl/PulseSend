using PulseSend.Core.Models;

namespace PulseSend.Windows.ViewModels;

public sealed class TransferItemViewModel : ViewModelBase
{
    private string _transferId = "";
    private string _fileName = "";
    private string _statusText = "";
    private string? _savedPath;
    private long _totalBytes;
    private long _receivedBytes;
    private TransferDirection _direction;
    private DateTime _updatedAt = DateTime.Now;
    public Action<string>? OpenRequested { get; set; }
    public Action<TransferItemViewModel>? DeleteRequested { get; set; }

    public RelayCommand OpenCommand { get; }
    public RelayCommand DeleteCommand { get; }

    public TransferItemViewModel()
    {
        OpenCommand = new RelayCommand(Open, () => CanOpen);
        DeleteCommand = new RelayCommand(Delete, () => CanDelete);
    }

    public string TransferId
    {
        get => _transferId;
        set => SetField(ref _transferId, value);
    }

    public string FileName
    {
        get => _fileName;
        set => SetField(ref _fileName, value);
    }

    public string StatusText
    {
        get => _statusText;
        set
        {
            if (SetField(ref _statusText, value))
            {
                RaisePropertyChanged(nameof(CanOpen));
                OpenCommand.NotifyCanExecuteChanged();
            }
        }
    }

    public string? SavedPath
    {
        get => _savedPath;
        set
        {
            if (SetField(ref _savedPath, value))
            {
                RaisePropertyChanged(nameof(CanOpen));
                RaisePropertyChanged(nameof(CanDelete));
                OpenCommand.NotifyCanExecuteChanged();
                DeleteCommand.NotifyCanExecuteChanged();
            }
        }
    }

    public long TotalBytes
    {
        get => _totalBytes;
        set
        {
            if (SetField(ref _totalBytes, value))
            {
                RaisePropertyChanged(nameof(Progress));
                RaisePropertyChanged(nameof(SizeText));
            }
        }
    }

    public long ReceivedBytes
    {
        get => _receivedBytes;
        set
        {
            if (SetField(ref _receivedBytes, value))
            {
                RaisePropertyChanged(nameof(Progress));
                RaisePropertyChanged(nameof(SizeText));
            }
        }
    }

    public TransferDirection Direction
    {
        get => _direction;
        set
        {
            if (SetField(ref _direction, value))
            {
                RaisePropertyChanged(nameof(DirectionText));
            }
        }
    }

    public DateTime UpdatedAt
    {
        get => _updatedAt;
        set
        {
            if (SetField(ref _updatedAt, value))
            {
                RaisePropertyChanged(nameof(TimeText));
            }
        }
    }

    public double Progress => TotalBytes <= 0 ? 0 : Math.Min(100, ReceivedBytes * 100.0 / TotalBytes);

    public string SizeText => $"{FormatBytes(ReceivedBytes)} / {FormatBytes(TotalBytes)}";
    public string DirectionText => Direction == TransferDirection.Outgoing ? "发送文件" : "接收文件";
    public string TimeText => UpdatedAt.ToString("MM-dd HH:mm:ss");

    public bool CanOpen => !string.IsNullOrWhiteSpace(SavedPath);
    public bool CanDelete => !string.IsNullOrWhiteSpace(SavedPath);

    public void UpdateFrom(TransferViewItem item)
    {
        TransferId = item.TransferId;
        FileName = item.FileName;
        TotalBytes = item.TotalBytes;
        ReceivedBytes = item.ReceivedBytes;
        StatusText = item.StatusText;
        Direction = item.Direction;
        SavedPath = item.SavedPath;
        UpdatedAt = item.UpdatedAt;
        OpenCommand.NotifyCanExecuteChanged();
        DeleteCommand.NotifyCanExecuteChanged();
    }

    private void Open()
    {
        if (!string.IsNullOrWhiteSpace(SavedPath))
        {
            OpenRequested?.Invoke(SavedPath);
        }
    }

    private void Delete()
    {
        if (CanDelete)
        {
            DeleteRequested?.Invoke(this);
        }
    }

    private static string FormatBytes(long bytes)
    {
        if (bytes <= 0)
        {
            return "0 B";
        }
        string[] units = { "B", "KB", "MB", "GB" };
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }
        return $"{value:0.##} {units[unit]}";
    }
}







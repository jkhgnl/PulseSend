using PulseSend.Core.Models;

namespace PulseSend.Windows.ViewModels;

public sealed class TransferItemViewModel : ViewModelBase
{
    private string _transferId = "";
    private string _fileName = "";
    private string _statusText = "";
    private long _totalBytes;
    private long _receivedBytes;
    private TransferDirection _direction;

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
        set => SetField(ref _statusText, value);
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
        set => SetField(ref _direction, value);
    }

    public double Progress => TotalBytes <= 0 ? 0 : Math.Min(100, ReceivedBytes * 100.0 / TotalBytes);

    public string SizeText => $"{FormatBytes(ReceivedBytes)} / {FormatBytes(TotalBytes)}";

    public void UpdateFrom(TransferViewItem item)
    {
        TransferId = item.TransferId;
        FileName = item.FileName;
        TotalBytes = item.TotalBytes;
        ReceivedBytes = item.ReceivedBytes;
        StatusText = item.StatusText;
        Direction = item.Direction;
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







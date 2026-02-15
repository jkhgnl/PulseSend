using PulseSend.Core.Models;

namespace PulseSend.Windows.ViewModels;

public sealed class MessageItemViewModel : ViewModelBase
{
    private const int PreviewLength = 120;

    private string _messageId = "";
    private string _deviceName = "";
    private string _content = "";
    private DateTime _receivedAt;
    private MessageDirection _direction;

    public Action<string>? CopyRequested { get; set; }
    public Action<string>? ViewFullRequested { get; set; }

    public RelayCommand CopyCommand { get; }
    public RelayCommand ViewFullCommand { get; }

    public MessageItemViewModel()
    {
        CopyCommand = new RelayCommand(CopyContent, () => !string.IsNullOrWhiteSpace(Content));
        ViewFullCommand = new RelayCommand(ViewFullContent, () => HasOverflow);
    }

    public string MessageId
    {
        get => _messageId;
        set => SetField(ref _messageId, value);
    }

    public string DeviceName
    {
        get => _deviceName;
        set
        {
            if (SetField(ref _deviceName, value))
            {
                RaisePropertyChanged(nameof(Header));
            }
        }
    }

    public string Content
    {
        get => _content;
        set
        {
            if (SetField(ref _content, value))
            {
                CopyCommand.NotifyCanExecuteChanged();
                ViewFullCommand.NotifyCanExecuteChanged();
                RaisePropertyChanged(nameof(PreviewContent));
                RaisePropertyChanged(nameof(HasOverflow));
            }
        }
    }

    public DateTime ReceivedAt
    {
        get => _receivedAt;
        set
        {
            if (SetField(ref _receivedAt, value))
            {
                RaisePropertyChanged(nameof(Header));
                RaisePropertyChanged(nameof(TimeText));
            }
        }
    }

    public MessageDirection Direction
    {
        get => _direction;
        set
        {
            if (SetField(ref _direction, value))
            {
                RaisePropertyChanged(nameof(Header));
                RaisePropertyChanged(nameof(IsIncoming));
                RaisePropertyChanged(nameof(IsOutgoing));
                RaisePropertyChanged(nameof(DirectionLabel));
            }
        }
    }

    public bool IsIncoming => Direction == MessageDirection.Incoming;

    public bool IsOutgoing => Direction == MessageDirection.Outgoing;

    public bool HasOverflow => !string.IsNullOrWhiteSpace(Content) && Content.Length > PreviewLength;

    public string PreviewContent => HasOverflow
        ? $"{Content[..PreviewLength].TrimEnd()}..."
        : Content;

    public string DirectionLabel => IsOutgoing ? "发送至" : "收自";

    public string TimeText => ReceivedAt.ToString("HH:mm");

    public string Header => $"{DeviceName} | {DirectionLabel} | {TimeText}";

    public void UpdateFrom(MessageViewItem item)
    {
        MessageId = item.MessageId;
        DeviceName = item.DeviceName;
        Content = item.Content;
        ReceivedAt = item.ReceivedAt;
        Direction = item.Direction;
    }

    private void CopyContent()
    {
        if (!string.IsNullOrWhiteSpace(Content))
        {
            CopyRequested?.Invoke(Content);
        }
    }

    private void ViewFullContent()
    {
        if (!string.IsNullOrWhiteSpace(Content))
        {
            ViewFullRequested?.Invoke(Content);
        }
    }
}

using PulseSend.Core.Models;

namespace PulseSend.Windows.ViewModels;

public sealed class MessageItemViewModel : ViewModelBase
{
    private string _messageId = "";
    private string _deviceName = "";
    private string _content = "";
    private DateTime _receivedAt;
    private MessageDirection _direction;

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
        set => SetField(ref _content, value);
    }

    public DateTime ReceivedAt
    {
        get => _receivedAt;
        set
        {
            if (SetField(ref _receivedAt, value))
            {
                RaisePropertyChanged(nameof(Header));
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
            }
        }
    }

    public string Header
    {
        get
        {
            var label = Direction == MessageDirection.Outgoing ? "发送" : "接收";
            return $"{DeviceName} · {label} · {ReceivedAt:HH:mm}";
        }
    }

    public void UpdateFrom(MessageViewItem item)
    {
        MessageId = item.MessageId;
        DeviceName = item.DeviceName;
        Content = item.Content;
        ReceivedAt = item.ReceivedAt;
        Direction = item.Direction;
    }
}







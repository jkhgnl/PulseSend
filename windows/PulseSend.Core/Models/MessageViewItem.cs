namespace PulseSend.Core.Models;

public sealed record MessageViewItem
{
    public string MessageId { get; init; } = "";
    public string DeviceName { get; init; } = "";
    public string Content { get; init; } = "";
    public DateTime ReceivedAt { get; init; } = DateTime.Now;
    public MessageDirection Direction { get; init; } = MessageDirection.Incoming;
}







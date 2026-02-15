namespace PulseSend.Core.Models;

public sealed record TransferViewItem
{
    public string TransferId { get; init; } = "";
    public string FileName { get; init; } = "";
    public long TotalBytes { get; set; }
    public long ReceivedBytes { get; set; }
    public string StatusText { get; set; } = "";
    public TransferDirection Direction { get; init; } = TransferDirection.Incoming;
    public string? SavedPath { get; set; }
    public DateTime UpdatedAt { get; set; } = DateTime.Now;
}







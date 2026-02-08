namespace PulseSend.Core.Models;

public sealed record ServerSnapshot
{
    public string PairCode { get; init; } = "";
    public string Fingerprint { get; init; } = "";
    public int Port { get; init; }
    public string StatusText { get; init; } = "";
    public List<DeviceRecord> TrustedDevices { get; init; } = new();
    public List<TransferViewItem> Transfers { get; init; } = new();
    public List<MessageViewItem> Messages { get; init; } = new();
}







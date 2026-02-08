namespace PulseSend.Core.Models;

public sealed record DeviceRecord
{
    public string DeviceId { get; init; } = "";
    public string DeviceName { get; init; } = "";
    public string Fingerprint { get; init; } = "";
    public string? OutgoingToken { get; init; }
    public string? IncomingToken { get; init; }
    public string Token { get; init; } = "";
    public DateTime PairedAt { get; init; } = DateTime.Now;
    public string? LastSeenAddress { get; init; }
    public int? LastSeenPort { get; init; }
}







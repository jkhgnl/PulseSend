namespace PulseSend.Core.Models;

public sealed record DeviceInfo
{
    public string Id { get; init; } = "";
    public string Name { get; init; } = "";
    public string Platform { get; init; } = "";
    public string Address { get; init; } = "";
    public int TlsPort { get; init; }
    public string? Fingerprint { get; init; }
    public bool IsTrusted { get; init; }
    public DateTime LastSeen { get; init; } = DateTime.Now;
}







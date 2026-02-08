using System.Text.Json.Serialization;

namespace PulseSend.Core.Protocol;

public sealed record DiscoveryMessage
{
    public string Type { get; init; } = "";
    public string DeviceId { get; init; } = "";
    public string DeviceName { get; init; } = "";
    public string Platform { get; init; } = "";
    public int? TlsPort { get; init; }
    public string? Fingerprint { get; init; }
}

public sealed record PairRequest
{
    public string DeviceId { get; init; } = "";
    public string DeviceName { get; init; } = "";
    public string PublicKey { get; init; } = "";
    public string Code { get; init; } = "";
}

public sealed record PairResponse
{
    public string DeviceId { get; init; } = "";
    public string DeviceName { get; init; } = "";
    public string Fingerprint { get; init; } = "";
    public string PeerPublicKey { get; init; } = "";
    public string Salt { get; init; } = "";
    public string? Token { get; init; }
}

public sealed record SessionRequest
{
    public string DeviceId { get; init; } = "";
    public string PublicKey { get; init; } = "";
    public string? Token { get; init; }
}

public sealed record SessionResponse
{
    public string PeerPublicKey { get; init; } = "";
    public string Salt { get; init; } = "";
}

public sealed record TransferInitRequest
{
    public string FileName { get; init; } = "";
    public long FileSize { get; init; }
    public string? MimeType { get; init; }
    public string Sha256 { get; init; } = "";
    public int ChunkSize { get; init; }
}

public sealed record TransferInitResponse
{
    public string TransferId { get; init; } = "";
    public bool Accepted { get; init; }
    public List<int> MissingChunks { get; init; } = new();
}

public sealed record TransferChunkRequest
{
    public string TransferId { get; init; } = "";
    public int Index { get; init; }
    public int TotalChunks { get; init; }
    public string Nonce { get; init; } = "";
    public string CipherText { get; init; } = "";
    public string Aad { get; init; } = "";
}

public sealed record TransferChunkResponse
{
    public bool Received { get; init; }
}

public sealed record TextMessageRequest
{
    public string MessageId { get; init; } = "";
    public string Nonce { get; init; } = "";
    public string CipherText { get; init; } = "";
    public string Aad { get; init; } = "";
}

public sealed record TextMessageResponse
{
    public bool Received { get; init; }
}







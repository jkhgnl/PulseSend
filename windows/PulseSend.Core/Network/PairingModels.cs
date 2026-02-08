namespace PulseSend.Core.Network;

public sealed record PairingResult(string Fingerprint, string? Token);

public sealed record E2eSession(byte[] Key, string? Token, string? Fingerprint);







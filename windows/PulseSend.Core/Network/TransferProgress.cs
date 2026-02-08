namespace PulseSend.Core.Network;

public sealed record TransferProgress(
    string TransferId,
    string FileName,
    long SentBytes,
    long TotalBytes,
    string StatusText
);







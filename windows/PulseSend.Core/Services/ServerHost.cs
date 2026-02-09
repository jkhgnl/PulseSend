using System.Collections.Concurrent;
using System;
using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Http.Json;
using Microsoft.Extensions.DependencyInjection;
using PulseSend.Core.Crypto;
using PulseSend.Core.Discovery;
using PulseSend.Core.Models;
using PulseSend.Core.Protocol;

namespace PulseSend.Core.Services;

public sealed class ServerHost
{
    public event Action<string>? TransferError;

    private const int DiscoveryPort = 24821;
    private const int DefaultTlsPort = 48084;

    private readonly object _sync = new();
    private readonly TrustedDeviceRegistry _registry;
    private readonly ConcurrentDictionary<string, DeviceRecord> _tokenIndex = new();
    private readonly ConcurrentDictionary<string, SessionBucket> _sessions = new();
    private readonly ConcurrentDictionary<string, TransferState> _transfersById = new();
    private readonly ConcurrentDictionary<string, TransferState> _transfersByHash = new();
    private readonly ConcurrentDictionary<string, TransferViewItem> _transferViews = new();
    private readonly List<MessageViewItem> _messages = new();
    private readonly CancellationTokenSource _cts = new();

    private DiscoveryResponder? _discovery;
    private WebApplication? _app;
    private X509Certificate2? _certificate;
    private string _fingerprint = "";
    private string _pairCode = "------";
    private string _statusText = "未启动";

    private readonly int _port;
    private readonly DeviceIdentity _identity;
    private readonly byte[] _infoBytes = Encoding.UTF8.GetBytes("pulse-session");

    public event Action<ServerSnapshot>? SnapshotUpdated;

    public ServerHost(DeviceIdentity identity, TrustedDeviceRegistry registry, int? port = null)
    {
        _identity = identity;
        _registry = registry;
        _port = port ?? DefaultTlsPort;
        RefreshTokenIndex();
    }

    public ServerSnapshot GetSnapshot() => BuildSnapshot();

    public void RegeneratePairCode()
    {
        _pairCode = GeneratePairCode();
        PublishSnapshot();
    }

    public async Task StartAsync()
    {
        if (_app != null)
        {
            return;
        }

        _certificate = LoadOrCreateCertificate();
        _fingerprint = CryptoUtils.ComputePin(_certificate);
        _pairCode = GeneratePairCode();

        var builder = WebApplication.CreateBuilder();
        builder.Services.Configure<JsonOptions>(options =>
        {
            options.SerializerOptions.PropertyNamingPolicy = JsonNamingPolicy.CamelCase;
            options.SerializerOptions.PropertyNameCaseInsensitive = true;
        });
        builder.WebHost.ConfigureKestrel(options =>
        {
            options.ListenAnyIP(_port, listen => listen.UseHttps(_certificate));
        });

        var app = builder.Build();
        app.MapPost("/pair", (HttpRequest http, PairRequest request) => HandlePair(http, request));
        app.MapPost("/session", (HttpRequest http, SessionRequest request) => HandleSession(http, request));
        app.MapPost("/transfer/init", (HttpRequest http, TransferInitRequest request) => HandleTransferInit(http, request));
        app.MapPost("/transfer/chunk", (HttpRequest http, TransferChunkRequest request) => HandleTransferChunk(http, request));
        app.MapPost("/message", (HttpRequest http, TextMessageRequest request) => HandleTextMessage(http, request));
        app.MapGet("/ping", () => Results.Json(new { ok = true }));

        _app = app;
        _ = app.StartAsync(_cts.Token);

        _discovery = new DiscoveryResponder(DiscoveryPort, () => new DiscoveryMessage
        {
            Type = "ADVERTISE",
            DeviceId = _identity.Id,
            DeviceName = _identity.Name,
            Platform = _identity.Platform,
            TlsPort = _port,
            Fingerprint = _fingerprint
        });
        _discovery.Start();

        _statusText = "运行中";
        PublishSnapshot();
        await Task.CompletedTask;
    }

    public async Task StopAsync()
    {
        _cts.Cancel();
        _discovery?.Dispose();
        if (_app != null)
        {
            await _app.StopAsync();
        }
        _statusText = "已停止";
        PublishSnapshot();
    }

    private IResult HandlePair(HttpRequest http, PairRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.Code) || request.Code != _pairCode)
        {
            return Results.StatusCode(StatusCodes.Status403Forbidden);
        }

        var peerSpki = CryptoUtils.FromBase64(request.PublicKey);
        var peerRaw = SpkiUtils.DecodeX25519PublicKey(peerSpki);
        var keyPair = E2eCrypto.GenerateKeyPair();
        var salt = CryptoUtils.RandomBytes(16);
        _ = E2eCrypto.DeriveSessionKey(keyPair.PrivateKey, peerRaw, salt, _infoBytes);

        var token = CryptoUtils.ToBase64(CryptoUtils.RandomBytes(32));
        var existing = _registry.FindById(request.DeviceId);
        var record = new DeviceRecord
        {
            DeviceId = request.DeviceId,
            DeviceName = request.DeviceName,
            Fingerprint = string.IsNullOrWhiteSpace(existing?.Fingerprint) ? _fingerprint : existing.Fingerprint,
            OutgoingToken = existing?.OutgoingToken,
            IncomingToken = token,
            Token = existing?.Token ?? string.Empty,
            PairedAt = DateTime.Now,
            LastSeenAddress = http.HttpContext.Connection.RemoteIpAddress?.ToString(),
            LastSeenPort = existing?.LastSeenPort
        };

        _registry.Upsert(record);
        _tokenIndex[token] = record;

        RegeneratePairCode();

        var response = new PairResponse
        {
            DeviceId = _identity.Id,
            DeviceName = _identity.Name,
            Fingerprint = _fingerprint,
            PeerPublicKey = CryptoUtils.ToBase64(keyPair.PublicKeySpki),
            Salt = CryptoUtils.ToBase64(salt),
            Token = token
        };
        return Results.Json(response);
    }

    private IResult HandleSession(HttpRequest http, SessionRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.Token) || !_tokenIndex.ContainsKey(request.Token))
        {
            return Results.Unauthorized();
        }

        var peerSpki = CryptoUtils.FromBase64(request.PublicKey);
        var peerRaw = SpkiUtils.DecodeX25519PublicKey(peerSpki);
        var keyPair = E2eCrypto.GenerateKeyPair();
        var salt = CryptoUtils.RandomBytes(16);
        var sessionKey = E2eCrypto.DeriveSessionKey(keyPair.PrivateKey, peerRaw, salt, _infoBytes);
        var bucket = _sessions.GetOrAdd(request.Token, _ => new SessionBucket());
        bucket.Add(new SessionContext(sessionKey, DateTime.UtcNow.AddMinutes(30)));

        if (_tokenIndex.TryGetValue(request.Token, out var record))
        {
            var address = http.HttpContext.Connection.RemoteIpAddress?.ToString() ?? record.LastSeenAddress;
            _registry.UpdateLastSeen(record.DeviceId, address ?? string.Empty, record.LastSeenPort);
        }

        var response = new SessionResponse
        {
            PeerPublicKey = CryptoUtils.ToBase64(keyPair.PublicKeySpki),
            Salt = CryptoUtils.ToBase64(salt)
        };
        return Results.Json(response);
    }

    private IResult HandleTransferInit(HttpRequest http, TransferInitRequest request)
    {
        if (!TryGetSession(http, out _))
        {
            return Results.Unauthorized();
        }

        if (string.IsNullOrWhiteSpace(request.FileName) || request.FileSize <= 0 || request.ChunkSize <= 0)
        {
            return Results.BadRequest();
        }

        TransferState state;
        List<int> missing;
        lock (_sync)
        {
            if (_transfersByHash.TryGetValue(request.Sha256, out var existing) && !existing.Completed)
            {
                state = existing;
                missing = state.GetMissingChunks();
            }
            else
            {
                state = TransferState.Create(request, ResolveIncomingFolder());
                _transfersByHash[request.Sha256] = state;
                _transfersById[state.TransferId] = state;
                missing = new List<int>();
                EnsureTransferView(state, "准备中");
            }
        }

        var response = new TransferInitResponse
        {
            TransferId = state.TransferId,
            Accepted = true,
            MissingChunks = missing
        };
        PublishSnapshot();
        return Results.Json(response);
    }

    private IResult HandleTransferChunk(HttpRequest http, TransferChunkRequest request)
    {
        if (!TryGetToken(http, out var token) || !TryGetActiveSessions(token, out var sessions))
        {
            return Results.Unauthorized();
        }

        if (!_transfersById.TryGetValue(request.TransferId, out var state))
        {
            return Results.NotFound();
        }

        if (request.Index < 0 || request.Index >= state.TotalChunks)
        {
            return Results.BadRequest();
        }

        try
        {
            var nonce = CryptoUtils.FromBase64(request.Nonce);
            var aad = CryptoUtils.FromBase64(request.Aad);
            var cipher = CryptoUtils.FromBase64(request.CipherText);
            if (!TryDecryptWithAnySession(sessions, nonce, aad, cipher, out var plain, out var decryptError))
            {
                TransferError?.Invoke($"数据块 {request.Index} 处理失败：{decryptError ?? "解密失败"}");
                return Results.BadRequest();
            }
            lock (state.Gate)
            {
                if (state.Completed || state.Received[request.Index])
                {
                    return Results.Json(new TransferChunkResponse { Received = true });
                }

                state.Stream.Seek((long)request.Index * state.ChunkSize, SeekOrigin.Begin);
                state.Stream.Write(plain, 0, plain.Length);
                state.Received[request.Index] = true;
                state.ReceivedBytes += plain.Length;
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"Chunk {request.Index} failed: {ex}");
            TransferError?.Invoke($"数据块 {request.Index} 处理失败：{ex.Message}");
            return Results.BadRequest();
        }

        UpdateTransferView(state, "传输中");

        if (state.IsComplete())
        {
            FinalizeTransfer(state);
        }

        return Results.Json(new TransferChunkResponse { Received = true });
    }

    private IResult HandleTextMessage(HttpRequest http, TextMessageRequest request)
    {
        if (!TryGetToken(http, out var token) || !TryGetActiveSessions(token, out var sessions))
        {
            return Results.Unauthorized();
        }

        if (string.IsNullOrWhiteSpace(request.CipherText) ||
            string.IsNullOrWhiteSpace(request.Nonce) ||
            string.IsNullOrWhiteSpace(request.Aad))
        {
            return Results.BadRequest();
        }

        var nonce = CryptoUtils.FromBase64(request.Nonce);
        var aad = CryptoUtils.FromBase64(request.Aad);
        var cipher = CryptoUtils.FromBase64(request.CipherText);
        if (!TryDecryptWithAnySession(sessions, nonce, aad, cipher, out var plain, out _))
        {
            return Results.BadRequest();
        }
        var text = Encoding.UTF8.GetString(plain);
        var messageId = string.IsNullOrWhiteSpace(request.MessageId)
            ? Guid.NewGuid().ToString("N")
            : request.MessageId;
        var deviceName = _tokenIndex.TryGetValue(token, out var record) ? record.DeviceName : "未知设备";

        lock (_sync)
        {
            _messages.Add(new MessageViewItem
            {
                MessageId = messageId,
                DeviceName = deviceName,
                Content = text,
                ReceivedAt = DateTime.Now,
                Direction = MessageDirection.Incoming
            });
            if (_messages.Count > 50)
            {
                _messages.RemoveRange(0, _messages.Count - 50);
            }
        }

        PublishSnapshot();
        return Results.Json(new TextMessageResponse { Received = true });
    }

    private bool TryGetSession(HttpRequest request, out SessionContext session)
    {
        session = default!;
        if (!TryGetToken(request, out var token))
        {
            return false;
        }
        if (!TryGetActiveSessions(token, out var sessions))
        {
            return false;
        }
        session = sessions[0];
        return true;
    }

    private bool TryGetToken(HttpRequest request, out string token)
    {
        token = "";
        if (!request.Headers.TryGetValue("X-Session-Token", out var tokenValues))
        {
            return false;
        }
        token = tokenValues.ToString();
        return !string.IsNullOrWhiteSpace(token);
    }

    private bool TryGetActiveSessions(string token, out List<SessionContext> sessions)
    {
        sessions = new List<SessionContext>();
        if (!_sessions.TryGetValue(token, out var bucket))
        {
            return false;
        }
        sessions = bucket.GetActive();
        if (sessions.Count == 0)
        {
            _sessions.TryRemove(token, out _);
            return false;
        }
        return true;
    }

    private static bool TryDecryptWithAnySession(
        List<SessionContext> sessions,
        byte[] nonce,
        byte[] aad,
        byte[] cipher,
        out byte[] plain,
        out string? error)
    {
        plain = Array.Empty<byte>();
        error = null;
        foreach (var session in sessions)
        {
            try
            {
                plain = E2eCrypto.Decrypt(session.Key, nonce, aad, cipher);
                return true;
            }
            catch (CryptographicException ex)
            {
                error = ex.Message;
            }
        }
        return false;
    }

    private void FinalizeTransfer(TransferState state)
    {
        lock (state.Gate)
        {
            if (state.Completed)
            {
                return;
            }
            state.Completed = true;
            state.Stream.Flush();
            state.Stream.Dispose();
            if (File.Exists(state.FinalPath))
            {
                File.Delete(state.FinalPath);
            }
            File.Move(state.PartPath, state.FinalPath);
        }
        UpdateTransferView(state, "已完成");
    }

    private void EnsureTransferView(TransferState state, string status)
    {
        var view = new TransferViewItem
        {
            TransferId = state.TransferId,
            FileName = state.FileName,
            TotalBytes = state.TotalBytes,
            ReceivedBytes = state.ReceivedBytes,
            StatusText = status,
            Direction = TransferDirection.Incoming
        };
        _transferViews[state.TransferId] = view;
        PublishSnapshot();
    }

    private void UpdateTransferView(TransferState state, string status)
    {
        if (_transferViews.TryGetValue(state.TransferId, out var view))
        {
            view.ReceivedBytes = state.ReceivedBytes;
            view.TotalBytes = state.TotalBytes;
            view.StatusText = status;
        }
        PublishSnapshot();
    }

    private ServerSnapshot BuildSnapshot()
    {
        lock (_sync)
        {
            return new ServerSnapshot
            {
                PairCode = _pairCode,
                Fingerprint = _fingerprint,
                Port = _port,
                StatusText = _statusText,
                TrustedDevices = _registry.GetAll().ToList(),
                Transfers = _transferViews.Values
                    .Select(t => new TransferViewItem
                    {
                        TransferId = t.TransferId,
                        FileName = t.FileName,
                        TotalBytes = t.TotalBytes,
                        ReceivedBytes = t.ReceivedBytes,
                        StatusText = t.StatusText,
                        Direction = t.Direction
                    })
                    .ToList(),
                Messages = _messages
                    .Select(m => new MessageViewItem
                    {
                        MessageId = m.MessageId,
                        DeviceName = m.DeviceName,
                        Content = m.Content,
                        ReceivedAt = m.ReceivedAt,
                        Direction = m.Direction
                    })
                    .ToList()
            };
        }
    }

    private void PublishSnapshot()
    {
        SnapshotUpdated?.Invoke(BuildSnapshot());
    }

    private void RefreshTokenIndex()
    {
        _tokenIndex.Clear();
        foreach (var record in _registry.GetAll())
        {
            var token = TrustedDeviceRegistry.ResolveIncomingToken(record);
            if (!string.IsNullOrWhiteSpace(token))
            {
                _tokenIndex[token] = record;
            }
        }
    }

    private static string GeneratePairCode() =>
        RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");

    private static X509Certificate2 LoadOrCreateCertificate()
    {
        var path = GetCertificatePath();
        if (File.Exists(path))
        {
            try
            {
                var bytes = File.ReadAllBytes(path);
                return new X509Certificate2(bytes, (string?)null, X509KeyStorageFlags.Exportable);
            }
            catch
            {
                // fall through to regenerate
            }
        }

        var cert = CreateSelfSignedCertificate();
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path) ?? "");
            File.WriteAllBytes(path, cert.Export(X509ContentType.Pfx));
        }
        catch
        {
            // ignore persistence failures; use ephemeral cert
        }
        return cert;
    }

    private static string GetCertificatePath()
    {
        var root = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "PulseSend");
        return Path.Combine(root, "server_cert.pfx");
    }

    private static X509Certificate2 CreateSelfSignedCertificate()
    {
        using var ecdsa = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest(
            "CN=PulseSend",
            ecdsa,
            HashAlgorithmName.SHA256);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, false));
        var cert = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(1));
        return new X509Certificate2(cert.Export(X509ContentType.Pfx));
    }

    private static string ResolveIncomingFolder()
    {
        var folder = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "PulseSend",
            "Incoming");
        Directory.CreateDirectory(folder);
        return folder;
    }

    private sealed class SessionContext
    {
        public SessionContext(byte[] key, DateTime expiresAt)
        {
            Key = key;
            ExpiresAt = expiresAt;
        }

        public byte[] Key { get; }
        public DateTime ExpiresAt { get; }
    }

    private sealed class SessionBucket
    {
        private readonly object _gate = new();
        private readonly List<SessionContext> _sessions = new();

        public void Add(SessionContext session)
        {
            lock (_gate)
            {
                Prune();
                _sessions.Insert(0, session);
                if (_sessions.Count > 4)
                {
                    _sessions.RemoveRange(4, _sessions.Count - 4);
                }
            }
        }

        public List<SessionContext> GetActive()
        {
            lock (_gate)
            {
                Prune();
                return new List<SessionContext>(_sessions);
            }
        }

        private void Prune()
        {
            var now = DateTime.UtcNow;
            _sessions.RemoveAll(item => item.ExpiresAt < now);
        }
    }

    private sealed class TransferState
    {
        private TransferState(
            string transferId,
            string sha256,
            string fileName,
            string partPath,
            string finalPath,
            int chunkSize,
            int totalChunks,
            long totalBytes)
        {
            TransferId = transferId;
            Sha256 = sha256;
            FileName = fileName;
            PartPath = partPath;
            FinalPath = finalPath;
            ChunkSize = chunkSize;
            TotalChunks = totalChunks;
            TotalBytes = totalBytes;
            Received = new bool[totalChunks];
            Stream = new FileStream(partPath, FileMode.OpenOrCreate, FileAccess.Write, FileShare.None);
        }

        public string TransferId { get; }
        public string Sha256 { get; }
        public string FileName { get; }
        public string PartPath { get; }
        public string FinalPath { get; }
        public int ChunkSize { get; }
        public int TotalChunks { get; }
        public long TotalBytes { get; }
        public bool[] Received { get; }
        public FileStream Stream { get; }
        public long ReceivedBytes { get; set; }
        public bool Completed { get; set; }
        public object Gate { get; } = new();

        public List<int> GetMissingChunks()
        {
            var missing = new List<int>();
            for (var i = 0; i < Received.Length; i++)
            {
                if (!Received[i])
                {
                    missing.Add(i);
                }
            }
            return missing;
        }

        public bool IsComplete()
        {
            for (var i = 0; i < Received.Length; i++)
            {
                if (!Received[i])
                {
                    return false;
                }
            }
            return true;
        }

        public static TransferState Create(TransferInitRequest request, string folder)
        {
            var sanitized = SanitizeFileName(request.FileName);
            var basePath = Path.Combine(folder, sanitized);
            var finalPath = EnsureUniquePath(basePath);
            var partPath = finalPath + ".part";
            var totalChunks = Math.Max(1, (int)Math.Ceiling(request.FileSize / (double)request.ChunkSize));
            return new TransferState(
                transferId: Guid.NewGuid().ToString("N"),
                sha256: request.Sha256,
                fileName: Path.GetFileName(finalPath),
                partPath: partPath,
                finalPath: finalPath,
                chunkSize: request.ChunkSize,
                totalChunks: totalChunks,
                totalBytes: request.FileSize);
        }

        private static string SanitizeFileName(string name)
        {
            var invalid = Path.GetInvalidFileNameChars();
            var cleaned = new string(name.Select(ch => invalid.Contains(ch) ? '_' : ch).ToArray());
            return string.IsNullOrWhiteSpace(cleaned) ? "file.bin" : cleaned;
        }

        private static string EnsureUniquePath(string path)
        {
            if (!File.Exists(path))
            {
                return path;
            }
            var directory = Path.GetDirectoryName(path) ?? "";
            var filename = Path.GetFileNameWithoutExtension(path);
            var ext = Path.GetExtension(path);
            for (var i = 1; i < 1000; i++)
            {
                var candidate = Path.Combine(directory, $"{filename} ({i}){ext}");
                if (!File.Exists(candidate))
                {
                    return candidate;
                }
            }
            return Path.Combine(directory, $"{filename}-{Guid.NewGuid():N}{ext}");
        }
    }
}




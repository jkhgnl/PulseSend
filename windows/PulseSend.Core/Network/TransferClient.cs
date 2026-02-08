using System.Net.Http;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using PulseSend.Core.Crypto;
using PulseSend.Core.Models;
using PulseSend.Core.Protocol;

namespace PulseSend.Core.Network;

public sealed class TransferClient
{
    private const int ChunkSize = 1_048_576;
    private const int DefaultPort = 48084;

    private readonly SecureTransport _transport;
    private readonly DeviceIdentity _identity;
    private readonly JsonSerializerOptions _options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true
    };

    public TransferClient(SecureTransport transport, DeviceIdentity identity)
    {
        _transport = transport;
        _identity = identity;
    }

    public async Task SendFileAsync(DeviceInfo device, string filePath, Action<TransferProgress>? onProgress = null)
    {
        var session = await _transport.OpenSessionAsync(device, _identity);
        var fileInfo = new FileInfo(filePath);
        if (!fileInfo.Exists)
        {
            throw new FileNotFoundException("文件不存在", filePath);
        }

        var hashResult = ComputeSha256(fileInfo);
        var fileSize = fileInfo.Length > 0 ? fileInfo.Length : hashResult.Size;
        var initRequest = new TransferInitRequest
        {
            FileName = fileInfo.Name,
            FileSize = fileSize,
            MimeType = null,
            Sha256 = hashResult.Hash,
            ChunkSize = ChunkSize
        };

        using var client = CreatePinnedClient(session.Fingerprint);
        var initResponse = await PostJsonAsync<TransferInitResponse>(client, device, "/transfer/init", initRequest, session.Token);
        if (!initResponse.Accepted)
        {
            throw new InvalidOperationException("接收端拒绝传输。");
        }

        var totalChunks = Math.Max(1, (int)Math.Ceiling(fileSize / (double)ChunkSize));
        var missing = initResponse.MissingChunks?.ToHashSet() ?? new HashSet<int>();
        onProgress?.Invoke(new TransferProgress(initResponse.TransferId, fileInfo.Name, 0, fileSize, "准备中"));

        using var stream = new FileStream(fileInfo.FullName, FileMode.Open, FileAccess.Read, FileShare.Read);
        var buffer = new byte[ChunkSize];
        var index = 0;
        var sent = 0L;
        while (true)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(0, buffer.Length));
            if (read <= 0)
            {
                break;
            }
            var shouldSend = missing.Count == 0 || missing.Contains(index);
            if (shouldSend)
            {
                var chunk = buffer.AsSpan(0, read).ToArray();
                var nonce = CryptoUtils.RandomBytes(12);
                var aad = Encoding.UTF8.GetBytes($"{initResponse.TransferId}:{index}");
                var cipher = E2eCrypto.Encrypt(session.Key, nonce, aad, chunk);
                var chunkRequest = new TransferChunkRequest
                {
                    TransferId = initResponse.TransferId,
                    Index = index,
                    TotalChunks = totalChunks,
                    Nonce = CryptoUtils.ToBase64(nonce),
                    CipherText = CryptoUtils.ToBase64(cipher),
                    Aad = CryptoUtils.ToBase64(aad)
                };
                var chunkResponse = await PostJsonAsync<TransferChunkResponse>(client, device, "/transfer/chunk", chunkRequest, session.Token);
                if (!chunkResponse.Received)
                {
                    throw new InvalidOperationException("分片发送失败。");
                }
            }
            sent += read;
            onProgress?.Invoke(new TransferProgress(initResponse.TransferId, fileInfo.Name, sent, fileSize, "传输中"));
            index++;
        }
        onProgress?.Invoke(new TransferProgress(initResponse.TransferId, fileInfo.Name, sent, fileSize, "已完成"));
    }

    public async Task SendMessageAsync(DeviceInfo device, string text)
    {
        var session = await _transport.OpenSessionAsync(device, _identity);
        using var client = CreatePinnedClient(session.Fingerprint);
        var messageId = Guid.NewGuid().ToString("N");
        var nonce = CryptoUtils.RandomBytes(12);
        var aad = Encoding.UTF8.GetBytes($"message:{messageId}");
        var cipher = E2eCrypto.Encrypt(session.Key, nonce, aad, Encoding.UTF8.GetBytes(text));
        var request = new TextMessageRequest
        {
            MessageId = messageId,
            Nonce = CryptoUtils.ToBase64(nonce),
            CipherText = CryptoUtils.ToBase64(cipher),
            Aad = CryptoUtils.ToBase64(aad)
        };
        var response = await PostJsonAsync<TextMessageResponse>(client, device, "/message", request, session.Token);
        if (!response.Received)
        {
            throw new InvalidOperationException("消息发送失败。");
        }
    }

    private async Task<T> PostJsonAsync<T>(HttpClient client, DeviceInfo device, string path, object payload, string? token)
    {
        var port = device.TlsPort > 0 ? device.TlsPort : DefaultPort;
        var url = UrlUtils.BuildHttpsUrl(device.Address, port, path);
        var json = JsonSerializer.Serialize(payload, _options);
        using var request = new HttpRequestMessage(HttpMethod.Post, url)
        {
            Content = new StringContent(json, Encoding.UTF8, "application/json")
        };
        if (!string.IsNullOrWhiteSpace(token))
        {
            request.Headers.Add("X-Session-Token", token);
        }
        using var response = await client.SendAsync(request);
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException($"请求失败：{(int)response.StatusCode}");
        }
        var body = await response.Content.ReadAsStringAsync();
        return JsonSerializer.Deserialize<T>(body, _options)
               ?? throw new InvalidOperationException("响应解析失败。");
    }

    private static HttpClient CreatePinnedClient(string? expectedPin)
    {
        var handler = new HttpClientHandler();
        handler.ServerCertificateCustomValidationCallback = (_, certificate, _, _) =>
        {
            if (certificate is X509Certificate2 cert)
            {
                var pin = CryptoUtils.ComputePin(cert);
                if (!string.IsNullOrWhiteSpace(expectedPin))
                {
                    return string.Equals(pin, expectedPin, StringComparison.Ordinal);
                }
            }
            return string.IsNullOrWhiteSpace(expectedPin);
        };
        return new HttpClient(handler)
        {
            Timeout = TimeSpan.FromSeconds(30)
        };
    }

    private static HashResult ComputeSha256(FileInfo file)
    {
        using var stream = file.OpenRead();
        using var sha = System.Security.Cryptography.SHA256.Create();
        var buffer = new byte[8192];
        int read;
        long total = 0;
        while ((read = stream.Read(buffer, 0, buffer.Length)) > 0)
        {
            sha.TransformBlock(buffer, 0, read, null, 0);
            total += read;
        }
        sha.TransformFinalBlock(Array.Empty<byte>(), 0, 0);
        var hash = CryptoUtils.ToBase64(sha.Hash ?? Array.Empty<byte>());
        return new HashResult(hash, total);
    }

    private sealed record HashResult(string Hash, long Size);
}







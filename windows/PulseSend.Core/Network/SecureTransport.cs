using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using PulseSend.Core.Crypto;
using PulseSend.Core.Models;
using PulseSend.Core.Protocol;
using PulseSend.Core.Services;

namespace PulseSend.Core.Network;

public sealed class SecureTransport
{
    private const int DefaultPort = 48084;

    private readonly TrustedDeviceRegistry _registry;
    private readonly JsonSerializerOptions _options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true
    };
    private readonly byte[] _infoBytes = Encoding.UTF8.GetBytes("pulse-session");

    public SecureTransport(TrustedDeviceRegistry registry)
    {
        _registry = registry;
    }

    public async Task<PairingResult> PairAsync(DeviceInfo device, DeviceIdentity identity, string code)
    {
        var keyPair = E2eCrypto.GenerateKeyPair();
        var requestBody = new PairRequest
        {
            DeviceId = identity.Id,
            DeviceName = identity.Name,
            PublicKey = CryptoUtils.ToBase64(keyPair.PublicKeySpki),
            Code = code
        };

        string? observedPin = null;
        using var client = CreateClient(null, pin => observedPin = pin);
        var url = BuildUrl(device, null, "/pair");
        var payload = JsonSerializer.Serialize(requestBody, _options);
        using var response = await client.PostAsync(url, new StringContent(payload, Encoding.UTF8, "application/json"));
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException($"配对失败：{(int)response.StatusCode}");
        }
        var body = await response.Content.ReadAsStringAsync();
        var parsed = JsonSerializer.Deserialize<PairResponse>(body, _options)
                     ?? throw new InvalidOperationException("配对响应为空。");

        var peerSpki = CryptoUtils.FromBase64(parsed.PeerPublicKey);
        var peerRaw = SpkiUtils.DecodeX25519PublicKey(peerSpki);
        var salt = CryptoUtils.FromBase64(parsed.Salt);
        _ = E2eCrypto.DeriveSessionKey(keyPair.PrivateKey, peerRaw, salt, _infoBytes);

        var fingerprint = observedPin ?? parsed.Fingerprint;
        var existing = _registry.FindById(parsed.DeviceId);
        var record = new DeviceRecord
        {
            DeviceId = parsed.DeviceId,
            DeviceName = parsed.DeviceName,
            Fingerprint = fingerprint,
            OutgoingToken = parsed.Token,
            IncomingToken = existing?.IncomingToken,
            Token = string.Empty,
            PairedAt = DateTime.Now,
            LastSeenAddress = device.Address,
            LastSeenPort = ResolvePort(device, null)
        };
        _registry.Upsert(record);
        return new PairingResult(fingerprint, parsed.Token);
    }

    public async Task<E2eSession> OpenSessionAsync(DeviceInfo device, DeviceIdentity identity)
    {
        var record = _registry.FindById(device.Id)
                     ?? throw new InvalidOperationException("设备尚未配对。");
        var outgoingToken = TrustedDeviceRegistry.ResolveOutgoingToken(record);
        if (string.IsNullOrWhiteSpace(outgoingToken))
        {
            throw new InvalidOperationException("设备尚未配对。");
        }

        string? observedPin = null;
        var expected = string.IsNullOrWhiteSpace(record.Fingerprint) ? null : record.Fingerprint;
        using var client = CreateClient(expected, pin => observedPin = pin);

        var keyPair = E2eCrypto.GenerateKeyPair();
        var requestBody = new SessionRequest
        {
            DeviceId = identity.Id,
            PublicKey = CryptoUtils.ToBase64(keyPair.PublicKeySpki),
            Token = outgoingToken
        };

        var url = BuildUrl(device, record, "/session");
        var payload = JsonSerializer.Serialize(requestBody, _options);
        using var response = await client.PostAsync(url, new StringContent(payload, Encoding.UTF8, "application/json"));
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException($"会话失败：{(int)response.StatusCode}");
        }
        var body = await response.Content.ReadAsStringAsync();
        var parsed = JsonSerializer.Deserialize<SessionResponse>(body, _options)
                     ?? throw new InvalidOperationException("会话响应为空。");

        var peerSpki = CryptoUtils.FromBase64(parsed.PeerPublicKey);
        var peerRaw = SpkiUtils.DecodeX25519PublicKey(peerSpki);
        var salt = CryptoUtils.FromBase64(parsed.Salt);
        var sessionKey = E2eCrypto.DeriveSessionKey(keyPair.PrivateKey, peerRaw, salt, _infoBytes);

        var finalPin = observedPin ?? expected;
        if (!string.IsNullOrWhiteSpace(finalPin) && finalPin != record.Fingerprint)
        {
            var updated = record with { Fingerprint = finalPin };
            _registry.Upsert(updated);
        }
        _registry.UpdateLastSeen(record.DeviceId, device.Address, ResolvePort(device, record));
        return new E2eSession(sessionKey, outgoingToken, finalPin);
    }

    public async Task<bool> PingAsync(DeviceInfo device)
    {
        var record = _registry.FindById(device.Id);
        var expected = record?.Fingerprint ?? device.Fingerprint;
        using var client = CreateClient(string.IsNullOrWhiteSpace(expected) ? null : expected, _ => { });
        var url = BuildUrl(device, record, "/ping");
        using var response = await client.GetAsync(url);
        return response.IsSuccessStatusCode;
    }

    private static HttpClient CreateClient(string? expectedPin, Action<string> onPinObserved)
    {
        var handler = new HttpClientHandler();
        handler.ServerCertificateCustomValidationCallback = (_, certificate, _, _) =>
        {
            if (certificate is X509Certificate2 cert)
            {
                var pin = CryptoUtils.ComputePin(cert);
                onPinObserved(pin);
                if (!string.IsNullOrWhiteSpace(expectedPin))
                {
                    return string.Equals(pin, expectedPin, StringComparison.Ordinal);
                }
            }
            return string.IsNullOrWhiteSpace(expectedPin);
        };
        return new HttpClient(handler)
        {
            Timeout = TimeSpan.FromSeconds(20)
        };
    }

    private static string BuildUrl(DeviceInfo device, DeviceRecord? record, string path)
    {
        var port = ResolvePort(device, record);
        return UrlUtils.BuildHttpsUrl(device.Address, port, path);
    }

    private static int ResolvePort(DeviceInfo device, DeviceRecord? record)
    {
        if (device.TlsPort > 0)
        {
            return device.TlsPort;
        }
        if (record?.LastSeenPort is > 0)
        {
            return record.LastSeenPort.Value;
        }
        return DefaultPort;
    }
}







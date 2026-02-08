using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using PulseSend.Core.Models;
using PulseSend.Core.Protocol;

namespace PulseSend.Core.Discovery;

public sealed class DiscoveryScanner : IDisposable
{
    private readonly DeviceIdentity _identity;
    private readonly int _port;
    private readonly CancellationTokenSource _cts = new();
    private Task? _sender;
    private Task? _receiver;
    private UdpClient? _client;

    public event Action<DeviceInfo>? DeviceDiscovered;

    public DiscoveryScanner(DeviceIdentity identity, int port = 24821)
    {
        _identity = identity;
        _port = port;
    }

    public void Start()
    {
        var options = new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            PropertyNameCaseInsensitive = true
        };

        // Use an ephemeral local port to avoid binding conflicts with the responder.
        _client = new UdpClient(0) { EnableBroadcast = true };

        _sender = Task.Run(async () =>
        {
            var address = IPAddress.Broadcast;
            while (!_cts.IsCancellationRequested)
            {
                try
                {
                    var message = new DiscoveryMessage
                    {
                        Type = "DISCOVER",
                        DeviceId = _identity.Id,
                        DeviceName = _identity.Name,
                        Platform = _identity.Platform
                    };
                    var payload = JsonSerializer.SerializeToUtf8Bytes(message, options);
                    await _client.SendAsync(payload, payload.Length, new IPEndPoint(address, _port));
                    await Task.Delay(TimeSpan.FromSeconds(2), _cts.Token);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch
                {
                    await Task.Delay(500, _cts.Token);
                }
            }
        }, _cts.Token);

        _receiver = Task.Run(async () =>
        {
            while (!_cts.IsCancellationRequested)
            {
                try
                {
                    var result = await _client.ReceiveAsync(_cts.Token);
                    var message = JsonSerializer.Deserialize<DiscoveryMessage>(result.Buffer, options);
                    if (message == null || !string.Equals(message.Type, "ADVERTISE", StringComparison.OrdinalIgnoreCase))
                    {
                        continue;
                    }
                    if (message.DeviceId == _identity.Id)
                    {
                        continue;
                    }
                    var device = new DeviceInfo
                    {
                        Id = message.DeviceId,
                        Name = message.DeviceName,
                        Platform = message.Platform,
                        Address = result.RemoteEndPoint.Address.ToString(),
                        TlsPort = message.TlsPort ?? 0,
                        Fingerprint = message.Fingerprint,
                        LastSeen = DateTime.Now
                    };
                    DeviceDiscovered?.Invoke(device);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch
                {
                    await Task.Delay(200, _cts.Token);
                }
            }
        }, _cts.Token);
    }

    public void Dispose()
    {
        _cts.Cancel();
        try { _sender?.Wait(500); } catch { }
        try { _receiver?.Wait(500); } catch { }
        _client?.Dispose();
    }
}


using System.Net.Sockets;
using System.Text.Json;
using PulseSend.Core.Protocol;

namespace PulseSend.Core.Discovery;

public sealed class DiscoveryResponder : IDisposable
{
    private readonly int _port;
    private readonly Func<DiscoveryMessage> _messageFactory;
    private readonly CancellationTokenSource _cts = new();
    private Task? _task;

    public DiscoveryResponder(int port, Func<DiscoveryMessage> messageFactory)
    {
        _port = port;
        _messageFactory = messageFactory;
    }

    public void Start()
    {
        _task = Task.Run(async () =>
        {
            using var client = new UdpClient(_port);
            client.EnableBroadcast = true;
            var options = new JsonSerializerOptions
            {
                PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
                PropertyNameCaseInsensitive = true
            };
            while (!_cts.IsCancellationRequested)
            {
                try
                {
                    var result = await client.ReceiveAsync(_cts.Token);
                    var message = JsonSerializer.Deserialize<DiscoveryMessage>(result.Buffer, options);
                    if (message == null || !string.Equals(message.Type, "DISCOVER", StringComparison.OrdinalIgnoreCase))
                    {
                        continue;
                    }
                    var response = _messageFactory();
                    var payload = JsonSerializer.SerializeToUtf8Bytes(response, options);
                    await client.SendAsync(payload, payload.Length, result.RemoteEndPoint);
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
        try
        {
            _task?.Wait(500);
        }
        catch
        {
            // ignore
        }
    }
}







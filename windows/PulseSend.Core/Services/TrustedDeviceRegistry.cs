using System.Linq;
using PulseSend.Core.Models;
using PulseSend.Core.Storage;

namespace PulseSend.Core.Services;

public sealed class TrustedDeviceRegistry
{
    private readonly object _sync = new();
    private readonly TrustedDeviceStore _store;
    private readonly List<DeviceRecord> _devices;

    public TrustedDeviceRegistry(TrustedDeviceStore store)
    {
        _store = store;
        _devices = store.Load();
    }

    public IReadOnlyList<DeviceRecord> GetAll()
    {
        lock (_sync)
        {
            return _devices.Select(d => d).ToList();
        }
    }

    public DeviceRecord? FindById(string deviceId)
    {
        lock (_sync)
        {
            return _devices.FirstOrDefault(d => d.DeviceId == deviceId);
        }
    }

    public DeviceRecord? FindByToken(string token)
    {
        lock (_sync)
        {
            return _devices.FirstOrDefault(d => ResolveIncomingToken(d) == token);
        }
    }

    public void Upsert(DeviceRecord record)
    {
        lock (_sync)
        {
            _devices.RemoveAll(d => d.DeviceId == record.DeviceId);
            _devices.Add(record);
            _store.Save(_devices);
        }
    }

    public void UpdateLastSeen(string deviceId, string address, int? port)
    {
        lock (_sync)
        {
            var existing = _devices.FirstOrDefault(d => d.DeviceId == deviceId);
            if (existing == null)
            {
                return;
            }
            var updated = existing with
            {
                LastSeenAddress = address,
                LastSeenPort = port ?? existing.LastSeenPort
            };
            _devices.RemoveAll(d => d.DeviceId == deviceId);
            _devices.Add(updated);
            _store.Save(_devices);
        }
    }

    public void ResetTokens(string deviceId)
    {
        lock (_sync)
        {
            var existing = _devices.FirstOrDefault(d => d.DeviceId == deviceId);
            if (existing == null)
            {
                return;
            }
            var updated = existing with
            {
                OutgoingToken = string.Empty,
                IncomingToken = string.Empty,
                Token = string.Empty
            };
            _devices.RemoveAll(d => d.DeviceId == deviceId);
            _devices.Add(updated);
            _store.Save(_devices);
        }
    }

    public void ResetAllTokens()
    {
        lock (_sync)
        {
            for (var i = 0; i < _devices.Count; i++)
            {
                var entry = _devices[i];
                _devices[i] = entry with
                {
                    OutgoingToken = string.Empty,
                    IncomingToken = string.Empty,
                    Token = string.Empty
                };
            }
            _store.Save(_devices);
        }
    }

    public void Remove(string deviceId)
    {
        lock (_sync)
        {
            if (_devices.RemoveAll(d => d.DeviceId == deviceId) > 0)
            {
                _store.Save(_devices);
            }
        }
    }

    public static string? ResolveOutgoingToken(DeviceRecord record)
    {
        if (!string.IsNullOrWhiteSpace(record.OutgoingToken))
        {
            return record.OutgoingToken;
        }
        if (string.IsNullOrWhiteSpace(record.IncomingToken) && !string.IsNullOrWhiteSpace(record.Token) && !string.IsNullOrWhiteSpace(record.Fingerprint))
        {
            return record.Token;
        }
        return null;
    }

    public static string? ResolveIncomingToken(DeviceRecord record)
    {
        if (!string.IsNullOrWhiteSpace(record.IncomingToken))
        {
            return record.IncomingToken;
        }
        if (string.IsNullOrWhiteSpace(record.OutgoingToken) && !string.IsNullOrWhiteSpace(record.Token) && string.IsNullOrWhiteSpace(record.Fingerprint))
        {
            return record.Token;
        }
        return null;
    }
}








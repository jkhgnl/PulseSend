using System.Text.Json;
using PulseSend.Core.Models;

namespace PulseSend.Core.Storage;

public sealed class TrustedDeviceStore
{
    private readonly string _path;
    private readonly JsonSerializerOptions _options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true
    };

    public TrustedDeviceStore(string? basePath = null)
    {
        var root = basePath ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "PulseSend");
        Directory.CreateDirectory(root);
        _path = Path.Combine(root, "trusted_devices.json");
    }

    public List<DeviceRecord> Load()
    {
        if (!File.Exists(_path))
        {
            return new List<DeviceRecord>();
        }
        var json = File.ReadAllText(_path);
        return JsonSerializer.Deserialize<List<DeviceRecord>>(json, _options)
               ?? new List<DeviceRecord>();
    }

        public void Save(List<DeviceRecord> devices)
        {
            var json = JsonSerializer.Serialize(devices, _options);
            File.WriteAllText(_path, json);
        }

        public void Clear()
        {
            Save(new List<DeviceRecord>());
        }
}







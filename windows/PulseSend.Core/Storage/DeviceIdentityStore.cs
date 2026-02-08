using System.Text.Json;
using PulseSend.Core.Models;

namespace PulseSend.Core.Storage;

public sealed class DeviceIdentityStore
{
    private readonly string _path;
    private readonly JsonSerializerOptions _options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true
    };

    public DeviceIdentityStore(string? basePath = null)
    {
        var root = basePath ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "PulseSend");
        Directory.CreateDirectory(root);
        _path = Path.Combine(root, "device_identity.json");
    }

    public DeviceIdentity Load(string defaultName, string platform)
    {
        if (File.Exists(_path))
        {
            var json = File.ReadAllText(_path);
            var identity = JsonSerializer.Deserialize<DeviceIdentity>(json, _options);
            if (identity != null)
            {
                return identity;
            }
        }
        var created = new DeviceIdentity
        {
            Id = Guid.NewGuid().ToString("N"),
            Name = defaultName,
            Platform = platform
        };
        Save(created);
        return created;
    }

    public void Save(DeviceIdentity identity)
    {
        var json = JsonSerializer.Serialize(identity, _options);
        File.WriteAllText(_path, json);
    }
}







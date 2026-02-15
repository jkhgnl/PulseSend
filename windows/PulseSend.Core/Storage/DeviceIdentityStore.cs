using System.Security.Cryptography;
using System.Text;
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
            var raw = File.ReadAllText(_path);
            var json = Decrypt(raw) ?? raw;
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
        File.WriteAllText(_path, Encrypt(json));
    }

    private static string Encrypt(string plain)
    {
        var bytes = Encoding.UTF8.GetBytes(plain);
        var protectedBytes = ProtectedData.Protect(bytes, null, DataProtectionScope.CurrentUser);
        return Convert.ToBase64String(protectedBytes);
    }

    private static string? Decrypt(string cipher)
    {
        try
        {
            var protectedBytes = Convert.FromBase64String(cipher);
            var bytes = ProtectedData.Unprotect(protectedBytes, null, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(bytes);
        }
        catch
        {
            return null;
        }
    }
}

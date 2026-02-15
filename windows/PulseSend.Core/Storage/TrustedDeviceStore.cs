using System.Security.Cryptography;
using System.Text;
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

        var raw = File.ReadAllText(_path);
        var json = Decrypt(raw) ?? raw;
        return JsonSerializer.Deserialize<List<DeviceRecord>>(json, _options)
               ?? new List<DeviceRecord>();
    }

    public void Save(List<DeviceRecord> devices)
    {
        var json = JsonSerializer.Serialize(devices, _options);
        File.WriteAllText(_path, Encrypt(json));
    }

    public void Clear()
    {
        Save(new List<DeviceRecord>());
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

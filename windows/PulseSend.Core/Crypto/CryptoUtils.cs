using System.Linq;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace PulseSend.Core.Crypto;

public static class CryptoUtils
{
    private static readonly RandomNumberGenerator Random = RandomNumberGenerator.Create();

    public static byte[] Sha256(byte[] input)
    {
        using var sha = SHA256.Create();
        return sha.ComputeHash(input);
    }

    public static byte[] RandomBytes(int size)
    {
        var buffer = new byte[size];
        Random.GetBytes(buffer);
        return buffer;
    }

    public static byte[] HkdfSha256(byte[] ikm, byte[] salt, byte[] info, int size)
    {
        using var hmac = new HMACSHA256(salt);
        var prk = hmac.ComputeHash(ikm);
        var output = new List<byte>(size);
        var previous = Array.Empty<byte>();
        byte counter = 1;
        while (output.Count < size)
        {
            using var stepHmac = new HMACSHA256(prk);
            var data = previous.Concat(info).Concat(new[] { counter }).ToArray();
            previous = stepHmac.ComputeHash(data);
            output.AddRange(previous);
            counter++;
        }
        return output.Take(size).ToArray();
    }

    public static string ToBase64(byte[] bytes) => Convert.ToBase64String(bytes);

    public static byte[] FromBase64(string value) => Convert.FromBase64String(value);

    public static string ComputePin(X509Certificate2 certificate)
    {
        var spki = certificate.GetECDsaPublicKey()?.ExportSubjectPublicKeyInfo()
                  ?? certificate.GetRSAPublicKey()?.ExportSubjectPublicKeyInfo()
                  ?? throw new InvalidOperationException("无法读取证书公钥。");
        var hash = Sha256(spki);
        return $"sha256/{ToBase64(hash)}";
    }
}







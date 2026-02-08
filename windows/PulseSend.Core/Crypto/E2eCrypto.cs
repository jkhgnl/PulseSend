using System.Security.Cryptography;
using NSec.Cryptography;

namespace PulseSend.Core.Crypto;

public sealed class X25519KeyPair
{
    public required Key PrivateKey { get; init; }
    public required byte[] PublicKeyRaw { get; init; }
    public byte[] PublicKeySpki => SpkiUtils.EncodeX25519PublicKey(PublicKeyRaw);
}

public static class E2eCrypto
{
    public static X25519KeyPair GenerateKeyPair()
    {
        var algorithm = KeyAgreementAlgorithm.X25519;
        var key = new Key(algorithm, new KeyCreationParameters
        {
            ExportPolicy = KeyExportPolicies.AllowPlaintextExport
        });
        var rawPublic = key.PublicKey.Export(KeyBlobFormat.RawPublicKey);
        return new X25519KeyPair
        {
            PrivateKey = key,
            PublicKeyRaw = rawPublic
        };
    }

    public static byte[] DeriveSessionKey(Key privateKey, byte[] peerPublicRaw, byte[] salt, byte[] info)
    {
        var algorithm = KeyAgreementAlgorithm.X25519;
        var peerKey = PublicKey.Import(algorithm, peerPublicRaw, KeyBlobFormat.RawPublicKey);
        using var shared = algorithm.Agree(privateKey, peerKey);
        var creation = new KeyCreationParameters
        {
            ExportPolicy = KeyExportPolicies.AllowPlaintextExport
        };
        using var derivedKey = KeyDerivationAlgorithm.HkdfSha256.DeriveKey(
            shared,
            salt,
            info,
            AeadAlgorithm.ChaCha20Poly1305,
            ref creation);
        return derivedKey.Export(KeyBlobFormat.RawSymmetricKey);
    }

    public static byte[] Encrypt(byte[] keyBytes, byte[] nonce, byte[] aad, byte[] plaintext)
    {
        var algorithm = AeadAlgorithm.ChaCha20Poly1305;
        var creation = new KeyCreationParameters
        {
            ExportPolicy = KeyExportPolicies.AllowPlaintextExport
        };
        using var key = Key.Import(algorithm, keyBytes, KeyBlobFormat.RawSymmetricKey, ref creation);
        var ciphertext = new byte[plaintext.Length + algorithm.TagSize];
        algorithm.Encrypt(key, nonce, aad, plaintext, ciphertext);
        return ciphertext;
    }

    public static byte[] Decrypt(byte[] keyBytes, byte[] nonce, byte[] aad, byte[] ciphertext)
    {
        var algorithm = AeadAlgorithm.ChaCha20Poly1305;
        var creation = new KeyCreationParameters
        {
            ExportPolicy = KeyExportPolicies.AllowPlaintextExport
        };
        using var key = Key.Import(algorithm, keyBytes, KeyBlobFormat.RawSymmetricKey, ref creation);
        var plaintext = new byte[ciphertext.Length - algorithm.TagSize];
        if (!algorithm.Decrypt(key, nonce, aad, ciphertext, plaintext))
        {
            throw new CryptographicException("解密失败：认证标签不匹配。");
        }
        return plaintext;
    }
}







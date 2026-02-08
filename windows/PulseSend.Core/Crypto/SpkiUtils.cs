using System.Formats.Asn1;

namespace PulseSend.Core.Crypto;

public static class SpkiUtils
{
    private const string X25519Oid = "1.3.101.110";

    public static byte[] EncodeX25519PublicKey(byte[] rawPublicKey)
    {
        var writer = new AsnWriter(AsnEncodingRules.DER);
        writer.PushSequence();
        writer.PushSequence();
        writer.WriteObjectIdentifier(X25519Oid);
        writer.PopSequence();
        writer.WriteBitString(rawPublicKey);
        writer.PopSequence();
        return writer.Encode();
    }

    public static byte[] DecodeX25519PublicKey(byte[] spki)
    {
        var reader = new AsnReader(spki, AsnEncodingRules.DER);
        var sequence = reader.ReadSequence();
        var algorithm = sequence.ReadSequence();
        var oid = algorithm.ReadObjectIdentifier();
        if (oid != X25519Oid)
        {
            throw new InvalidOperationException("不支持的公钥算法。");
        }
        var rawKey = sequence.ReadBitString(out _);
        return rawKey;
    }
}




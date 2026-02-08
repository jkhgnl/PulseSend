using System.Net;
using System.Net.Sockets;

namespace PulseSend.Core.Network;

internal static class UrlUtils
{
    public static string BuildHttpsUrl(string address, int port, string path)
    {
        var host = NormalizeHost(address);
        return $"https://{host}:{port}{path}";
    }

    public static string NormalizeHost(string address)
    {
        if (string.IsNullOrWhiteSpace(address))
        {
            throw new InvalidOperationException("设备地址为空。");
        }

        if (address.StartsWith("[", StringComparison.Ordinal) &&
            address.EndsWith("]", StringComparison.Ordinal))
        {
            return address;
        }

        if (IPAddress.TryParse(address, out var ip))
        {
            if (ip.AddressFamily == AddressFamily.InterNetworkV6)
            {
                if (ip.IsIPv4MappedToIPv6)
                {
                    return ip.MapToIPv4().ToString();
                }
                var text = ip.ToString();
                if (text.Contains('%'))
                {
                    text = text.Replace("%", "%25", StringComparison.Ordinal);
                }
                return $"[{text}]";
            }
            return ip.ToString();
        }

        return address;
    }
}

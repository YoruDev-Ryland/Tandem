using System.Net;

namespace Tandem.Server.Http;

/// <summary>Parses KNOWN_PROXIES: a comma-separated list of IP addresses (each its own /32 or /128) and CIDR networks.</summary>
public static class KnownProxies
{
    public static List<IPNetwork> Parse(string? value)
    {
        var networks = new List<IPNetwork>();
        foreach (var raw in (value ?? "").Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            if (raw.Contains('/')) networks.Add(IPNetwork.TryParse(raw, out var network)
                ? network
                : throw new InvalidOperationException($"KNOWN_PROXIES: '{raw}' is not a CIDR network."));
            else if (IPAddress.TryParse(raw, out var address))
                networks.Add(new IPNetwork(address, address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork ? 32 : 128));
            else throw new InvalidOperationException($"KNOWN_PROXIES: '{raw}' is not an IP address or CIDR network.");
        }
        return networks;
    }
}

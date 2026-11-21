using System.Globalization;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using Tandem.Protocol;

namespace Tandem.Server.Tests.Infrastructure;

public static class Fixtures
{
    public static string Sha256(byte[] bytes) => Convert.ToHexStringLower(SHA256.HashData(bytes));

    /// <summary>Uploads each file's blob with <paramref name="device"/> and returns the manifest.</summary>
    public static async Task<List<ManifestEntry>> Manifest(HttpClient device, params (string path, byte[] bytes)[] files)
    {
        var entries = new List<ManifestEntry>();
        foreach (var (path, bytes) in files)
        {
            var hash = Sha256(bytes);
            var content = new ByteArrayContent(bytes);
            content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            (await device.PutAsync($"/v1/blobs/{hash}", content)).EnsureSuccessStatusCode();
            entries.Add(new ManifestEntry(path, hash, bytes.Length));
        }
        return entries;
    }

    /// <summary>Independent implementation of the manifest hash format (sorted ordinal by path).</summary>
    public static string ManifestHash(IEnumerable<ManifestEntry> manifest)
    {
        var sb = new StringBuilder();
        foreach (var e in manifest.OrderBy(e => e.Path, StringComparer.Ordinal))
            sb.Append(e.Path).Append('\0').Append(e.Hash).Append('\0')
                .Append(e.Size.ToString(CultureInfo.InvariantCulture)).Append('\n');
        return Sha256(Encoding.UTF8.GetBytes(sb.ToString()));
    }
}

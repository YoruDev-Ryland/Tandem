using System.IO.Compression;
using System.Text;
using EmuSync.Protocol;
using EmuSync.Server.Blobs;
using EmuSync.Server.Http;

namespace EmuSync.Server.Web;

/// <summary>One file to place in a zip: where it goes and which blob holds its bytes.</summary>
public sealed record ZipItem(string EntryName, string Hash);

/// <summary>Streams zips straight to an output stream (nothing is buffered whole in memory) and cleans entry names.</summary>
public static class ZipExport
{
    private static readonly char[] Unsafe = ['<', '>', ':', '"', '|', '?', '*', '\\', '/'];

    /// <summary>A single path segment with every unsafe character replaced; never empty, ".", or "..".</summary>
    public static string SanitizeSegment(string? segment)
    {
        var sb = new StringBuilder();
        foreach (var c in segment ?? "")
            sb.Append(char.IsControl(c) || Array.IndexOf(Unsafe, c) >= 0 ? '_' : c);
        var s = sb.ToString().Trim().TrimEnd('.', ' ');
        return s.Length == 0 ? "_" : s.Length > 150 ? s[..150] : s;
    }

    /// <summary>Sanitizes each '/' (or '\') separated segment, dropping empty and dot segments' meaning (they become "_").</summary>
    public static string SanitizePath(string? path) =>
        string.Join('/', (path ?? "").Split(['/', '\\'], StringSplitOptions.RemoveEmptyEntries).Select(SanitizeSegment));

    public static string FileName(string path) => path[(path.LastIndexOf('/') + 1)..];

    /// <summary>Throws 404 if a blob is missing, before any byte is written.</summary>
    public static async Task RequireBlobsAsync(IBlobStore store, Guid userId, IEnumerable<ZipItem> items, CancellationToken ct)
    {
        foreach (var hash in items.Select(i => i.Hash).Distinct())
            if (!await store.ExistsAsync(userId, hash, ct))
                throw new ApiException(404, "blob_not_found", "A file of this version is missing from storage.");
    }

    public static async Task WriteAsync(Stream output, Guid userId, IEnumerable<ZipItem> items, IBlobStore store, CancellationToken ct, bool tolerateMissing = false)
    {
        var missing = new List<string>();
        var archive = await ZipArchive.CreateAsync(new AsyncFriendlyStream(output), ZipArchiveMode.Create, leaveOpen: true, entryNameEncoding: null);
        await using (archive)
        {
            var used = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var item in items)
            {
                var name = item.EntryName;
                for (var n = 2; !used.Add(name); n++)
                {
                    var dot = item.EntryName.LastIndexOf('.');
                    var slash = item.EntryName.LastIndexOf('/');
                    name = dot > slash + 1
                        ? $"{item.EntryName[..dot]} ({n}){item.EntryName[dot..]}"
                        : $"{item.EntryName} ({n})";
                }
                var opened = await store.OpenAsync(userId, item.Hash, ct);
                if (opened is null)
                {
                    if (!tolerateMissing) throw new IOException("A blob went missing while the zip was streaming.");
                    used.Remove(name);
                    missing.Add($"{item.EntryName}  {item.Hash}");
                    continue;
                }
                await using var source = opened;
                var entry = archive.CreateEntry(name, CompressionLevel.Fastest);
                await using var target = await entry.OpenAsync(ct);
                await source.CopyToAsync(target, ct);
            }
            if (missing.Count > 0)
            {
                var note = archive.CreateEntry("MISSING.txt", CompressionLevel.Fastest);
                await using var noteStream = await note.OpenAsync(ct);
                await noteStream.WriteAsync(Encoding.UTF8.GetBytes(string.Join('\n', missing) + "\n"), ct);
            }
        }
    }

    /// <summary>
    /// ZipArchive closes each entry's deflate stream synchronously (a small final flush). Kestrel forbids sync writes
    /// on the response, so those few writes are forwarded as blocking async ones; bulk data still goes through
    /// <see cref="WriteAsync(ReadOnlyMemory{byte}, CancellationToken)"/>.
    /// </summary>
    private sealed class AsyncFriendlyStream(Stream inner) : Stream
    {
        public override bool CanRead => false;
        public override bool CanSeek => false;
        public override bool CanWrite => true;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() => inner.FlushAsync().GetAwaiter().GetResult();
        public override Task FlushAsync(CancellationToken ct) => inner.FlushAsync(ct);
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) =>
            inner.WriteAsync(buffer.AsMemory(offset, count)).AsTask().GetAwaiter().GetResult();
        public override void Write(ReadOnlySpan<byte> buffer) =>
            inner.WriteAsync(buffer.ToArray()).AsTask().GetAwaiter().GetResult();
        public override ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken ct = default) => inner.WriteAsync(buffer, ct);
        public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
            inner.WriteAsync(buffer.AsMemory(offset, count), ct).AsTask();
    }
}

using System.Security.Cryptography;
using Tandem.Protocol;
using Tandem.Server.Auth;
using Tandem.Server.Blobs;
using Tandem.Server.Data;
using Tandem.Server.Http;
using Tandem.Server.Saves;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.WebUtilities;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Microsoft.Net.Http.Headers;

namespace Tandem.Server.Web;

public static class WebEndpoints
{
    public const int MaxFieldLength = 1000;

    public static IEndpointRouteBuilder MapWebEndpoints(this IEndpointRouteBuilder app)
    {
        var web = app.MapGroup("/v1/web").RequireAuthorization();

        web.MapGet("/home", (HttpContext http, WebQueryService q, CancellationToken ct) =>
            q.HomeAsync(http.User.UserId(), http.User.DeviceId(), ct));

        web.MapGet("/games", (HttpContext http, WebQueryService svc, string? emulator, string? q, CancellationToken ct) =>
            svc.GamesAsync(http.User.UserId(), emulator, q, ct));

        // gameKey may contain '/', so it is a catch-all. Routing decodes everything except an encoded slash (%2F), which
        // stays literal; turn just that back into '/' (a full second decode would corrupt keys containing '%').
        web.MapGet("/games/{emulatorId}/{*gameKey}", (HttpContext http, WebQueryService q, string emulatorId, string gameKey, CancellationToken ct) =>
            q.GameAsync(http.User.UserId(), emulatorId, gameKey.Replace("%2F", "/", StringComparison.OrdinalIgnoreCase), ct));

        web.MapGet("/devices/{id:guid}", (HttpContext http, WebQueryService q, Guid id, CancellationToken ct) =>
            q.DeviceAsync(http.User.UserId(), id, http.User.DeviceId(), ct));

        web.MapGet("/devices/{id:guid}/setup", (HttpContext http, WebQueryService q, Guid id, CancellationToken ct) =>
            q.SetupAsync(http.User.UserId(), id, ct));

        web.MapPost("/devices/{id:guid}/setup/keep-own/{emulatorId}", async (HttpContext http, WebQueryService q, Guid id, string emulatorId, CancellationToken ct) =>
        {
            await q.KeepOwnAsync(http.User.UserId(), id, emulatorId, ct);
            return Results.NoContent();
        });

        web.MapGet("/conflicts", (HttpContext http, ConflictQueries q, CancellationToken ct) =>
            q.ListAsync(http.User.UserId(), ct));

        web.MapGet("/configs", (HttpContext http, WebQueryService q, CancellationToken ct) =>
            q.ConfigsAsync(http.User.UserId(), ct));

        web.MapPost("/saves/upload", UploadAsync).DisableAntiforgery();

        app.MapGet("/v1/saves/versions/{versionId:guid}/download", DownloadAsync).RequireAuthorization();
        app.MapGet("/v1/export.zip", ExportAsync).RequireAuthorization();
        return app;
    }

    // ---- downloads ----

    private static async Task<IResult> DownloadAsync(HttpContext http, TandemDbContext db, IBlobStore store, Guid versionId, CancellationToken ct)
    {
        var userId = http.User.UserId();
        var row = await (from v in db.SaveVersions.AsNoTracking()
                join i in db.SaveItems.AsNoTracking() on v.ItemId equals i.Id
                where v.Id == versionId && i.UserId == userId
                select new { v.ManifestJson, v.Seq, i.GameKey }).FirstOrDefaultAsync(ct)
            ?? throw new ApiException(404, "version_not_found", "Version not found.");
        var manifest = Manifests.FromJson(row.ManifestJson);

        if (manifest.Count == 1)
        {
            var entry = manifest[0];
            var stream = await store.OpenAsync(userId, entry.Hash, ct)
                ?? throw new ApiException(404, "blob_not_found", "The file is missing from storage.");
            return Results.File(stream, "application/octet-stream", ZipExport.FileName(entry.Path));
        }

        var items = manifest.OrderBy(e => e.Path, StringComparer.Ordinal).Select(e => new ZipItem(e.Path, e.Hash)).ToList();
        await ZipExport.RequireBlobsAsync(store, userId, items, ct);
        var name = $"{ZipExport.SanitizeSegment(ZipExport.FileName(row.GameKey))}-v{row.Seq}.zip";
        return Results.Stream(s => ZipExport.WriteAsync(s, userId, items, store, ct), "application/zip", name);
    }

    private static async Task<IResult> ExportAsync(HttpContext http, TandemDbContext db, IBlobStore store, string? emulator, CancellationToken ct)
    {
        var userId = http.User.UserId();
        if (!string.IsNullOrEmpty(emulator) && !EmulatorIds.IsKnown(emulator))
            throw new ApiException(400, "unknown_emulator", $"Unknown emulator '{emulator}'.");
        var rows = await (from i in db.SaveItems.AsNoTracking()
                join v in db.SaveVersions.AsNoTracking() on i.HeadVersionId equals v.Id
                where i.UserId == userId && (string.IsNullOrEmpty(emulator) || i.EmulatorId == emulator)
                orderby i.EmulatorId, i.GameKey, i.Kind, i.Slot, i.Core
                select new { i.EmulatorId, i.GameKey, i.Kind, i.Slot, i.Core, v.ManifestJson }).ToListAsync(ct);

        var items = new List<ZipItem>();
        foreach (var r in rows)
        {
            var kind = r.Kind == ItemKind.Save ? "save" : "state";
            var slot = ZipExport.SanitizePath(r.Slot.Length == 0 ? "default" : r.Slot);
            if (r.Kind == ItemKind.State && r.Core.Length > 0) slot = $"{slot}/{ZipExport.SanitizePath(r.Core)}";
            var prefix = $"{ZipExport.SanitizePath(r.EmulatorId)}/{ZipExport.SanitizePath(r.GameKey)}/{kind}/{slot}/";
            foreach (var e in Manifests.FromJson(r.ManifestJson).OrderBy(e => e.Path, StringComparer.Ordinal))
                items.Add(new ZipItem(prefix + ZipExport.SanitizePath(e.Path), e.Hash));
        }
        var name = string.IsNullOrEmpty(emulator) ? "tandem-export.zip" : $"tandem-{emulator}.zip";
        return Results.Stream(s => ZipExport.WriteAsync(s, userId, items, store, ct, tolerateMissing: true), "application/zip", name);
    }

    // ---- upload from a PC ----

    private static async Task<IResult> UploadAsync(HttpContext http, SaveService saves, IBlobStore store, BlobService blobs,
        IOptions<ServerOptions> options, CancellationToken ct)
    {
        var userId = http.User.UserId();
        var max = options.Value.MaxBlobBytes;
        var totalCap = options.Value.MaxUploadBytes;
        long totalSpooled = 0;
        if (!http.Request.HasFormContentType || !MediaTypeHeaderValue.TryParse(http.Request.ContentType, out var mt)
            || string.IsNullOrEmpty(HeaderUtilities.RemoveQuotes(mt.Boundary).Value))
            throw new ApiException(400, "bad_request", "A multipart/form-data body is required.");

        // Each file is limited on its own, so lift the whole-request cap.
        var limit = http.Features.Get<IHttpMaxRequestBodySizeFeature>();
        if (limit is { IsReadOnly: false }) limit.MaxRequestBodySize = null;

        var tmpDir = Path.Combine(options.Value.DataDirectory, "tmp");
        Directory.CreateDirectory(tmpDir);
        var fields = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var spooled = new List<(string Path, string Tmp, string Hash, long Size)>();
        try
        {
            var reader = new MultipartReader(HeaderUtilities.RemoveQuotes(mt.Boundary).Value!, http.Request.Body);
            try
            {
                while (await reader.ReadNextSectionAsync(ct) is { } section)
                {
                    if (section.AsFileSection() is { } file)
                    {
                        if (spooled.Count >= Manifests.MaxEntries)
                            throw new ApiException(400, "bad_manifest", $"At most {Manifests.MaxEntries} files per upload.");
                        var tmp = Path.Combine(tmpDir, $"upload-{Guid.NewGuid():N}");
                        spooled.Add((file.FileName ?? "", tmp, "", 0)); // registered first so the finally cleans it up
                        var (hash, size) = await SpoolAsync(file.FileStream!, tmp, max, totalCap - totalSpooled, ct);
                        totalSpooled += size;
                        spooled[^1] = (file.FileName ?? "", tmp, hash, size);
                    }
                    else if (section.AsFormDataSection() is { } field)
                    {
                        var value = await field.GetValueAsync(ct);
                        if (value.Length > MaxFieldLength) throw new ApiException(400, "bad_request", $"Field '{field.Name}' is too long.");
                        fields[field.Name] = value;
                    }
                }
            }
            catch (BadHttpRequestException ex) when (ex.StatusCode == StatusCodes.Status413PayloadTooLarge)
            {
                throw TooLarge();
            }
            catch (InvalidDataException)
            {
                throw new ApiException(400, "bad_request", "The multipart body is malformed.");
            }

            var emulatorId = fields.GetValueOrDefault("emulatorId") ?? "";
            if (!EmulatorIds.IsKnown(emulatorId))
                throw new ApiException(400, "unknown_emulator", $"Unknown emulator '{emulatorId}'.");
            if (!Enum.TryParse<ItemKind>(fields.GetValueOrDefault("kind"), ignoreCase: true, out var kind) || !Enum.IsDefined(kind))
                throw new ApiException(400, "bad_request", "kind must be Save or State.");
            var gameKey = fields.GetValueOrDefault("gameKey") ?? "";
            var slot = fields.GetValueOrDefault("slot") ?? "";
            var core = fields.GetValueOrDefault("core") ?? "";
            if (spooled.Count == 0) throw new ApiException(400, "bad_request", "At least one file is required.");

            var manifest = Manifests.Normalize(spooled.Select(f => new ManifestEntry(f.Path, f.Hash, f.Size)).ToList());

            var (head, openConflict) = await saves.FindHeadAsync(userId, emulatorId, kind, gameKey, slot, core, ct);
            if (openConflict)
                throw new ApiException(409, "conflict_open", "Resolve the open conflict before uploading.");

            foreach (var f in spooled)
            {
                await using var content = new FileStream(f.Tmp, FileMode.Open, FileAccess.Read, FileShare.Read, 81920, FileOptions.Asynchronous | FileOptions.SequentialScan);
                await store.PutAsync(userId, f.Hash, content, ct);
                await blobs.ConfirmAsync(userId, f.Hash, f.Size, ct);
            }
            return Results.Ok(await saves.UploadAsync(userId, null,
                new SaveUploadRequest(emulatorId, kind, gameKey, slot, core, head, manifest, null), ct));
        }
        finally
        {
            foreach (var f in spooled)
            {
                try { File.Delete(f.Tmp); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            }
        }
    }

    /// <summary>Copies the part to a temp file while hashing it, refusing anything over <paramref name="max"/>.</summary>
    private static async Task<(string Hash, long Size)> SpoolAsync(Stream source, string tmp, long max, long remaining, CancellationToken ct)
    {
        using var hasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        await using var file = new FileStream(tmp, FileMode.CreateNew, FileAccess.Write, FileShare.None, 81920, FileOptions.Asynchronous);
        var buffer = new byte[81920];
        long total = 0;
        int read;
        while ((read = await source.ReadAsync(buffer, ct)) > 0)
        {
            total += read;
            if (total > max || total > remaining) throw TooLarge();
            hasher.AppendData(buffer, 0, read);
            await file.WriteAsync(buffer.AsMemory(0, read), ct);
        }
        return (Convert.ToHexStringLower(hasher.GetHashAndReset()), total);
    }

    /// <summary>Deletes upload temp files left behind (crash, kill) that are over an hour old.</summary>
    public static void SweepStaleUploads(string dataDirectory)
    {
        var dir = Path.Combine(dataDirectory, "tmp");
        if (!Directory.Exists(dir)) return;
        var cutoff = DateTime.UtcNow.AddHours(-1);
        foreach (var f in Directory.EnumerateFiles(dir, "upload-*"))
        {
            try { if (File.GetLastWriteTimeUtc(f) < cutoff) File.Delete(f); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    private static ApiException TooLarge() => new(413, "too_large", "A file exceeds the maximum allowed size.");
}

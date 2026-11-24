using System.IO.Compression;
using System.Net;
using System.Text;
using Tandem.Protocol;
using Tandem.Server.Data;
using Tandem.Server.Retention;
using Tandem.Server.Saves;
using Tandem.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Tandem.Server.Tests.Saves;

/// <summary>Lost &amp; found: a deleted game's saves are kept, listed, downloadable, restorable and deletable for good.</summary>
public class LostAndFoundTests
{
    private const string Game = "retroarch/Pokemon - Emerald Version";
    private const string GamePath = "/v1/web/games/retroarch/retroarch%2FPokemon%20-%20Emerald%20Version";

    private static ServerFactory NewFactory()
    {
        var f = new ServerFactory();
        f.Settings["Tandem:AllowRegistration"] = "true";
        return f;
    }

    private static async Task<T> InDb<T>(ServerFactory f, Func<TandemDbContext, Task<T>> query)
    {
        using var scope = f.Services.CreateScope();
        return await query(scope.ServiceProvider.GetRequiredService<TandemDbContext>());
    }

    private static async Task<SaveUploadResponse> Upload(HttpClient device, ItemKind kind, string content, Guid? baseId,
        string slot = "", string core = "", string game = Game, string? name = "Pokemon Emerald", params (string Path, string Content)[] files)
    {
        var list = files.Length > 0 ? files : [("data.srm", content)];
        var manifest = await Fixtures.Manifest(device, list.Select(x => (x.Path, Encoding.UTF8.GetBytes(x.Content))).ToArray());
        var r = await device.PostJsonAsync("/v1/saves/upload", new SaveUploadRequest("retroarch", kind, game, slot, core, baseId, manifest, name));
        Assert.True(r.IsSuccessStatusCode, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
        return await r.ReadAsync<SaveUploadResponse>();
    }

    private static async Task DeleteGame(HttpClient web) =>
        Assert.Equal(HttpStatusCode.NoContent, (await web.DeleteAsync(GamePath)).StatusCode);

    private static Task<List<LostItemRow>> Lost(HttpClient web) => web.GetOk<List<LostItemRow>>("/v1/web/lost");

    private static string BlobPath(ServerFactory f, Guid userId, string hash) =>
        Path.Combine(f.DataDirectory, "blobs", userId.ToString("N"), hash[..2], hash[2..4], hash);

    private static Task<string> HeadHash(ServerFactory f, Guid versionId) =>
        InDb(f, db => db.SaveVersions.Where(v => v.Id == versionId).Select(v => v.ManifestHash).SingleAsync());

    // ---- delete moves to Lost & found ----

    [Fact]
    public async Task Deleting_a_game_moves_the_head_of_each_item_to_lost_and_found()
    {
        using var f = NewFactory();
        var (a, key) = await f.Register("a@example.com", deviceName: "Thor");
        var v1 = await Upload(a, ItemKind.Save, "one", null);
        var v2 = await Upload(a, ItemKind.Save, "two", v1.VersionId);
        f.Time.Advance(TimeSpan.FromMinutes(1));
        var state = await Upload(a, ItemKind.State, "st", null, slot: "1", core: "mgba");
        // An item with no head (nothing to keep) is just deleted.
        await InDb(f, async db =>
        {
            db.SaveItems.Add(new SaveItem { Id = Guid.NewGuid(), UserId = key.UserId, EmulatorId = "retroarch", Kind = ItemKind.State, GameKey = Game, Slot = "2", Core = "mgba" });
            return await db.SaveChangesAsync();
        });
        var saveHash = await HeadHash(f, v2.VersionId);
        var stateHash = await HeadHash(f, state.VersionId);

        var web = await f.WebLogin("a@example.com");
        f.Time.Advance(TimeSpan.FromMinutes(5));
        await DeleteGame(web);

        var rows = await Lost(web);
        Assert.Equal(2, rows.Count);
        Assert.All(rows, r =>
        {
            Assert.Equal("retroarch", r.EmulatorId);
            Assert.Equal(Game, r.GameKey);
            Assert.Equal("Pokemon Emerald", r.DisplayName);
            Assert.Equal("game_deleted", r.Reason);
            Assert.Equal(f.Time.GetUtcNow(), r.LostUtc);
        });
        var save = Assert.Single(rows, r => r.Kind == ItemKind.Save);
        Assert.Equal(("", ""), (save.Slot, save.Core));
        Assert.Equal(3, save.TotalSize); // "two"
        var st = Assert.Single(rows, r => r.Kind == ItemKind.State);
        Assert.Equal(("1", "mgba"), (st.Slot, st.Core));

        var stored = await InDb(f, db => db.LostItems.AsNoTracking().ToListAsync());
        Assert.Equal(saveHash, stored.Single(l => l.Kind == ItemKind.Save).ManifestHash);
        Assert.Equal(stateHash, stored.Single(l => l.Kind == ItemKind.State).ManifestHash);
        Assert.All(stored, l => Assert.Equal(key.UserId, l.UserId));
        Assert.Equal(0, await InDb(f, db => db.SaveItems.CountAsync()));

        var activity = await InDb(f, db => db.ActivityEvents.SingleAsync(e => e.Kind == "game_deleted"));
        Assert.Contains("Lost & found", activity.Message);
    }

    [Fact]
    public async Task Lost_items_are_listed_newest_first()
    {
        using var f = NewFactory();
        var (a, _) = await f.Register("a@example.com");
        await Upload(a, ItemKind.Save, "one", null, game: "older", name: null);
        await Upload(a, ItemKind.Save, "two", null);
        var web = await f.WebLogin("a@example.com");
        Assert.Equal(HttpStatusCode.NoContent, (await web.DeleteAsync("/v1/web/games/retroarch/older")).StatusCode);
        f.Time.Advance(TimeSpan.FromHours(1));
        await DeleteGame(web);

        Assert.Equal([Game, "older"], (await Lost(web)).Select(r => r.GameKey));
    }

    // ---- blob GC keeps them ----

    [Fact]
    public async Task A_deleted_games_blobs_survive_a_prune_until_it_is_deleted_for_good()
    {
        using var f = NewFactory();
        var (a, key) = await f.Register("a@example.com");
        var v = await Upload(a, ItemKind.Save, "keep me", null);
        var hash = Manifests.FromJson(await InDb(f, db => db.SaveVersions.Where(x => x.Id == v.VersionId).Select(x => x.ManifestJson).SingleAsync()))
            .Single().Hash;
        var web = await f.WebLogin("a@example.com");
        await DeleteGame(web);

        f.Time.Advance(TimeSpan.FromHours(2)); // past the blob grace period
        await f.Services.GetRequiredService<Pruner>().RunOnceAsync(CancellationToken.None);
        Assert.True(File.Exists(BlobPath(f, key.UserId, hash)));
        Assert.True(await InDb(f, db => db.UserBlobs.AnyAsync(b => b.Hash == hash)));

        var lost = Assert.Single(await Lost(web));
        Assert.Equal(HttpStatusCode.NoContent, (await web.DeleteAsync($"/v1/web/lost/{lost.Id}")).StatusCode);
        Assert.Empty(await Lost(web));
        await f.Services.GetRequiredService<Pruner>().RunOnceAsync(CancellationToken.None);
        Assert.False(File.Exists(BlobPath(f, key.UserId, hash)));
        Assert.False(await InDb(f, db => db.UserBlobs.AnyAsync(b => b.Hash == hash)));
    }

    // ---- download ----

    [Fact]
    public async Task A_lost_item_downloads_as_its_file_or_as_a_zip()
    {
        using var f = NewFactory();
        var (a, _) = await f.Register("a@example.com");
        await Upload(a, ItemKind.Save, "single", null);
        await Upload(a, ItemKind.State, "", null, slot: "1", core: "mgba", files: [("a.state", "AAA"), ("sub/b.png", "BB")]);
        var web = await f.WebLogin("a@example.com");
        await DeleteGame(web);
        var rows = await Lost(web);

        var one = await web.GetAsync($"/v1/web/lost/{rows.Single(r => r.Kind == ItemKind.Save).Id}/download");
        Assert.Equal(HttpStatusCode.OK, one.StatusCode);
        Assert.Equal("single", await one.Content.ReadAsStringAsync());
        Assert.Equal("data.srm", one.Content.Headers.ContentDisposition?.FileName?.Trim('"'));

        var zip = await web.GetAsync($"/v1/web/lost/{rows.Single(r => r.Kind == ItemKind.State).Id}/download");
        Assert.Equal(HttpStatusCode.OK, zip.StatusCode);
        Assert.Equal("application/zip", zip.Content.Headers.ContentType?.MediaType);
        using var archive = new ZipArchive(new MemoryStream(await zip.Content.ReadAsByteArrayAsync()));
        Assert.Equal(["a.state", "sub/b.png"], archive.Entries.Select(e => e.FullName).Order(StringComparer.Ordinal));
    }

    // ---- restore ----

    [Fact]
    public async Task Restoring_when_the_game_is_gone_recreates_it_and_sends_it_to_devices()
    {
        using var f = NewFactory();
        var (a, _) = await f.Register("a@example.com", deviceName: "Thor");
        var (b, _) = await f.PairSecondDevice(a, "Odin");
        await b.Install(Game, "retroarch");
        var v = await Upload(a, ItemKind.Save, "precious", null);
        var hash = await HeadHash(f, v.VersionId);
        var web = await f.WebLogin("a@example.com");
        await DeleteGame(web);
        var lost = Assert.Single(await Lost(web));

        var r = await web.PostAsync($"/v1/web/lost/{lost.Id}/restore", null);
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        var restored = await r.ReadAsync<SaveUploadResponse>();
        Assert.Equal(SaveUploadStatus.Accepted, restored.Status);

        var item = await InDb(f, db => db.SaveItems.AsNoTracking().SingleAsync());
        Assert.Equal((Game, ItemKind.Save, "Pokemon Emerald"), (item.GameKey, item.Kind, item.DisplayName));
        Assert.Equal(restored.VersionId, item.HeadVersionId);
        Assert.Equal(hash, await HeadHash(f, restored.VersionId));
        Assert.Empty(await Lost(web));
        Assert.Contains(await b.PendingDeliveries(), d => d.Kind == DeliveryKind.Save && d.GameKey == Game);
        Assert.Contains(await InDb(f, db => db.ActivityEvents.Select(e => e.Message).ToListAsync()), m => m.Contains("Lost & found") && m.StartsWith("Restored"));
    }

    [Fact]
    public async Task Restoring_next_to_a_newer_save_opens_a_conflict_instead_of_overwriting_it()
    {
        using var f = NewFactory();
        var (a, _) = await f.Register("a@example.com", deviceName: "Thor");
        var old = await Upload(a, ItemKind.Save, "old", null);
        var oldHash = await HeadHash(f, old.VersionId);
        var web = await f.WebLogin("a@example.com");
        await DeleteGame(web);
        // The device re-seeds the game with what it has now.
        var newer = await Upload(a, ItemKind.Save, "newer", null);
        var lost = Assert.Single(await Lost(web));

        var restored = await (await web.PostAsync($"/v1/web/lost/{lost.Id}/restore", null)).ReadAsync<SaveUploadResponse>();

        Assert.Equal(SaveUploadStatus.Conflict, restored.Status);
        Assert.Equal(newer.VersionId, restored.HeadVersionId);
        var item = await InDb(f, db => db.SaveItems.AsNoTracking().SingleAsync());
        Assert.Equal(newer.VersionId, item.HeadVersionId); // the newer save stays head
        Assert.True(item.OpenConflict);
        var row = Assert.Single(await web.GetOk<List<ConflictRow>>("/v1/web/conflicts"));
        Assert.Equal(ConflictReason.Concurrent, row.Reason);
        Assert.Contains(row.Sides, s => s.VersionId == restored.VersionId && s.ManifestHash == oldHash && !s.IsHead);
        Assert.Empty(await Lost(web));
        Assert.Contains(await InDb(f, db => db.ActivityEvents.Where(e => e.Kind == "conflict_opened").Select(e => e.Message).ToListAsync()),
            m => m.Contains("Lost & found"));
    }

    [Fact]
    public async Task Restoring_what_the_game_already_holds_changes_nothing_and_clears_the_lost_item()
    {
        using var f = NewFactory();
        var (a, _) = await f.Register("a@example.com");
        await Upload(a, ItemKind.Save, "same", null);
        var web = await f.WebLogin("a@example.com");
        await DeleteGame(web);
        var again = await Upload(a, ItemKind.Save, "same", null);
        var lost = Assert.Single(await Lost(web));

        var restored = await (await web.PostAsync($"/v1/web/lost/{lost.Id}/restore", null)).ReadAsync<SaveUploadResponse>();

        Assert.Equal(SaveUploadStatus.Unchanged, restored.Status);
        Assert.Equal(again.VersionId, restored.VersionId);
        Assert.Equal(1, await InDb(f, db => db.SaveVersions.CountAsync()));
        Assert.Empty(await Lost(web));
    }

    [Fact]
    public async Task Restoring_onto_an_item_with_an_open_conflict_is_refused_and_keeps_the_lost_item()
    {
        using var f = NewFactory();
        var (a, _) = await f.Register("a@example.com", deviceName: "Thor");
        var (b, _) = await f.PairSecondDevice(a, "Odin");
        await Upload(a, ItemKind.Save, "lost", null);
        var web = await f.WebLogin("a@example.com");
        await DeleteGame(web);
        await Upload(a, ItemKind.Save, "thor", null);
        Assert.Equal(SaveUploadStatus.Conflict, (await Upload(b, ItemKind.Save, "odin", null)).Status);
        var lost = Assert.Single(await Lost(web));

        var r = await web.PostAsync($"/v1/web/lost/{lost.Id}/restore", null);

        Assert.Equal(HttpStatusCode.Conflict, r.StatusCode);
        Assert.Equal("conflict_open", (await r.ReadAsync<ErrorResponse>()).Code);
        Assert.Single(await Lost(web));
    }

    // ---- isolation ----

    [Fact]
    public async Task Another_users_lost_items_are_not_found()
    {
        using var f = NewFactory();
        var (a, _) = await f.Register("a@example.com");
        await Upload(a, ItemKind.Save, "mine", null);
        var web = await f.WebLogin("a@example.com");
        await DeleteGame(web);
        var lost = Assert.Single(await Lost(web));

        await f.Register("b@example.com");
        var other = await f.WebLogin("b@example.com");
        Assert.Empty(await Lost(other));
        Assert.Equal(HttpStatusCode.NotFound, (await other.GetAsync($"/v1/web/lost/{lost.Id}/download")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await other.PostAsync($"/v1/web/lost/{lost.Id}/restore", null)).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await other.DeleteAsync($"/v1/web/lost/{lost.Id}")).StatusCode);
        Assert.Equal(0, await InDb(f, db => db.SaveItems.CountAsync())); // the refused restore created nothing
        Assert.Single(await Lost(web));

        Assert.Equal(HttpStatusCode.NotFound, (await web.DeleteAsync($"/v1/web/lost/{Guid.NewGuid()}")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await web.PostAsync($"/v1/web/lost/{Guid.NewGuid()}/restore", null)).StatusCode);
    }

    [Fact]
    public async Task Lost_and_found_needs_a_web_session()
    {
        using var f = NewFactory();
        var (a, _) = await f.Register("a@example.com");
        Assert.Equal(HttpStatusCode.Forbidden, (await a.GetAsync("/v1/web/lost")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await f.Client().GetAsync("/v1/web/lost")).StatusCode);
    }
}

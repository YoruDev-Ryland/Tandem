using System.Collections.Concurrent;
using System.Net;
using System.Text;
using Tandem.Protocol;
using Tandem.Server.Data;
using Tandem.Server.Events;
using Tandem.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Testcontainers.PostgreSql;

namespace Tandem.Server.Tests;

public class SaveTests
{
    private const string Game = "0100F2C0115B6000";

    private static ServerFactory NewFactory()
    {
        var factory = new ServerFactory();
        factory.Settings["Tandem:AllowRegistration"] = "true";
        return factory;
    }

    private static SaveUploadRequest Req(IReadOnlyList<ManifestEntry> manifest, Guid? baseId, ItemKind kind = ItemKind.Save,
        string game = Game, string slot = "", string core = "", string emulator = "eden", string? displayName = null) =>
        new(emulator, kind, game, slot, core, baseId, manifest, displayName);

    private static Task<List<ManifestEntry>> Files(HttpClient device, string content, string path = "data.dat") =>
        Fixtures.Manifest(device, (path, Encoding.UTF8.GetBytes(content)));

    private static async Task<SaveUploadResponse> Upload(HttpClient device, SaveUploadRequest request)
    {
        var response = await device.PostJsonAsync("/v1/saves/upload", request);
        Assert.True(response.StatusCode == HttpStatusCode.OK, $"{response.StatusCode}: {await response.Content.ReadAsStringAsync()}");
        return await response.ReadAsync<SaveUploadResponse>();
    }

    private static Task<HttpResponseMessage> Resolve(HttpClient client, Guid itemId, ResolveChoice choice, Guid? keep) =>
        client.PostJsonAsync($"/v1/conflicts/{itemId}/resolve", new ResolveConflictRequest(choice, keep));

    private static async Task AssertError(HttpResponseMessage r, HttpStatusCode status, string code)
    {
        Assert.Equal(status, r.StatusCode);
        Assert.Equal(code, (await r.ReadAsync<ErrorResponse>()).Code);
    }

    private static async Task<T> InDb<T>(ServerFactory factory, Func<TandemDbContext, Task<T>> query)
    {
        using var scope = factory.Services.CreateScope();
        return await query(scope.ServiceProvider.GetRequiredService<TandemDbContext>());
    }

    private static Task<SaveItem> Item(ServerFactory f, Guid id) =>
        InDb(f, db => db.SaveItems.AsNoTracking().SingleAsync(i => i.Id == id));

    private static Task<List<SaveVersion>> Versions(ServerFactory f, Guid itemId) =>
        InDb(f, db => db.SaveVersions.AsNoTracking().Where(v => v.ItemId == itemId).OrderBy(v => v.Seq).ToListAsync());

    private static ConcurrentQueue<object> Capture(ServerFactory f)
    {
        var seen = new ConcurrentQueue<object>();
        var events = f.Services.GetRequiredService<IDomainEvents>();
        events.Subscribe<SaveHeadChanged>((e, _) => { seen.Enqueue(e); return Task.CompletedTask; });
        events.Subscribe<ConflictOpened>((e, _) => { seen.Enqueue(e); return Task.CompletedTask; });
        return seen;
    }

    [Fact]
    public async Task First_upload_is_accepted_seq_1()
    {
        using var factory = NewFactory();
        var (device, key) = await factory.Register("a@example.com");
        var seen = Capture(factory);
        var manifest = await Files(device, "v1");

        var r = await Upload(device, Req(manifest, null, displayName: "Zelda"));

        Assert.Equal(SaveUploadStatus.Accepted, r.Status);
        Assert.Equal(r.VersionId, r.HeadVersionId);
        var item = await Item(factory, r.ItemId);
        Assert.Equal(r.VersionId, item.HeadVersionId);
        Assert.False(item.OpenConflict);
        Assert.Equal("Zelda", item.DisplayName);
        var v = Assert.Single(await Versions(factory, r.ItemId));
        Assert.Equal(1, v.Seq);
        Assert.Equal(key.DeviceId, v.DeviceId);
        Assert.Null(v.ConflictReason);
        Assert.Null(v.BaseVersionId);
        Assert.Equal(factory.Time.GetUtcNow(), v.ReceivedUtc);
        Assert.Equal(Fixtures.ManifestHash(manifest), v.ManifestHash);
        Assert.Equal(2, v.TotalSize);
        var state = await InDb(factory, db => db.DeviceItemStates.SingleAsync(s => s.DeviceId == key.DeviceId && s.ItemId == r.ItemId));
        Assert.Equal(r.VersionId, state.KnownVersionId);
        Assert.Equal(new SaveHeadChanged(key.UserId, r.ItemId, r.VersionId), Assert.Single(seen));
    }

    [Fact]
    public async Task Upload_based_on_head_becomes_new_head()
    {
        using var factory = NewFactory();
        var (device, key) = await factory.Register("a@example.com");
        var first = await Upload(device, Req(await Files(device, "v1"), null));

        var second = await Upload(device, Req(await Files(device, "v2"), first.VersionId));

        Assert.Equal(SaveUploadStatus.Accepted, second.Status);
        Assert.Equal(first.ItemId, second.ItemId);
        Assert.Equal(second.VersionId, second.HeadVersionId);
        Assert.Equal(second.VersionId, (await Item(factory, first.ItemId)).HeadVersionId);
        var versions = await Versions(factory, first.ItemId);
        Assert.Equal([1, 2], versions.Select(v => v.Seq));
        Assert.Equal(first.VersionId, versions[1].BaseVersionId);
        Assert.Equal(second.VersionId, await InDb(factory, db => db.DeviceItemStates
            .Where(s => s.DeviceId == key.DeviceId).Select(s => s.KnownVersionId).SingleAsync()));
    }

    [Fact]
    public async Task Identical_reupload_is_Unchanged_and_creates_no_version()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        var bytes1 = Encoding.UTF8.GetBytes("one");
        var bytes2 = Encoding.UTF8.GetBytes("two");
        var manifest = await Fixtures.Manifest(a, ("x/1.dat", bytes1), ("a/2.dat", bytes2));
        var first = await Upload(a, Req(manifest, null));
        var seen = Capture(factory);

        // Another device with the same content, entries in another order, and no base: still Unchanged.
        var reordered = manifest.AsEnumerable().Reverse().ToList();
        var r = await Upload(b, Req(reordered, null, displayName: "Renamed"));

        Assert.Equal(SaveUploadStatus.Unchanged, r.Status);
        Assert.Equal(first.VersionId, r.VersionId);
        Assert.Equal(first.VersionId, r.HeadVersionId);
        Assert.Single(await Versions(factory, first.ItemId));
        Assert.Equal("Renamed", (await Item(factory, first.ItemId)).DisplayName);
        Assert.Equal(first.VersionId, await InDb(factory, db => db.DeviceItemStates
            .Where(s => s.DeviceId == bKey.DeviceId && s.ItemId == first.ItemId).Select(s => s.KnownVersionId).SingleAsync()));
        Assert.Empty(seen);
    }

    [Fact]
    public async Task Stale_base_is_Concurrent_conflict_and_head_unchanged()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        var seen = Capture(factory);

        var r = await Upload(b, Req(await Files(b, "b-edit"), v1.VersionId));

        Assert.Equal(SaveUploadStatus.Conflict, r.Status);
        Assert.Equal(v2.VersionId, r.HeadVersionId);
        Assert.NotEqual(v2.VersionId, r.VersionId);
        var item = await Item(factory, v1.ItemId);
        Assert.Equal(v2.VersionId, item.HeadVersionId);
        Assert.True(item.OpenConflict);
        var conflict = (await Versions(factory, v1.ItemId)).Single(v => v.Id == r.VersionId);
        Assert.Equal(ConflictReason.Concurrent, conflict.ConflictReason);
        Assert.Equal(3, conflict.Seq);
        Assert.False(conflict.Resolved);
        Assert.Equal(new ConflictOpened(bKey.UserId, v1.ItemId, r.VersionId, ConflictReason.Concurrent), Assert.Single(seen));
        // The conflicting device's known version is not moved.
        Assert.False(await InDb(factory, db => db.DeviceItemStates.AnyAsync(s => s.DeviceId == bKey.DeviceId)));

        // An unknown base is treated the same way.
        var unknown = await Upload(b, Req(await Files(b, "b-other"), Guid.NewGuid()));
        Assert.Equal(SaveUploadStatus.Conflict, unknown.Status);
        Assert.Equal(ConflictReason.Concurrent, (await Versions(factory, v1.ItemId)).Single(v => v.Id == unknown.VersionId).ConflictReason);
        Assert.Equal(v2.VersionId, (await Item(factory, v1.ItemId)).HeadVersionId);
    }

    [Fact]
    public async Task Null_base_against_existing_head_is_FirstSync_conflict()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        var v1 = await Upload(a, Req(await Files(a, "server"), null));
        var seen = Capture(factory);

        var r = await Upload(b, Req(await Files(b, "device"), null));

        Assert.Equal(SaveUploadStatus.Conflict, r.Status);
        Assert.Equal(v1.VersionId, r.HeadVersionId);
        var item = await Item(factory, v1.ItemId);
        Assert.Equal(v1.VersionId, item.HeadVersionId);
        Assert.True(item.OpenConflict);
        Assert.Equal(ConflictReason.FirstSync, (await Versions(factory, v1.ItemId)).Single(v => v.Id == r.VersionId).ConflictReason);
        Assert.Equal(new ConflictOpened(bKey.UserId, v1.ItemId, r.VersionId, ConflictReason.FirstSync), Assert.Single(seen));
    }

    [Fact]
    public async Task Reupload_of_open_conflict_returns_the_same_version()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        var v1 = await Upload(a, Req(await Files(a, "server"), null));
        var manifest = await Files(b, "device");
        var c1 = await Upload(b, Req(manifest, null));
        var seen = Capture(factory);

        var c2 = await Upload(b, Req(manifest, null));

        Assert.Equal(SaveUploadStatus.Conflict, c2.Status);
        Assert.Equal(c1.VersionId, c2.VersionId);
        Assert.Equal(2, (await Versions(factory, v1.ItemId)).Count);
        Assert.Empty(seen);

        // The same content from another device is a new version under the normal rules.
        var (c, _) = await factory.PairSecondDevice(a, "C");
        var c3 = await Upload(c, Req(manifest, null));
        Assert.Equal(SaveUploadStatus.Conflict, c3.Status);
        Assert.NotEqual(c1.VersionId, c3.VersionId);

        // The same content based on the head is not a retry: rule 5 makes it the new head.
        var onHead = await Upload(b, Req(manifest, v1.VersionId));
        Assert.Equal(SaveUploadStatus.Accepted, onHead.Status);
        Assert.NotEqual(c1.VersionId, onHead.VersionId);
        Assert.Equal(onHead.VersionId, (await Item(factory, v1.ItemId)).HeadVersionId);
        Assert.Equal(4, (await Versions(factory, v1.ItemId)).Count);
    }

    [Fact]
    public async Task Reupload_matching_old_history_is_a_new_version()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var m1 = await Files(a, "v1");
        var v1 = await Upload(a, Req(m1, null));
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));

        var v3 = await Upload(a, Req(m1, v2.VersionId));

        Assert.Equal(SaveUploadStatus.Accepted, v3.Status);
        Assert.NotEqual(v1.VersionId, v3.VersionId);
        Assert.Equal([1, 2, 3], (await Versions(factory, v1.ItemId)).Select(v => v.Seq));
        Assert.Equal(v3.VersionId, (await Item(factory, v1.ItemId)).HeadVersionId);
    }

    [Fact]
    public async Task Concurrent_uploads_same_base_yield_one_head_one_conflict()
    {
        using var factory = NewFactory();
        await RunConcurrentRounds(factory, 20);
    }

    [DockerFact]
    [Trait("Category", "Postgres")]
    public async Task Concurrent_uploads_on_postgres_yield_one_head_one_conflict()
    {
        await using var container = new PostgreSqlBuilder("postgres:17-alpine").Build();
        await container.StartAsync();
        using var factory = NewFactory();
        factory.Settings["Tandem:DatabaseProvider"] = "postgres";
        factory.Settings["Tandem:DatabaseConnection"] = container.GetConnectionString();

        await RunConcurrentRounds(factory, 20);
        await ResolveAndBulkScenario(factory);
    }

    private static async Task RunConcurrentRounds(ServerFactory factory, int rounds)
    {
        var (a, _) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        var start = await Upload(a, Req(await Files(a, "start"), null));
        var head = start.VersionId;
        var uploaded = new List<string> { Fixtures.ManifestHash(await Files(a, "start")) };

        for (var round = 0; round < rounds; round++)
        {
            var ma = await Files(a, $"a{round}");
            var mb = await Files(b, $"b{round}");
            uploaded.Add(Fixtures.ManifestHash(ma));
            uploaded.Add(Fixtures.ManifestHash(mb));
            var results = await Task.WhenAll(Upload(a, Req(ma, head)), Upload(b, Req(mb, head)));

            var accepted = Assert.Single(results, r => r.Status == SaveUploadStatus.Accepted);
            var conflict = Assert.Single(results, r => r.Status == SaveUploadStatus.Conflict);
            Assert.Equal(accepted.VersionId, conflict.HeadVersionId);
            Assert.Equal(accepted.VersionId, (await Item(factory, start.ItemId)).HeadVersionId);

            (await Resolve(a, start.ItemId, ResolveChoice.KeepVersion, accepted.VersionId)).EnsureSuccessStatusCode();
            head = accepted.VersionId;
        }

        var versions = await Versions(factory, start.ItemId);
        Assert.Equal(1 + 2 * rounds, versions.Count);
        Assert.Equal(versions.Count, versions.Select(v => v.Seq).Distinct().Count());
        Assert.Equal(Enumerable.Range(1, versions.Count), versions.Select(v => v.Seq));
        var item = await Item(factory, start.ItemId);
        Assert.Equal(head, item.HeadVersionId);
        Assert.Single(versions, v => v.Id == item.HeadVersionId);
        Assert.False(item.OpenConflict);
        Assert.Equal(uploaded.Order(), versions.Select(v => v.ManifestHash).Order());
        Assert.Equal(1, await InDb(factory, db => db.SaveItems.CountAsync()));
        Assert.Equal(rounds, versions.Count(v => v.ConflictReason == ConflictReason.Concurrent && v.Resolved));
    }

    private static async Task ResolveAndBulkScenario(ServerFactory factory)
    {
        var (a, _) = await factory.Register("pg@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        var s = await Upload(a, Req(await Files(a, "s"), null, game: "pg"));
        var c = await Upload(b, Req(await Files(b, "d"), null, game: "pg"));
        Assert.Equal(SaveUploadStatus.Conflict, c.Status);
        (await b.PostJsonAsync("/v1/conflicts/first-sync/resolve",
            new BulkFirstSyncRequest(bKey.DeviceId, FirstSyncChoice.UseDevice, null))).EnsureSuccessStatusCode();
        var item = await Item(factory, s.ItemId);
        Assert.Equal(c.VersionId, item.HeadVersionId);
        Assert.False(item.OpenConflict);
    }

    [Fact]
    public async Task States_with_different_core_are_different_items()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var m = await Files(a, "state");

        var mgba = await Upload(a, Req(m, null, ItemKind.State, "gba/Pokemon", "1", "mgba", "retroarch"));
        var vba = await Upload(a, Req(m, null, ItemKind.State, "gba/Pokemon", "1", "vbam", "retroarch"));
        var slot2 = await Upload(a, Req(m, null, ItemKind.State, "gba/Pokemon", "2", "mgba", "retroarch"));
        var save = await Upload(a, Req(m, null, ItemKind.Save, "gba/Pokemon", "", "mgba", "retroarch"));

        Assert.All([mgba, vba, slot2, save], r => Assert.Equal(SaveUploadStatus.Accepted, r.Status));
        Assert.Equal(4, new[] { mgba.ItemId, vba.ItemId, slot2.ItemId, save.ItemId }.Distinct().Count());
        Assert.Equal("vbam", (await Item(factory, vba.ItemId)).Core);
        Assert.Equal("vbam", Assert.Single(await Versions(factory, vba.ItemId)).Core);
    }

    [Fact]
    public async Task Save_core_is_recorded_on_version_not_identity()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");

        var v1 = await Upload(a, Req(await Files(a, "1"), null, game: "gba/Pokemon", core: "mgba", emulator: "retroarch"));
        var v2 = await Upload(b, Req(await Files(b, "2"), v1.VersionId, game: "gba/Pokemon", core: "vbam", emulator: "retroarch"));

        Assert.Equal(SaveUploadStatus.Accepted, v2.Status);
        Assert.Equal(v1.ItemId, v2.ItemId);
        Assert.Equal("", (await Item(factory, v1.ItemId)).Core);
        Assert.Equal(["mgba", "vbam"], (await Versions(factory, v1.ItemId)).Select(v => v.Core));
    }

    [Fact]
    public async Task Resolve_KeepVersion_makes_conflict_head_and_keeps_history()
    {
        using var factory = NewFactory();
        var (a, key) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        var c = await Upload(b, Req(await Files(b, "b"), v1.VersionId));
        var seen = Capture(factory);

        // Resolving works from a web session too.
        var web = await factory.WebLogin("a@example.com");
        var r = await Resolve(web, v1.ItemId, ResolveChoice.KeepVersion, c.VersionId);

        Assert.Equal(HttpStatusCode.NoContent, r.StatusCode);
        var item = await Item(factory, v1.ItemId);
        Assert.Equal(c.VersionId, item.HeadVersionId);
        Assert.False(item.OpenConflict);
        var versions = await Versions(factory, v1.ItemId);
        Assert.Equal(3, versions.Count);
        var conflict = versions.Single(v => v.Id == c.VersionId);
        Assert.Equal(ConflictReason.Concurrent, conflict.ConflictReason);
        Assert.True(conflict.Resolved);
        // Only the displaced head is kept aside, pinned and labelled.
        var displaced = versions.Single(v => v.Id == v2.VersionId);
        Assert.True(displaced.Pinned);
        Assert.NotNull(displaced.Variant);
        Assert.Equal(2, versions.Count(v => v.Variant is null));
        Assert.Equal(new SaveHeadChanged(key.UserId, v1.ItemId, c.VersionId), Assert.Single(seen));

        // Device B now bases on its own (head) version: accepted.
        var next = await Upload(b, Req(await Files(b, "b2"), c.VersionId));
        Assert.Equal(SaveUploadStatus.Accepted, next.Status);
    }

    [Fact]
    public async Task Resolve_errors()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        var (other, _) = await factory.Register("z@example.com");
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));

        await AssertError(await Resolve(a, v1.ItemId, ResolveChoice.KeepVersion, v1.VersionId), HttpStatusCode.Conflict, "no_conflict");
        await AssertError(await Resolve(a, Guid.NewGuid(), ResolveChoice.KeepVersion, v1.VersionId), HttpStatusCode.NotFound, "item_not_found");

        var c = await Upload(b, Req(await Files(b, "b"), null));
        await AssertError(await Resolve(other, v1.ItemId, ResolveChoice.KeepVersion, c.VersionId), HttpStatusCode.NotFound, "item_not_found");
        await AssertError(await Resolve(a, v1.ItemId, ResolveChoice.KeepVersion, Guid.NewGuid()), HttpStatusCode.BadRequest, "bad_version");
        await AssertError(await Resolve(a, v1.ItemId, ResolveChoice.KeepBoth, null), HttpStatusCode.BadRequest, "bad_version");
        var otherItem = await Upload(a, Req(await Files(a, "x"), null, game: "other"));
        await AssertError(await Resolve(a, v1.ItemId, ResolveChoice.KeepVersion, otherItem.VersionId), HttpStatusCode.BadRequest, "bad_version");

        Assert.True((await Item(factory, v1.ItemId)).OpenConflict);
        Assert.Equal(v1.VersionId, (await Item(factory, v1.ItemId)).HeadVersionId);
    }

    [Fact]
    public async Task Resolve_KeepBoth_labels_variant_and_pins()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com", deviceName: "Odin");
        var (b, _) = await factory.PairSecondDevice(a, "Retroid");
        var (c, _) = await factory.PairSecondDevice(a, "Ayn");
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        factory.Time.SetUtcNow(DateTimeOffset.Parse("2026-11-20T08:05:59Z"));
        var cb = await Upload(b, Req(await Files(b, "b"), null));
        factory.Time.SetUtcNow(DateTimeOffset.Parse("2026-11-21T23:59:00Z"));
        var cc = await Upload(c, Req(await Files(c, "c"), Guid.NewGuid()));

        // Keep the server head; both conflicting copies become pinned variants.
        (await Resolve(a, v1.ItemId, ResolveChoice.KeepBoth, v1.VersionId)).EnsureSuccessStatusCode();

        var item = await Item(factory, v1.ItemId);
        Assert.Equal(v1.VersionId, item.HeadVersionId);
        Assert.False(item.OpenConflict);
        var versions = await Versions(factory, v1.ItemId);
        var head = versions.Single(v => v.Id == v1.VersionId);
        Assert.Null(head.Variant);
        Assert.False(head.Pinned);
        var vb = versions.Single(v => v.Id == cb.VersionId);
        Assert.Equal("From Retroid", vb.Variant);
        Assert.True(vb.Pinned);
        Assert.True(vb.Resolved);
        var vc = versions.Single(v => v.Id == cc.VersionId);
        Assert.Equal("From Ayn", vc.Variant);
        Assert.True(vc.Pinned);

        // Keeping a conflict version as head labels the displaced head too, so neither is lost.
        factory.Time.SetUtcNow(DateTimeOffset.Parse("2026-11-22T10:00:00Z"));
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        var cb2 = await Upload(b, Req(await Files(b, "b2"), cb.VersionId));
        (await Resolve(b, v1.ItemId, ResolveChoice.KeepBoth, cb2.VersionId)).EnsureSuccessStatusCode();
        versions = await Versions(factory, v1.ItemId);
        Assert.Equal(cb2.VersionId, (await Item(factory, v1.ItemId)).HeadVersionId);
        Assert.Null(versions.Single(v => v.Id == cb2.VersionId).Variant);
        var displaced = versions.Single(v => v.Id == v2.VersionId);
        Assert.Equal("From Odin", displaced.Variant);
        Assert.True(displaced.Pinned);
    }

    [Fact]
    public async Task Bulk_first_sync_UseDevice_resolves_all_for_device()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        var (c, _) = await factory.PairSecondDevice(a, "C");
        var s1 = await Upload(a, Req(await Files(a, "s1"), null, game: "g1"));
        var s2 = await Upload(a, Req(await Files(a, "s2"), null, game: "g2"));
        var s3 = await Upload(a, Req(await Files(a, "s3"), null, game: "g3"));
        var d1 = await Upload(b, Req(await Files(b, "d1"), null, game: "g1"));
        var d2 = await Upload(b, Req(await Files(b, "d2"), null, game: "g2"));
        var concurrent = await Upload(b, Req(await Files(b, "d3"), Guid.NewGuid(), game: "g3"));
        var fromC = await Upload(c, Req(await Files(c, "c2"), null, game: "g2"));
        var seen = Capture(factory);

        var r = await b.PostJsonAsync("/v1/conflicts/first-sync/resolve",
            new BulkFirstSyncRequest(bKey.DeviceId, FirstSyncChoice.UseDevice, null));

        Assert.Equal(HttpStatusCode.NoContent, r.StatusCode);
        var i1 = await Item(factory, s1.ItemId);
        Assert.Equal(d1.VersionId, i1.HeadVersionId);
        Assert.False(i1.OpenConflict);
        var i2 = await Item(factory, s2.ItemId);
        Assert.Equal(d2.VersionId, i2.HeadVersionId);
        Assert.True(i2.OpenConflict); // device C's first-sync conflict is still open
        var v2 = await Versions(factory, s2.ItemId);
        Assert.True(v2.Single(v => v.Id == d2.VersionId).Resolved);
        Assert.False(v2.Single(v => v.Id == fromC.VersionId).Resolved);
        // A Concurrent conflict is not a first-sync conflict.
        var i3 = await Item(factory, s3.ItemId);
        Assert.Equal(s3.VersionId, i3.HeadVersionId);
        Assert.True(i3.OpenConflict);
        Assert.False((await Versions(factory, s3.ItemId)).Single(v => v.Id == concurrent.VersionId).Resolved);
        // Every version is kept, and the displaced server heads become pinned, labelled variants.
        var v1 = await Versions(factory, s1.ItemId);
        Assert.Equal(2, v1.Count);
        var displaced1 = v1.Single(v => v.Id == s1.VersionId);
        Assert.Equal("Test device 2026-11-19 12:00", displaced1.Variant);
        Assert.True(displaced1.Pinned);
        var displaced2 = (await Versions(factory, s2.ItemId)).Single(v => v.Id == s2.VersionId);
        Assert.Equal("Test device 2026-11-19 12:00", displaced2.Variant);
        Assert.True(displaced2.Pinned);
        Assert.Null(v1.Single(v => v.Id == d1.VersionId).Variant);
        Assert.Equal(
            new HashSet<object> { new SaveHeadChanged(bKey.UserId, s1.ItemId, d1.VersionId), new SaveHeadChanged(bKey.UserId, s2.ItemId, d2.VersionId) },
            seen.ToHashSet());
    }

    // Device-realistic follow-ups (I-2): a device that got Conflict keeps the base it had (the client only adopts a
    // version id on Accepted/Unchanged/Superseded), so its next upload names that old base, not the head id.

    [Fact]
    public async Task Resolve_winner_next_save_on_its_real_base_is_Accepted()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var bHas = (await Upload(b, Req(await Files(b, "v1"), null))).HeadVersionId; // Unchanged: B adopts v1
        Assert.Equal(v1.VersionId, bHas);
        await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        var c = await Upload(b, Req(await Files(b, "b"), bHas));
        Assert.Equal(SaveUploadStatus.Conflict, c.Status);
        (await Resolve(a, v1.ItemId, ResolveChoice.KeepVersion, c.VersionId)).EnsureSuccessStatusCode();

        // B still holds base v1 locally.
        var next = await Upload(b, Req(await Files(b, "b2"), bHas));

        Assert.Equal(SaveUploadStatus.Accepted, next.Status);
        var item = await Item(factory, v1.ItemId);
        Assert.Equal(next.VersionId, item.HeadVersionId);
        Assert.False(item.OpenConflict);
        var version = (await Versions(factory, v1.ItemId)).Single(v => v.Id == next.VersionId);
        Assert.Null(version.ConflictReason);
        Assert.Equal(c.VersionId, version.BaseVersionId); // recorded as based on the head the device holds
    }

    [Fact]
    public async Task Bulk_first_sync_UseDevice_winner_next_save_with_null_base_is_Accepted()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        var s1 = await Upload(a, Req(await Files(a, "s1"), null));
        var d1 = await Upload(b, Req(await Files(b, "d1"), null));
        Assert.Equal(SaveUploadStatus.Conflict, d1.Status);
        (await b.PostJsonAsync("/v1/conflicts/first-sync/resolve",
            new BulkFirstSyncRequest(bKey.DeviceId, FirstSyncChoice.UseDevice, null))).EnsureSuccessStatusCode();

        // B never adopted a version id: its base is still null.
        var next = await Upload(b, Req(await Files(b, "d1b"), null));

        Assert.Equal(SaveUploadStatus.Accepted, next.Status);
        var item = await Item(factory, s1.ItemId);
        Assert.Equal(next.VersionId, item.HeadVersionId);
        Assert.False(item.OpenConflict);
    }

    [Fact]
    public async Task Device_whose_known_is_not_head_uploading_old_base_still_conflicts()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var bHas = (await Upload(b, Req(await Files(b, "v1"), null))).HeadVersionId; // server records B known = v1
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));

        var stale = await Upload(b, Req(await Files(b, "b"), bHas));
        var fresh = await Upload(b, Req(await Files(b, "b-null"), null));

        Assert.Equal(SaveUploadStatus.Conflict, stale.Status);
        Assert.Equal(SaveUploadStatus.Conflict, fresh.Status);
        var versions = await Versions(factory, v1.ItemId);
        Assert.Equal(ConflictReason.Concurrent, versions.Single(v => v.Id == stale.VersionId).ConflictReason);
        Assert.Equal(ConflictReason.FirstSync, versions.Single(v => v.Id == fresh.VersionId).ConflictReason);
        Assert.Equal(v2.VersionId, (await Item(factory, v1.ItemId)).HeadVersionId);
    }

    [Fact]
    public async Task Bulk_first_sync_writes_one_summary_activity_row()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "Bee");
        var s1 = await Upload(a, Req(await Files(a, "s1"), null, game: "g1"));
        await Upload(a, Req(await Files(a, "s2"), null, game: "g2"));
        await Upload(b, Req(await Files(b, "d1"), null, game: "g1"));
        await Upload(b, Req(await Files(b, "d2"), null, game: "g2"));
        var before = (await (await a.GetAsync("/v1/activity?limit=200")).ReadAsync<ActivityPage>()).Items.Count;

        (await a.PostJsonAsync("/v1/conflicts/first-sync/resolve",
            new BulkFirstSyncRequest(bKey.DeviceId, FirstSyncChoice.UseServer, null))).EnsureSuccessStatusCode();

        var items = (await (await a.GetAsync("/v1/activity?limit=200")).ReadAsync<ActivityPage>()).Items;
        Assert.Equal(before + 1, items.Count);
        Assert.Equal("conflict_resolved", items[0].Kind);
        Assert.Equal("Bee first-sync: kept server's saves for 2 games", items[0].Message);
        _ = s1;
    }

    [Fact]
    public async Task Bulk_first_sync_UseServer_keeps_head_and_respects_item_filter()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        var s1 = await Upload(a, Req(await Files(a, "s1"), null, game: "g1"));
        var s2 = await Upload(a, Req(await Files(a, "s2"), null, game: "g2"));
        var d1 = await Upload(b, Req(await Files(b, "d1"), null, game: "g1"));
        var d2 = await Upload(b, Req(await Files(b, "d2"), null, game: "g2"));

        var web = await factory.WebLogin("a@example.com");
        (await web.PostJsonAsync("/v1/conflicts/first-sync/resolve",
            new BulkFirstSyncRequest(bKey.DeviceId, FirstSyncChoice.UseServer, [s1.ItemId]))).EnsureSuccessStatusCode();

        var i1 = await Item(factory, s1.ItemId);
        Assert.Equal(s1.VersionId, i1.HeadVersionId);
        Assert.False(i1.OpenConflict);
        Assert.True((await Versions(factory, s1.ItemId)).Single(v => v.Id == d1.VersionId).Resolved);
        var i2 = await Item(factory, s2.ItemId);
        Assert.True(i2.OpenConflict);
        Assert.False((await Versions(factory, s2.ItemId)).Single(v => v.Id == d2.VersionId).Resolved);

        var (_, otherKey) = await factory.Register("z@example.com");
        await AssertError(await a.PostJsonAsync("/v1/conflicts/first-sync/resolve",
            new BulkFirstSyncRequest(otherKey.DeviceId, FirstSyncChoice.UseServer, null)), HttpStatusCode.NotFound, "device_not_found");
    }

    [Fact]
    public async Task Pin_and_unpin()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (other, _) = await factory.Register("z@example.com");
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));

        Assert.Equal(HttpStatusCode.NoContent, (await a.PostAsync($"/v1/saves/{v1.ItemId}/pin/{v1.VersionId}", null)).StatusCode);
        Assert.True((await Versions(factory, v1.ItemId)).Single().Pinned);
        Assert.Equal(HttpStatusCode.NoContent, (await a.DeleteAsync($"/v1/saves/{v1.ItemId}/pin/{v1.VersionId}")).StatusCode);
        Assert.False((await Versions(factory, v1.ItemId)).Single().Pinned);

        await AssertError(await a.PostAsync($"/v1/saves/{v1.ItemId}/pin/{Guid.NewGuid()}", null), HttpStatusCode.NotFound, "version_not_found");
        await AssertError(await a.PostAsync($"/v1/saves/{Guid.NewGuid()}/pin/{v1.VersionId}", null), HttpStatusCode.NotFound, "version_not_found");
        await AssertError(await other.PostAsync($"/v1/saves/{v1.ItemId}/pin/{v1.VersionId}", null), HttpStatusCode.NotFound, "version_not_found");
        await AssertError(await other.DeleteAsync($"/v1/saves/{v1.ItemId}/pin/{v1.VersionId}"), HttpStatusCode.NotFound, "version_not_found");
    }

    [Theory]
    [InlineData("/abs")]
    [InlineData("a/../b")]
    [InlineData("..")]
    [InlineData("./a")]
    [InlineData("a//b")]
    [InlineData("a/")]
    [InlineData("a\\b")]
    [InlineData("a\0b")]
    [InlineData("")]
    [InlineData("duplicate")]
    [InlineData("toolong")]
    [InlineData("badhash")]
    [InlineData("negative")]
    [InlineData("empty")]
    [InlineData("wrongsize")]
    public async Task Bad_manifest_paths_rejected(string path)
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var good = (await Files(a, "content"))[0];
        IReadOnlyList<ManifestEntry> manifest = path switch
        {
            "duplicate" => [good with { Path = "x" }, good with { Path = "x" }],
            "toolong" => [good with { Path = new string('a', 513) }],
            "badhash" => [good with { Hash = good.Hash.ToUpperInvariant() }],
            "negative" => [good with { Size = -1 }],
            "empty" => [],
            "wrongsize" => [good with { Size = good.Size + 1 }],
            _ => [good with { Path = path }],
        };

        await AssertError(await a.PostJsonAsync("/v1/saves/upload", Req(manifest, null)), HttpStatusCode.BadRequest, "bad_manifest");
        Assert.Equal(0, await InDb(factory, db => db.SaveVersions.CountAsync()));
    }

    [Fact]
    public async Task Too_many_entries_rejected()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var good = (await Files(a, "content"))[0];
        var manifest = Enumerable.Range(0, 10001).Select(i => good with { Path = $"f{i}" }).ToList();
        await AssertError(await a.PostJsonAsync("/v1/saves/upload", Req(manifest, null)), HttpStatusCode.BadRequest, "bad_manifest");
        // The limit itself is fine.
        Assert.Equal(SaveUploadStatus.Accepted, (await Upload(a, Req(manifest[..10000], null))).Status);
    }

    [Fact]
    public async Task Missing_or_foreign_blobs_rejected()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (other, _) = await factory.Register("z@example.com");
        var foreign = await Files(other, "theirs");
        var missing = new ManifestEntry("x.dat", Fixtures.Sha256([1, 2, 3]), 3);

        await AssertError(await a.PostJsonAsync("/v1/saves/upload", Req([missing], null)), HttpStatusCode.BadRequest, "missing_blobs");
        await AssertError(await a.PostJsonAsync("/v1/saves/upload", Req(foreign, null)), HttpStatusCode.BadRequest, "missing_blobs");
        Assert.Equal(0, await InDb(factory, db => db.SaveItems.CountAsync()));
    }

    [Theory]
    [InlineData("emulator")]
    [InlineData("gamekey-empty")]
    [InlineData("gamekey-long")]
    [InlineData("slot")]
    [InlineData("core")]
    [InlineData("display")]
    public async Task Bad_identity_rejected(string field)
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var m = await Files(a, "x");
        var request = field switch
        {
            "emulator" => Req(m, null, emulator: "snes9x"),
            "gamekey-empty" => Req(m, null, game: ""),
            "gamekey-long" => Req(m, null, game: new string('g', 301)),
            "slot" => Req(m, null, slot: new string('s', 101)),
            "core" => Req(m, null, core: new string('c', 101)),
            _ => Req(m, null, displayName: new string('d', 301)),
        };
        var r = await a.PostJsonAsync("/v1/saves/upload", request);
        await AssertError(r, HttpStatusCode.BadRequest, field == "emulator" ? "unknown_emulator" : "bad_request");
    }

    [Fact]
    public async Task Upload_requires_a_device()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var m = await Files(a, "x");
        var web = await factory.WebLogin("a@example.com");
        await AssertError(await web.PostJsonAsync("/v1/saves/upload", Req(m, null)), HttpStatusCode.BadRequest, "no_device");
        Assert.Equal(HttpStatusCode.Unauthorized, (await factory.Client().PostJsonAsync("/v1/saves/upload", Req(m, null))).StatusCode);
    }

    [Fact]
    public async Task Enums_are_strings_on_the_wire()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var m = await Files(a, "x");
        var json = $$"""
            {"emulatorId":"eden","kind":"State","gameKey":"g","slot":"1","core":"","baseVersionId":null,
             "manifest":[{"path":"{{m[0].Path}}","hash":"{{m[0].Hash}}","size":{{m[0].Size}}}]}
            """;
        var r = await a.PostAsync("/v1/saves/upload", new StringContent(json, Encoding.UTF8, "application/json"));
        var body = await r.Content.ReadAsStringAsync();
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Contains("\"status\":\"Accepted\"", body);
        var item = await InDb(factory, db => db.SaveItems.SingleAsync());
        Assert.Equal(ItemKind.State, item.Kind);
    }
}

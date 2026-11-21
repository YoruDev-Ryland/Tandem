using System.Net;
using Tandem.Protocol;
using Tandem.Server.Data;
using Tandem.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Tandem.Server.Tests.Saves;

/// <summary>C1: DELETE /v1/web/games/{emulatorId}/{*gameKey}.</summary>
public class DeleteGameTests
{
    [Fact]
    public async Task Deleting_a_game_removes_its_saves_states_versions_and_pending_deliveries()
    {
        using var f = new ServerFactory();
        var (a, key) = await f.Register("a@example.com", deviceName: "Thor");
        var (b, bKey) = await f.PairSecondDevice(a, "Odin");
        const string game = "retroarch/Pokemon - Emerald Version";
        await b.Install(game, "retroarch");
        await a.UploadSave(game, "one", null, "retroarch");
        var manifest = await Fixtures.Manifest(a, ("s.state", "st"u8.ToArray()));
        (await a.PostJsonAsync("/v1/saves/upload",
            new SaveUploadRequest("retroarch", ItemKind.State, game, "1", "mgba", null, manifest, null))).EnsureSuccessStatusCode();
        await a.UploadSave("other", "keep", null, "retroarch");
        Assert.NotEmpty(await b.PendingDeliveries());

        var web = await f.WebLogin("a@example.com");
        var response = await web.DeleteAsync("/v1/web/games/retroarch/retroarch%2FPokemon%20-%20Emerald%20Version");
        Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);

        using var scope = f.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        Assert.Equal(["other"], await db.SaveItems.Select(i => i.GameKey).ToListAsync());
        Assert.Equal(1, await db.SaveVersions.CountAsync());
        Assert.DoesNotContain(await b.PendingDeliveries(), d => d.Kind == DeliveryKind.Save && d.GameKey == game);
        var activity = await db.ActivityEvents.Where(e => e.Kind == "game_deleted").SingleAsync();
        Assert.Equal(game, activity.GameKey);
        Assert.Equal(key.UserId, activity.UserId);
        _ = bKey;

        var again = await web.DeleteAsync("/v1/web/games/retroarch/retroarch%2FPokemon%20-%20Emerald%20Version");
        Assert.Equal(HttpStatusCode.NotFound, again.StatusCode);
        Assert.Equal("not_found", (await again.ReadAsync<ErrorResponse>()).Code);
    }

    [Fact]
    public async Task Another_user_cannot_delete_my_game()
    {
        using var f = new ServerFactory();
        f.Settings["Tandem:AllowRegistration"] = "true";
        var (a, _) = await f.Register("a@example.com");
        await a.UploadSave("game", "one", null);
        await f.Register("b@example.com");
        var other = await f.WebLogin("b@example.com");
        Assert.Equal(HttpStatusCode.NotFound, (await other.DeleteAsync("/v1/web/games/eden/game")).StatusCode);
        Assert.Single(await (await f.WebLogin("a@example.com")).GetOk<List<GameRow>>("/v1/web/games"));
    }

    [Fact]
    public async Task A_device_learns_which_of_its_items_were_deleted_and_re_uploads_them_as_new()
    {
        using var f = new ServerFactory();
        var (a, _) = await f.Register("a@example.com", deviceName: "Thor");
        var (b, _) = await f.PairSecondDevice(a, "Odin");
        var first = await a.UploadSave("game", "one", null);
        var second = await a.UploadSave("game", "two", first.VersionId);
        await a.UploadSave("other", "keep", null);
        var manifest = await Fixtures.Manifest(a, ("s.state", "st"u8.ToArray()));
        (await a.PostJsonAsync("/v1/saves/upload",
            new SaveUploadRequest("eden", ItemKind.State, "game", "1", "core", null, manifest, null))).EnsureSuccessStatusCode();

        var web = await f.WebLogin("a@example.com");
        Assert.Equal(HttpStatusCode.NoContent, (await web.DeleteAsync("/v1/web/games/eden/game")).StatusCode);

        // The device names the items it holds a known version of; the server answers which no longer exist.
        var asked = new MissingSavesRequest([
            new SaveItemKey("eden", ItemKind.Save, "game", "", "recorded-only"),
            new SaveItemKey("eden", ItemKind.State, "game", "1", "core"),
            new SaveItemKey("eden", ItemKind.Save, "other", "", ""),
        ]);
        var missing = await (await a.PostJsonAsync("/v1/saves/missing", asked)).ReadAsync<MissingSavesResponse>();
        Assert.Equal([asked.Items[0], asked.Items[1]], missing.Missing);

        // Its state cleared, the device uploads the unchanged save again with no base: a new item, cleanly accepted.
        var again = await a.UploadSave("game", "two", null);
        Assert.Equal(SaveUploadStatus.Accepted, again.Status);
        Assert.NotEqual(second.ItemId, again.ItemId);
        // A second device holding the same save, also cleared, matches it: no conflict.
        Assert.Equal(SaveUploadStatus.Unchanged, (await b.UploadSave("game", "two", null)).Status);
    }

    [Fact]
    public async Task A_change_uploaded_on_a_deleted_version_as_its_base_is_accepted_as_a_new_item()
    {
        using var f = new ServerFactory();
        var (a, _) = await f.Register("a@example.com");
        var first = await a.UploadSave("game", "one", null);
        var web = await f.WebLogin("a@example.com");
        Assert.Equal(HttpStatusCode.NoContent, (await web.DeleteAsync("/v1/web/games/eden/game")).StatusCode);
        var next = await a.UploadSave("game", "changed", first.VersionId);
        Assert.Equal(SaveUploadStatus.Accepted, next.Status);
    }

    [Fact]
    public async Task The_missing_check_is_bounded_and_validated()
    {
        using var f = new ServerFactory();
        var (a, _) = await f.Register("a@example.com");
        var many = Enumerable.Range(0, 5001).Select(i => new SaveItemKey("eden", ItemKind.Save, $"g{i}", "", "")).ToList();
        Assert.Equal(HttpStatusCode.BadRequest, (await a.PostJsonAsync("/v1/saves/missing", new MissingSavesRequest(many))).StatusCode);
        var unknown = new MissingSavesRequest([new SaveItemKey("nope", ItemKind.Save, "g", "", "")]);
        Assert.Equal(HttpStatusCode.BadRequest, (await a.PostJsonAsync("/v1/saves/missing", unknown)).StatusCode);
    }
}

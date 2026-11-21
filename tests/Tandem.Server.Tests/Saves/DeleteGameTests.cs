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
}

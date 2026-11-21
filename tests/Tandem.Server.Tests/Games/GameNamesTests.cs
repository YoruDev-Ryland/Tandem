using System.Net;
using System.Text;
using Tandem.Protocol;
using Tandem.Server.Data;
using Tandem.Server.Games;
using Tandem.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace Tandem.Server.Tests.Games;

public class GameNamesTests
{
    [Theory]
    [InlineData("13 Sentinels: Aegis Rim [v0]", "13 Sentinels: Aegis Rim")]
    [InlineData("Pokemon Sword [0100ABF008968000][v0]", "Pokemon Sword")]
    [InlineData("Super Mario World (USA) (Rev 1)", "Super Mario World")]
    [InlineData("Metroid Fusion (Europe) (En,Fr,De,Es,It)", "Metroid Fusion")]
    [InlineData("Pokemon Mystery Dungeon - Red Rescue Team (Red)", "Pokemon Mystery Dungeon - Red Rescue Team (Red)")]
    [InlineData("Atelier Lulua ~The Scion of Arland~", "Atelier Lulua ~The Scion of Arland~")]
    [InlineData("  Zelda  [!] ", "Zelda")]
    public void Clean_strips_rom_site_tags_but_keeps_real_parts_of_names(string raw, string expected)
        => Assert.Equal(expected, GameNames.Clean(raw));

    [Fact]
    public void Clean_of_a_name_that_is_only_tags_is_null() => Assert.Null(GameNames.Clean("[v0]"));

    private static TitleNames Db() => TitleNames.Parse(new MemoryStream(Encoding.UTF8.GetBytes("""
        {
          "70010000012345": { "id": "0100ABF008968000", "name": "Pokémon™ Sword", "region": "US" },
          "70010000012346": { "id": "0100AC20128AC000", "name": "Some Game" },
          "70010000012347": { "id": null, "name": "No id" },
          "70010000012348": { "id": "0100000011D90000", "name": null }
        }
        """)));

    [Fact]
    public void Title_list_maps_ids_to_names_and_falls_back_to_the_base_title()
    {
        var db = Db();
        Assert.Equal("Pokémon™ Sword", db.Lookup("0100abf008968000"));
        Assert.Equal("Some Game", db.Lookup("0100AC20128AC001"));   // another program of the same title
        Assert.Null(db.Lookup("0100000011D90000"));
        Assert.Null(db.Lookup("not-an-id"));
        Assert.Equal(2, db.Count);
    }

    [Fact]
    public void Title_list_round_trips_through_its_compact_cache()
    {
        var path = Path.Combine(Path.GetTempPath(), $"titles-{Guid.NewGuid():N}.json");
        try
        {
            Db().Save(path);
            Assert.Equal("Some Game", TitleNames.Load(path).Lookup("0100AC20128AC000"));
        }
        finally { File.Delete(path); }
    }

    [Theory]
    [InlineData("eden", "0100ABF008968000", null, "Pokémon™ Sword")]
    [InlineData("eden", "0100ABF008968000", "0100ABF008968000", "Pokémon™ Sword")]
    [InlineData("eden", "0100ABF008968000", "My Sword [v0]", "My Sword")]
    [InlineData("eden", "0100FFFFFFFFF000", null, null)]
    [InlineData("retroarch", "Super Mario World (USA)", "Super Mario World (USA)", "Super Mario World")]
    [InlineData("retroarch", "0100ABF008968000", null, null)]
    public void Resolve_prefers_a_real_client_name_then_the_switch_title_list(string emulator, string key, string? client, string? expected)
    {
        var names = new GameNames(new TitleNamesHolder { Current = Db() });
        Assert.Equal(expected, names.Resolve(emulator, key, client));
    }

    private static ServerFactory NewFactory(TitleNamesHolder holder)
    {
        var factory = new ServerFactory();
        factory.Settings["Tandem:AllowRegistration"] = "true";
        factory.ConfigureExtraServices = s => { s.RemoveAll<TitleNamesHolder>(); s.AddSingleton(holder); };
        return factory;
    }

    [Fact]
    public async Task A_switch_save_uploaded_without_a_name_gets_its_title_and_a_later_list_backfills_old_items()
    {
        var holder = new TitleNamesHolder();
        using var f = NewFactory(holder);
        var (a, _) = await f.Register("a@example.com");
        var manifest = await Fixtures.Manifest(a, ("data.dat", Encoding.UTF8.GetBytes("x")));
        var r = await a.PostJsonAsync("/v1/saves/upload",
            new SaveUploadRequest("eden", ItemKind.Save, "0100ABF008968000", "", "", null, manifest, null));
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);

        async Task<string?> Name()
        {
            using var scope = f.Services.CreateScope();
            return (await scope.ServiceProvider.GetRequiredService<TandemDbContext>().SaveItems.AsNoTracking().SingleAsync()).DisplayName;
        }
        Assert.Null(await Name());   // no list yet

        holder.Current = Db();
        await f.Services.GetRequiredService<TitleNamesBackfill>().RunAsync(CancellationToken.None);
        Assert.Equal("Pokémon™ Sword", await Name());

        var r2 = await a.PostJsonAsync("/v1/saves/upload",
            new SaveUploadRequest("eden", ItemKind.Save, "0100AC20128AC000", "", "", null, manifest, "0100AC20128AC000"));
        Assert.Equal(HttpStatusCode.OK, r2.StatusCode);
        using var scope2 = f.Services.CreateScope();
        Assert.Equal("Some Game", (await scope2.ServiceProvider.GetRequiredService<TandemDbContext>().SaveItems.AsNoTracking()
            .SingleAsync(i => i.GameKey == "0100AC20128AC000")).DisplayName);
    }
}

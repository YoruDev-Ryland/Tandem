using System.Net;
using System.Text;
using Tandem.Protocol;
using Tandem.Server.Data;
using Tandem.Server.Games;
using Tandem.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Tandem.Server.Tests.Games;

/// <summary>Item 13: emulator-path prefixes, GameCube/Wii names from GameTDB, the v3 cache, the download cap.</summary>
public class DiscNamesTests
{
    private const string GameTdb = "TITLES = https://www.gametdb.com (type: Wii language: EN version: 20261101)\n" +
                                   "GALE01 = Super Smash Bros. Melee\n" +
                                   "RMCE01 = Mario Kart Wii\n" +
                                   "RMCP01 = Mario Kart Wii (PAL)\n" +
                                   "WAAE = A WiiWare Game\n" +
                                   "bad line\n" +
                                   "TOOLONG1 = Nope\n";

    private static TitleNames Tdb() => TitleNames.ParseGameTdb(new MemoryStream(Encoding.UTF8.GetBytes(GameTdb)));

    [Theory]
    [InlineData("retroarch/Pokemon - Emerald Version", "Pokemon - Emerald Version")]
    [InlineData("retroarch/Super Mario World (USA)", "Super Mario World")]
    [InlineData("RetroArch/Metroid [!]", "Metroid")]
    [InlineData("Not retroarch/a prefix", "Not retroarch/a prefix")]
    public void Clean_strips_an_emulator_path_prefix(string raw, string expected) => Assert.Equal(expected, GameNames.Clean(raw));

    [Fact]
    public void GameTdb_lines_map_6_and_4_character_game_ids()
    {
        var tdb = Tdb();
        Assert.Equal(4, tdb.Count);
        Assert.Equal("Super Smash Bros. Melee", tdb.LookupDisc("GALE01"));
        Assert.Equal("Mario Kart Wii", tdb.LookupDisc("RMCE"));   // a Wii save's 4-character key: the first full id wins
        Assert.Equal("A WiiWare Game", tdb.LookupDisc("WAAE"));
        Assert.Null(tdb.LookupDisc("ZZZZ"));
        Assert.Null(tdb.LookupDisc("gale01"));                     // Dolphin keys are upper case
        Assert.Null(tdb.Lookup("GALE01"));                         // not a 16-hex title id
    }

    [Theory]
    [InlineData("dolphin", "GALE01", null, "Super Smash Bros. Melee")]
    [InlineData("dolphin", "GALE01", "GALE01", "Super Smash Bros. Melee")]
    [InlineData("dolphin", "RMCE", null, "Mario Kart Wii")]
    [InlineData("dolphin", "GALE01", "Melee (USA)", "Melee")]
    [InlineData("dolphin", "XXXX01", null, null)]
    [InlineData("eden", "GALE01", null, null)]
    [InlineData("retroarch", "retroarch/Pokemon - Emerald Version", null, "Pokemon - Emerald Version")]
    [InlineData("retroarch", "retroarch/Pokemon - Emerald Version", "retroarch/Pokemon - Emerald Version", "Pokemon - Emerald Version")]
    public void Resolve_names_dolphin_ids_and_retroarch_keys(string emulator, string key, string? client, string? expected)
    {
        var names = new GameNames(new TitleNamesHolder { Current = Tdb() });
        Assert.Equal(expected, names.Resolve(emulator, key, client));
    }

    [Fact]
    public void Labels_fall_back_to_a_cleaned_key()
    {
        Assert.Equal("Zelda", GameNames.Label("whatever", "Zelda"));
        Assert.Equal("Pokemon - Emerald Version", GameNames.Label("retroarch/Pokemon - Emerald Version", null));
        Assert.Equal("ULUS10400", GameNames.Label("ULUS10400", " "));
    }

    [Fact]
    public void The_cache_round_trips_disc_ids_and_their_prefix_lookup()
    {
        var path = Path.Combine(Path.GetTempPath(), $"titles-{Guid.NewGuid():N}.json");
        try
        {
            Tdb().Save(path);
            Assert.Equal("Mario Kart Wii", TitleNames.Load(path).LookupDisc("RMCE"));
        }
        finally { File.Delete(path); }
    }

    [Fact]
    public async Task A_title_list_over_the_cap_or_of_the_wrong_type_is_refused()
    {
        var big = new ByteArrayContent(new byte[10]);
        big.Headers.ContentLength = 400L * 1024 * 1024;
        await Assert.ThrowsAsync<InvalidDataException>(() => TitleNamesRefresher.CopyCappedAsync(big, Stream.Null, 300L * 1024 * 1024, default));

        var streamed = new StreamContent(new MemoryStream(new byte[2000])); // no length header: counted while streaming
        streamed.Headers.ContentLength = null;
        await Assert.ThrowsAsync<InvalidDataException>(() => TitleNamesRefresher.CopyCappedAsync(streamed, Stream.Null, 1000, default));

        var image = new ByteArrayContent([1, 2, 3]);
        image.Headers.ContentType = new("image/png");
        await Assert.ThrowsAsync<InvalidDataException>(() => TitleNamesRefresher.CopyCappedAsync(image, Stream.Null, 1000, default));

        var ok = new StringContent(GameTdb, Encoding.UTF8, "text/plain");
        using var target = new MemoryStream();
        await TitleNamesRefresher.CopyCappedAsync(ok, target, 1000, default);
        Assert.Equal(Encoding.UTF8.GetByteCount(GameTdb), target.Length);
    }

    private sealed class Lists : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var body = request.RequestUri!.Host switch
            {
                "switch.test" => """{ "1": { "id": "0100ABF008968000", "name": "Pokémon Sword" } }""",
                "3ds.test" => """[ { "Name": "Pokémon X", "TitleID": "0004000000055D00" } ]""",
                "tdb.test" => GameTdb,
                _ => null,
            };
            return Task.FromResult(body is null
                ? new HttpResponseMessage(HttpStatusCode.NotFound)
                : new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(body) });
        }
    }

    [Fact]
    public async Task On_start_old_caches_go_and_the_v3_list_names_dolphin_saves()
    {
        using var f = new ServerFactory();
        File.WriteAllText(Path.Combine(f.DataDirectory, "titles.json"), "{}");
        File.WriteAllText(Path.Combine(f.DataDirectory, "titles-v2.json"), """{ "0100ABF008968000": "Old Sword" }""");
        f.Settings["Tandem:EnableTitleNames"] = "true";
        f.Settings["Tandem:TitleNamesUrl"] = "https://switch.test/US.en.json";
        f.Settings["Tandem:ThreeDsTitleNamesUrls"] = "https://3ds.test/list_US.json";
        f.Settings["Tandem:DiscTitleNamesUrl"] = "https://tdb.test/wiitdb.txt";
        f.ConfigureExtraServices = s => s.AddHttpClient(TitleNamesRefresher.ClientName).ConfigurePrimaryHttpMessageHandler(() => new Lists());
        var (device, _) = await f.Register("a@example.com");

        var cache = Path.Combine(f.DataDirectory, TitleNamesRefresher.CacheFileName);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        while (!File.Exists(cache) || File.Exists(Path.Combine(f.DataDirectory, "titles-v2.json")))
            await Task.Delay(20, timeout.Token);
        Assert.False(File.Exists(Path.Combine(f.DataDirectory, "titles.json")));

        var manifest = await Fixtures.Manifest(device, ("GALE01.gci", "x"u8.ToArray()));
        (await device.PostJsonAsync("/v1/saves/upload",
            new SaveUploadRequest("dolphin", ItemKind.Save, "GALE01", "A", "", null, manifest, null))).EnsureSuccessStatusCode();
        using var scope = f.Services.CreateScope();
        var item = await scope.ServiceProvider.GetRequiredService<TandemDbContext>().SaveItems.AsNoTracking().SingleAsync();
        Assert.Equal("Super Smash Bros. Melee", item.DisplayName);
        Assert.Equal("Pokémon Sword", f.Services.GetRequiredService<TitleNamesHolder>().Current!.Lookup("0100ABF008968000"));
    }

    [Fact]
    public async Task Activity_rows_use_names_not_raw_keys()
    {
        using var f = new ServerFactory();
        var (device, _) = await f.Register("a@example.com", deviceName: "Thor");
        await device.UploadSave("retroarch/Pokemon - Emerald Version", "x", null, "retroarch");
        var files = await Fixtures.Manifest(device, ("config/qt-config.ini", "[UI]\n"u8.ToArray()));
        (await device.PostJsonAsync("/v1/configs/upload", new ConfigUploadRequest("eden", null, files))).EnsureSuccessStatusCode();
        var web = await f.WebLogin("a@example.com");
        var messages = (await web.GetOk<ActivityPage>("/v1/activity")).Items.Select(i => i.Message).ToList();
        Assert.Contains("Thor uploaded save for Pokemon - Emerald Version", messages);
        Assert.Contains("Thor uploaded the Eden config", messages);
    }
}

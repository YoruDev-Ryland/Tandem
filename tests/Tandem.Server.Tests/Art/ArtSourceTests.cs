using System.Text;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Microsoft.Extensions.Time.Testing;
using Tandem.Server.Art;
using Tandem.Server.Games;

namespace Tandem.Server.Tests.Art;

public class ArtSourceTests
{
    // Real libretro-thumbnails file names (Named_Boxarts listings), a few per system.
    private static readonly Dictionary<string, string[]> Listings = new()
    {
        ["Nintendo_-_Game_Boy_Advance"] =
        [
            "Pokemon - Emerald Version (Europe).png", "Pokemon - Emerald Version (Japan).png",
            "Pokemon - Emerald Version (USA, Europe).png", "Pokemon - Ruby Version (USA).png",
            "Legend of Zelda, The - The Minish Cap (USA).png", "Mario _ Luigi - Superstar Saga (USA).png",
        ],
        ["Nintendo_-_Nintendo_DS"] =
        [
            "Chrono Trigger (Europe) (En,Fr).png", "Chrono Trigger (Japan) (En,Ja).png", "Chrono Trigger (USA) (En,Fr).png",
        ],
        ["Nintendo_-_Nintendo_3DS"] =
        [
            "Pokemon X (Europe) (En,Ja,Fr,De,Es,It,Ko).png", "Pokemon X (Japan) (En,Ja,Fr,De,Es,It,Ko) (Rev 1).png",
            "Pokemon X (USA) (En,Ja,Fr,De,Es,It,Ko).png", "Pokemon X (USA).png", "Pokemon Y (USA).png",
        ],
        ["Sony_-_PlayStation_Portable"] =
        [
            "Monster Hunter Freedom Unite (Europe) (En,Fr,De,Es,It).png", "Monster Hunter Freedom Unite (USA) (Demo).png",
            "Monster Hunter Freedom Unite (USA) (En,Fr,De,Es,It).png",
        ],
    };

    private sealed class FakeIndex : ILibretroIndex
    {
        public List<string> Asked { get; } = [];

        public Task<IReadOnlyCollection<string>> FilesAsync(string repo, CancellationToken ct)
        {
            Asked.Add(repo);
            return Task.FromResult<IReadOnlyCollection<string>>(Listings.GetValueOrDefault(repo) ?? []);
        }
    }

    private static async Task<string?> Libretro(string emulatorId, string gameKey, string? displayName, string? core = null)
    {
        var uris = await new LibretroThumbnailSource(new FakeIndex()).CandidatesAsync(new ArtRequest(emulatorId, gameKey, displayName, core), default);
        return uris.SingleOrDefault()?.AbsoluteUri;
    }

    private static TitleNames DiscList() =>
        TitleNames.ParseGameTdb(new MemoryStream(Encoding.UTF8.GetBytes("TITLES = test\nGXBE69 = SSX 3\nRMCE01 = Mario Kart Wii\n")));

    private static List<string> GameTdb(string emulatorId, string gameKey, TitleNames? list = null) =>
        GameTdbCoverSource.Candidates(new ArtRequest(emulatorId, gameKey, null, null), list).Select(u => u.AbsoluteUri).ToList();

    [Fact]
    public void GameTdb_builds_the_cover_url_from_the_dolphin_game_id()
    {
        // SSX 3 (the owner's Dolphin game; its id is GXBE69).
        Assert.Equal(["https://art.gametdb.com/wii/cover/US/GXBE69.png"], GameTdb("dolphin", "GXBE69"));
        Assert.Equal(["https://art.gametdb.com/wii/cover/JA/GALJ01.png"], GameTdb("dolphin", "GALJ01"));
        Assert.Equal(["https://art.gametdb.com/wii/cover/EN/RMCP01.png"], GameTdb("dolphin", "RMCP01"));
        Assert.Equal(["https://art.gametdb.com/wii/cover/DE/RMCD01.png", "https://art.gametdb.com/wii/cover/EN/RMCD01.png"],
            GameTdb("dolphin", "RMCD01"));
    }

    [Fact]
    public void GameTdb_widens_a_wii_save_id_through_the_title_list()
    {
        Assert.Equal(["https://art.gametdb.com/wii/cover/US/RMCE01.png"], GameTdb("dolphin", "RMCE", DiscList()));
        Assert.Empty(GameTdb("dolphin", "RMCE")); // names off: no list to widen it with
    }

    [Fact]
    public void GameTdb_has_nothing_for_other_emulators()
    {
        Assert.Empty(GameTdb("eden", "0100F2C0115B6000"));
        Assert.Empty(GameTdb("azahar", "0004000000055D00"));
        Assert.Empty(GameTdb("melonds", "nds/Chrono Trigger (US)"));
        Assert.Empty(GameTdb("dolphin", "not-an-id"));
    }

    [Fact]
    public async Task Libretro_matches_a_retroarch_rom_name_by_core()
    {
        Assert.Equal(
            "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Game_Boy_Advance/master/Named_Boxarts/Pokemon%20-%20Emerald%20Version%20%28USA%2C%20Europe%29.png",
            await Libretro("retroarch", "retroarch/Pokemon - Emerald Version", "Pokemon - Emerald Version", "mGBA"));
        // The region the ROM name gives wins over the default order.
        Assert.EndsWith("Pokemon%20-%20Emerald%20Version%20%28Japan%29.png",
            await Libretro("retroarch", "retroarch/Pokemon - Emerald Version (Japan)", null, "mGBA"));
        // Exact file name, articles and libretro's '&' → '_' rule.
        Assert.EndsWith("Legend%20of%20Zelda%2C%20The%20-%20The%20Minish%20Cap%20%28USA%29.png",
            await Libretro("retroarch", "retroarch/The Legend of Zelda - The Minish Cap", null, "gpsp"));
        Assert.EndsWith("Mario%20_%20Luigi%20-%20Superstar%20Saga%20%28USA%29.png",
            await Libretro("retroarch", "GBA/Mario & Luigi - Superstar Saga.srm", null));
    }

    [Fact]
    public async Task Libretro_matches_a_melonds_rom_name()
    {
        Assert.Equal(
            "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Nintendo_DS/master/Named_Boxarts/Chrono%20Trigger%20%28USA%29%20%28En%2CFr%29.png",
            await Libretro("melonds", "nds/Chrono Trigger (US)", "Chrono Trigger (US)"));
    }

    [Fact]
    public async Task Libretro_matches_an_id_keyed_game_by_display_name()
    {
        Assert.Equal(
            "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Nintendo_3DS/master/Named_Boxarts/Pokemon%20X%20%28USA%29.png",
            await Libretro("azahar", "0004000000055D00", "Pokémon X"));
        Assert.Equal(
            "https://raw.githubusercontent.com/libretro-thumbnails/Sony_-_PlayStation_Portable/master/Named_Boxarts/Monster%20Hunter%20Freedom%20Unite%20%28USA%29%20%28En%2CFr%2CDe%2CEs%2CIt%29.png",
            await Libretro("ppsspp", "ULUS10391", "MONSTER HUNTER FREEDOM UNITE"));
    }

    [Fact]
    public async Task A_system_with_no_source_returns_nothing()
    {
        var index = new FakeIndex();
        var source = new LibretroThumbnailSource(index);
        // Switch: no libretro repository, and GameTDB keys Switch covers by product code, not title id.
        Assert.Empty(await source.CandidatesAsync(new ArtRequest("eden", "0100F2C0115B6000", "Pokémon Sword", null), default));
        // RetroArch without a known core or system segment.
        Assert.Empty(await source.CandidatesAsync(new ArtRequest("retroarch", "retroarch/Some Game", null, ""), default));
        // A name the listing doesn't have.
        Assert.Empty(await source.CandidatesAsync(new ArtRequest("azahar", "0004000000000000", "Not A Real Game", null), default));
        Assert.Empty(await source.CandidatesAsync(new ArtRequest("azahar", "0004000000000000", null, null), default));
        Assert.Equal(["Nintendo_-_Nintendo_3DS"], index.Asked);
    }

    [Fact]
    public void Title_keys_fold_accents_articles_and_punctuation()
    {
        Assert.Equal("pokemonemeraldversion", LibretroThumbnailSource.TitleKey("Pokémon - Emerald Version (USA)"));
        Assert.Equal(LibretroThumbnailSource.TitleKey("The Legend of Zelda: The Minish Cap"),
            LibretroThumbnailSource.TitleKey("Legend of Zelda, The - The Minish Cap (USA)"));
        Assert.Equal("a_b_c", LibretroThumbnailSource.Sanitize("a:b?c"));
    }

    [Fact]
    public void System_labels_follow_the_app_and_web()
    {
        Assert.Equal("GC", ArtSystems.Label("dolphin", "GXBE69", null));
        Assert.Equal("WII", ArtSystems.Label("dolphin", "RMCE", null));
        Assert.Equal("GBA", ArtSystems.Label("retroarch", "retroarch/Pokemon - Emerald Version", "mGBA"));
        Assert.Equal("GBA", ArtSystems.Label("retroarch", "GBA/Pokémon - Emerald Version (USA).srm", null));
        Assert.Equal("SNES", ArtSystems.Label("retroarch", "retroarch/x", "snes9x_libretro"));
        Assert.Null(ArtSystems.Label("retroarch", "retroarch/x", null));
        Assert.Equal("3DS", ArtSystems.Label("azahar", "0004000000055D00", null));
    }

    [Fact]
    public async Task The_libretro_listing_is_cached_and_drops_symlinks_and_big_files()
    {
        var data = Directory.CreateTempSubdirectory("tandem-art-").FullName;
        try
        {
            var calls = 0;
            const string body = """
                { "tree": [
                  { "path": "Pokemon X (USA).png", "mode": "100644", "type": "blob", "size": 1000 },
                  { "path": "Pokemon X (Demo).png", "mode": "120000", "type": "blob", "size": 30 },
                  { "path": "Huge (USA).png", "mode": "100644", "type": "blob", "size": 99999999 },
                  { "path": "notes.txt", "mode": "100644", "type": "blob", "size": 10 }
                ] }
                """;
            var handler = new ArtEndpointTests.FakeSources(_ =>
            {
                calls++;
                return new HttpResponseMessage(System.Net.HttpStatusCode.OK) { Content = new StringContent(body) };
            });
            var options = Options.Create(new ServerOptions { DataDirectory = data });
            var time = new FakeTimeProvider(DateTimeOffset.Parse("2026-11-19T12:00:00Z"));
            var index = new LibretroIndex(new SingleClient(handler), options, time, NullLogger<LibretroIndex>.Instance);

            Assert.Equal(["Pokemon X (USA).png"], await index.FilesAsync("Nintendo_-_Nintendo_3DS", default));
            Assert.Equal(LibretroIndex.ListingUri("Nintendo_-_Nintendo_3DS"), Assert.Single(handler.Seen));

            // A new server process reads the saved listing instead of asking again.
            var again = new LibretroIndex(new SingleClient(handler), options, time, NullLogger<LibretroIndex>.Instance);
            Assert.Equal(["Pokemon X (USA).png"], await again.FilesAsync("Nintendo_-_Nintendo_3DS", default));
            Assert.Equal(1, calls);

            time.Advance(TimeSpan.FromDays(8));
            await again.FilesAsync("Nintendo_-_Nintendo_3DS", default);
            Assert.Equal(2, calls);
        }
        finally { Directory.Delete(data, recursive: true); }
    }

    [Fact]
    public async Task An_unreachable_listing_is_unavailable_not_a_miss()
    {
        var data = Directory.CreateTempSubdirectory("tandem-art-").FullName;
        try
        {
            var handler = new ArtEndpointTests.FakeSources(_ => new HttpResponseMessage(System.Net.HttpStatusCode.ServiceUnavailable));
            var index = new LibretroIndex(new SingleClient(handler), Options.Create(new ServerOptions { DataDirectory = data }),
                TimeProvider.System, NullLogger<LibretroIndex>.Instance);
            await Assert.ThrowsAsync<ArtSourceUnavailableException>(() => index.FilesAsync("Nintendo_-_Nintendo_DS", default));
        }
        finally { Directory.Delete(data, recursive: true); }
    }

    [Fact]
    public async Task A_failed_listing_is_asked_for_once_an_hour_not_once_per_game()
    {
        var data = Directory.CreateTempSubdirectory("tandem-art-").FullName;
        try
        {
            var handler = new ArtEndpointTests.FakeSources(_ => new HttpResponseMessage(System.Net.HttpStatusCode.Forbidden));
            var time = new FakeTimeProvider(DateTimeOffset.Parse("2026-11-19T12:00:00Z"));
            var index = new LibretroIndex(new SingleClient(handler), Options.Create(new ServerOptions { DataDirectory = data }),
                time, NullLogger<LibretroIndex>.Instance);
            var source = new LibretroThumbnailSource(index);

            foreach (var game in new[] { "GBA/Advance Wars (USA).srm", "GBA/Golden Sun (USA).srm", "GBA/Metroid Fusion (USA).srm" })
                await Assert.ThrowsAsync<ArtSourceUnavailableException>(() => source.CandidatesAsync(new ArtRequest("retroarch", game, null, null), default));
            Assert.Equal(1, handler.Calls);

            time.Advance(TimeSpan.FromMinutes(61));
            await Assert.ThrowsAsync<ArtSourceUnavailableException>(() => index.FilesAsync("Nintendo_-_Game_Boy_Advance", default));
            Assert.Equal(2, handler.Calls);
        }
        finally { Directory.Delete(data, recursive: true); }
    }

    [Fact]
    public async Task A_stale_listing_is_kept_and_its_refresh_backs_off()
    {
        var data = Directory.CreateTempSubdirectory("tandem-art-").FullName;
        try
        {
            var up = true;
            var handler = new ArtEndpointTests.FakeSources(_ => up
                ? new HttpResponseMessage(System.Net.HttpStatusCode.OK)
                    { Content = new StringContent("""{ "tree": [ { "path": "Pokemon X (USA).png", "mode": "100644", "type": "blob", "size": 9 } ] }""") }
                : new HttpResponseMessage(System.Net.HttpStatusCode.ServiceUnavailable));
            var time = new FakeTimeProvider(DateTimeOffset.Parse("2026-11-19T12:00:00Z"));
            var index = new LibretroIndex(new SingleClient(handler), Options.Create(new ServerOptions { DataDirectory = data }),
                time, NullLogger<LibretroIndex>.Instance);
            await index.FilesAsync("Nintendo_-_Nintendo_3DS", default);

            up = false;
            time.Advance(TimeSpan.FromDays(8));
            for (var i = 0; i < 3; i++)
                Assert.Equal(["Pokemon X (USA).png"], await index.FilesAsync("Nintendo_-_Nintendo_3DS", default));
            Assert.Equal(2, handler.Calls); // the first load, then one failed refresh

            up = true;
            time.Advance(TimeSpan.FromMinutes(61));
            await index.FilesAsync("Nintendo_-_Nintendo_3DS", default);
            await index.FilesAsync("Nintendo_-_Nintendo_3DS", default);
            Assert.Equal(3, handler.Calls);
        }
        finally { Directory.Delete(data, recursive: true); }
    }

    [Fact]
    public void The_store_sweeps_old_temp_files_and_expired_misses()
    {
        var data = Directory.CreateTempSubdirectory("tandem-art-").FullName;
        try
        {
            var time = new FakeTimeProvider(DateTimeOffset.UtcNow);
            var store = new ArtStore(Options.Create(new ServerOptions { DataDirectory = data }), time, NullLogger<ArtStore>.Instance);
            store.RecordMiss("e", "old", time.GetUtcNow().AddHours(1));
            store.RecordMiss("e", "new", time.GetUtcNow().AddDays(7));
            Directory.CreateDirectory(Path.Combine(store.Directory, "index"));
            var staleTmp = Path.Combine(store.Directory, "index", "repo.txt.tmp");
            var freshTmp = Path.Combine(store.Directory, "abc.png.tmp");
            File.WriteAllText(staleTmp, "x");
            File.WriteAllText(freshTmp, "x");
            File.SetLastWriteTimeUtc(staleTmp, time.GetUtcNow().UtcDateTime.AddHours(-2));
            File.SetLastWriteTimeUtc(freshTmp, time.GetUtcNow().UtcDateTime);

            time.Advance(TimeSpan.FromHours(2));
            File.SetLastWriteTimeUtc(freshTmp, time.GetUtcNow().UtcDateTime.AddMinutes(-5));
            store.Sweep();

            Assert.False(File.Exists(staleTmp));
            Assert.True(File.Exists(freshTmp));
            Assert.False(File.Exists(Path.Combine(store.Directory, ArtStore.NameOf("e", "old") + ".miss")));
            Assert.IsType<ArtLookup.Miss>(store.TryGet("e", "new"));
        }
        finally { Directory.Delete(data, recursive: true); }
    }

    [Fact]
    public void A_transient_miss_says_so_and_carries_its_expiry()
    {
        var data = Directory.CreateTempSubdirectory("tandem-art-").FullName;
        try
        {
            var time = new FakeTimeProvider(DateTimeOffset.Parse("2026-11-19T12:00:00Z"));
            var store = new ArtStore(Options.Create(new ServerOptions { DataDirectory = data }), time, NullLogger<ArtStore>.Instance);
            store.RecordMiss("e", "a", time.GetUtcNow().AddHours(1), transient: true);
            store.RecordMiss("e", "b", time.GetUtcNow().AddDays(7));
            Assert.Equal(new ArtLookup.Miss(time.GetUtcNow().AddHours(1), true), store.TryGet("e", "a"));
            Assert.Equal(new ArtLookup.Miss(time.GetUtcNow().AddDays(7), false), store.TryGet("e", "b"));
        }
        finally { Directory.Delete(data, recursive: true); }
    }

    [Fact]
    public void The_art_client_does_not_follow_redirects()
    {
        using var handler = Assert.IsType<SocketsHttpHandler>(ArtFetcher.CreatePrimaryHandler());
        Assert.False(handler.AllowAutoRedirect);
    }

    [Fact]
    public void The_store_evicts_the_least_recently_read_image()
    {
        var data = Directory.CreateTempSubdirectory("tandem-art-").FullName;
        try
        {
            var time = new FakeTimeProvider(DateTimeOffset.Parse("2026-11-19T12:00:00Z"));
            var store = new ArtStore(Options.Create(new ServerOptions { DataDirectory = data, ArtCacheBytes = 250 }), time,
                NullLogger<ArtStore>.Instance);
            var png = ArtEndpointTests.FakePng(100);
            store.Save("e", "a", png, ".png");
            time.Advance(TimeSpan.FromMinutes(1));
            store.Save("e", "b", png, ".png");
            time.Advance(TimeSpan.FromMinutes(1));
            Assert.IsType<ArtLookup.Image>(store.TryGet("e", "a")); // a is now read more recently than b
            time.Advance(TimeSpan.FromMinutes(1));
            store.Save("e", "c", png, ".png");

            Assert.IsType<ArtLookup.Image>(store.TryGet("e", "a"));
            Assert.IsType<ArtLookup.Unknown>(store.TryGet("e", "b"));
            Assert.IsType<ArtLookup.Image>(store.TryGet("e", "c"));

            store.RecordMiss("e", "d", time.GetUtcNow().AddDays(7));
            Assert.IsType<ArtLookup.Miss>(store.TryGet("e", "d"));
            time.Advance(TimeSpan.FromDays(8));
            Assert.IsType<ArtLookup.Unknown>(store.TryGet("e", "d"));
        }
        finally { Directory.Delete(data, recursive: true); }
    }

    private sealed class SingleClient(HttpMessageHandler handler) : IHttpClientFactory
    {
        public HttpClient CreateClient(string name) => new(handler, disposeHandler: false);
    }
}

using System.Net;
using System.Net.Http.Json;
using EmuSync.Protocol;
using EmuSync.Server.Data;
using EmuSync.Server.Devices;
using EmuSync.Server.Events;
using EmuSync.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace EmuSync.Server.Tests;

public class DeviceTests
{
    private static DeviceStatusRequest Status(bool shizuku = true, IReadOnlyList<EmulatorStatus>? emus = null,
        IReadOnlyDictionary<string, string>? roots = null) =>
        new("android", "1.0", shizuku,
            emus ?? [new EmulatorStatus("retroarch", "com.retroarch", "1.19", ["mgba_libretro_android"])],
            roots ?? new Dictionary<string, string> { ["{INTERNAL}"] = "/storage/emulated/0" });

    private static async Task AssertError(HttpResponseMessage r, HttpStatusCode status, string code)
    {
        Assert.Equal(status, r.StatusCode);
        Assert.Equal(code, (await r.ReadAsync<ErrorResponse>()).Code);
    }

    private static InstallsRequest Games(params string[] keys) =>
        new(keys.Select(k => new InstalledGame(k, k.ToUpperInvariant(), null)).ToArray());

    [Fact]
    public async Task Status_replaces_emulators_and_roots()
    {
        using var factory = new ServerFactory();
        var (device, key) = await factory.Register("a@example.com");

        (await device.PutAsJsonAsync("/v1/devices/me/status", Status(
            emus: [new("retroarch", "com.retroarch", "1.19", ["a", "b"]), new("eden", "dev.eden", "0.1", null)],
            roots: new Dictionary<string, string> { ["{INTERNAL}"] = "/a", ["{SD}"] = "/b", ["{EMU:eden}"] = "/c" })))
            .EnsureSuccessStatusCode();
        (await device.PutAsJsonAsync("/v1/devices/me/status", Status(
            emus: [new("dolphin", "org.dolphin", "5", null)],
            roots: new Dictionary<string, string> { ["{SD}"] = "/sd2" })))
            .EnsureSuccessStatusCode();

        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>();
        var emus = await db.DeviceEmulators.Where(e => e.DeviceId == key.DeviceId).ToListAsync();
        Assert.Equal("dolphin", Assert.Single(emus).EmulatorId);
        var roots = await db.DeviceRoots.Where(r => r.DeviceId == key.DeviceId).ToListAsync();
        Assert.Equal("/sd2", Assert.Single(roots).Path);
        var svc = scope.ServiceProvider.GetRequiredService<DeviceService>();
        Assert.Equal("/sd2", await svc.Root(key.DeviceId, "{SD}"));
        Assert.Null(await svc.Root(key.DeviceId, "{INTERNAL}"));
    }

    [Theory]
    [InlineData("INTERNAL")]
    [InlineData("{internal}")]
    [InlineData("{EMU:nope}")]
    [InlineData("{EMU:}")]
    [InlineData("{SD}\n")] // a trailing newline is not part of a valid token
    public async Task Bad_root_token_is_400(string token)
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var r = await device.PutAsJsonAsync("/v1/devices/me/status",
            Status(roots: new Dictionary<string, string> { [token] = "/x" }));
        await AssertError(r, HttpStatusCode.BadRequest, "bad_root");
    }

    [Fact]
    public async Task Relative_root_path_is_400_bad_root()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var r = await device.PutAsJsonAsync("/v1/devices/me/status",
            Status(roots: new Dictionary<string, string> { ["{SD}"] = "relative/path" }));
        await AssertError(r, HttpStatusCode.BadRequest, "bad_root");
    }

    [Fact]
    public async Task Unknown_emulator_is_400_unknown_emulator()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var r = await device.PutAsJsonAsync("/v1/devices/me/status",
            Status(emus: [new("nope", "p", "1", null)]));
        await AssertError(r, HttpStatusCode.BadRequest, "unknown_emulator");
        r = await device.PutAsJsonAsync("/v1/devices/me/installs/nope", Games("a"));
        await AssertError(r, HttpStatusCode.BadRequest, "unknown_emulator");
    }

    [Fact]
    public async Task Installs_full_replace_reports_added_and_removed()
    {
        using var factory = new ServerFactory();
        var (device, key) = await factory.Register("a@example.com");
        var added = new List<InstallsChanged>();
        factory.Services.GetRequiredService<IDomainEvents>().Subscribe<InstallsChanged>((e, _) => { added.Add(e); return Task.CompletedTask; });

        var r = await device.PutAsJsonAsync("/v1/devices/me/installs/eden", Games("a", "b", "c"));
        Assert.Equal(new InstallsResponse(3, 0), await r.ReadAsync<InstallsResponse>());
        r = await device.PutAsJsonAsync("/v1/devices/me/installs/eden", Games("b", "c", "d"));
        Assert.Equal(new InstallsResponse(1, 1), await r.ReadAsync<InstallsResponse>());

        Assert.Equal(2, added.Count);
        Assert.Equal(["d"], added[1].AddedGameKeys);
        Assert.Equal(key.DeviceId, added[1].DeviceId);
        Assert.Equal("eden", added[1].EmulatorId);
        Assert.Equal(key.UserId, added[1].UserId);

        r = await device.PutAsJsonAsync("/v1/devices/me/installs/eden", Games("b", "c", "d"));
        Assert.Equal(new InstallsResponse(0, 0), await r.ReadAsync<InstallsResponse>());
        Assert.Equal(2, added.Count);

        using var scope = factory.Services.CreateScope();
        var svc = scope.ServiceProvider.GetRequiredService<DeviceService>();
        Assert.True(await svc.HasInstalled(key.DeviceId, "eden", "d"));
        Assert.False(await svc.HasInstalled(key.DeviceId, "eden", "a"));
        Assert.False(await svc.HasInstalled(key.DeviceId, "dolphin", "d"));
    }

    [Fact]
    public async Task Installs_validation()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        await AssertError(await device.PutAsJsonAsync("/v1/devices/me/installs/eden", Games("a", "a")),
            HttpStatusCode.BadRequest, "duplicate_game");
        await AssertError(await device.PutAsJsonAsync("/v1/devices/me/installs/eden", Games("")),
            HttpStatusCode.BadRequest, "bad_game");
        await AssertError(await device.PutAsJsonAsync("/v1/devices/me/installs/eden", Games(new string('a', 301))),
            HttpStatusCode.BadRequest, "bad_game");
        await AssertError(await device.PutAsJsonAsync("/v1/devices/me/installs/eden",
            Games(Enumerable.Range(0, 20001).Select(i => "g" + i).ToArray())),
            HttpStatusCode.BadRequest, "too_many");
    }

    [Fact]
    public async Task Web_session_without_device_gets_400_no_device()
    {
        using var factory = new ServerFactory();
        await factory.Register("a@example.com");
        var web = await factory.WebLogin("a@example.com");
        await AssertError(await web.PutAsJsonAsync("/v1/devices/me/status", Status()), HttpStatusCode.BadRequest, "no_device");
        await AssertError(await web.PutAsJsonAsync("/v1/devices/me/installs/eden", Games("a")), HttpStatusCode.BadRequest, "no_device");
    }

    [Fact]
    public async Task Shizuku_off_sets_since_time_and_on_clears_it()
    {
        using var factory = new ServerFactory();
        var (device, key) = await factory.Register("a@example.com");

        async Task<(bool? Ok, DateTimeOffset? Since)> Read()
        {
            using var scope = factory.Services.CreateScope();
            var d = await scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>().Devices.AsNoTracking().SingleAsync(x => x.Id == key.DeviceId);
            return (d.ShizukuOk, d.ShizukuOffSinceUtc);
        }

        var t0 = factory.Time.GetUtcNow();
        (await device.PutAsJsonAsync("/v1/devices/me/status", Status(shizuku: false))).EnsureSuccessStatusCode();
        Assert.Equal((false, t0), await Read());

        factory.Time.Advance(TimeSpan.FromMinutes(5));
        (await device.PutAsJsonAsync("/v1/devices/me/status", Status(shizuku: false))).EnsureSuccessStatusCode();
        Assert.Equal((false, t0), await Read());

        (await device.PutAsJsonAsync("/v1/devices/me/status", Status(shizuku: true))).EnsureSuccessStatusCode();
        Assert.Equal((true, null), await Read());
    }

    [Fact]
    public async Task Rename_trims_and_validates_and_scopes_to_user()
    {
        using var factory = new ServerFactory();
        factory.Settings["EmuSync:AllowRegistration"] = "true";
        var (device, key) = await factory.Register("a@example.com");
        var (other, otherKey) = await factory.Register("b@example.com");

        var r = await device.PatchAsJsonAsync($"/v1/devices/{key.DeviceId}", new RenameDeviceRequest("  Deck  "));
        var renamed = await r.ReadAsync<DeviceSummary>();
        Assert.Equal("Deck", renamed.Name);
        Assert.True(renamed.IsCurrent);
        await AssertError(await device.PatchAsJsonAsync($"/v1/devices/{key.DeviceId}", new RenameDeviceRequest("   ")),
            HttpStatusCode.BadRequest, "bad_name");
        await AssertError(await device.PatchAsJsonAsync($"/v1/devices/{key.DeviceId}", new RenameDeviceRequest(new string('x', 81))),
            HttpStatusCode.BadRequest, "bad_name");
        await AssertError(await device.PatchAsJsonAsync($"/v1/devices/{otherKey.DeviceId}", new RenameDeviceRequest("x")),
            HttpStatusCode.NotFound, "device_not_found");
        await AssertError(await device.DeleteAsync($"/v1/devices/{otherKey.DeviceId}"), HttpStatusCode.NotFound, "device_not_found");
        await AssertError(await device.DeleteAsync($"/v1/devices/{Guid.NewGuid()}"), HttpStatusCode.NotFound, "device_not_found");
    }

    [Fact]
    public async Task Revoked_device_key_gets_401_device_revoked()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var (second, secondKey) = await factory.PairSecondDevice(device, "Second");
        Assert.Equal(HttpStatusCode.OK, (await second.GetAsync("/v1/devices/me")).StatusCode);

        var del = await device.DeleteAsync($"/v1/devices/{secondKey.DeviceId}");
        Assert.True(del.IsSuccessStatusCode);

        await AssertError(await second.GetAsync("/v1/devices/me"), HttpStatusCode.Unauthorized, "device_revoked");
        var list = await (await device.GetAsync("/v1/devices")).ReadAsync<DeviceSummary[]>();
        Assert.DoesNotContain(list, d => d.Id == secondKey.DeviceId);
    }

    [Fact]
    public async Task Web_session_can_revoke_any_user_device()
    {
        using var factory = new ServerFactory();
        var (device, key) = await factory.Register("a@example.com");
        var web = await factory.WebLogin("a@example.com");
        Assert.True((await web.DeleteAsync($"/v1/devices/{key.DeviceId}")).IsSuccessStatusCode);
        await AssertError(await device.GetAsync("/v1/devices/me"), HttpStatusCode.Unauthorized, "device_revoked");
    }

    [Fact]
    public async Task Status_with_null_emulators_and_roots_clears_them()
    {
        using var factory = new ServerFactory();
        var (device, key) = await factory.Register("a@example.com");
        (await device.PutAsJsonAsync("/v1/devices/me/status", Status())).EnsureSuccessStatusCode();
        var r = await device.PutAsync("/v1/devices/me/status",
            new StringContent("""{"platform":"android","appVersion":"1","shizukuOk":true}""", System.Text.Encoding.UTF8, "application/json"));
        Assert.Equal(HttpStatusCode.NoContent, r.StatusCode);
        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>();
        Assert.Empty(await db.DeviceEmulators.Where(e => e.DeviceId == key.DeviceId).ToListAsync());
        Assert.Empty(await db.DeviceRoots.Where(e => e.DeviceId == key.DeviceId).ToListAsync());
    }

    [Fact]
    public async Task Throwing_handler_does_not_fail_request_and_other_handlers_run()
    {
        using var factory = new ServerFactory();
        var (device, key) = await factory.Register("a@example.com");
        var events = factory.Services.GetRequiredService<IDomainEvents>();
        var received = new List<InstallsChanged>();
        events.Subscribe<InstallsChanged>((_, _) => throw new InvalidOperationException("boom"));
        events.Subscribe<InstallsChanged>((e, _) => { received.Add(e); return Task.CompletedTask; });

        var r = await device.PutAsJsonAsync("/v1/devices/me/installs/eden", Games("a"));
        Assert.Equal(new InstallsResponse(1, 0), await r.ReadAsync<InstallsResponse>());
        Assert.Single(received);
        using var scope = factory.Services.CreateScope();
        Assert.True(await scope.ServiceProvider.GetRequiredService<DeviceService>().HasInstalled(key.DeviceId, "eden", "a"));
    }

    [Fact]
    public async Task Installs_resend_of_20000_games_completes_quickly()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var req = Games(Enumerable.Range(0, 20000).Select(i => "g" + i).ToArray());
        var first = await device.PutAsJsonAsync("/v1/devices/me/installs/eden", req);
        Assert.Equal(new InstallsResponse(20000, 0), await first.ReadAsync<InstallsResponse>());
        var sw = System.Diagnostics.Stopwatch.StartNew();
        var again = await device.PutAsJsonAsync("/v1/devices/me/installs/eden", req);
        sw.Stop();
        Assert.Equal(new InstallsResponse(0, 0), await again.ReadAsync<InstallsResponse>());
        Assert.True(sw.Elapsed < TimeSpan.FromSeconds(5), $"took {sw.Elapsed}");
    }
}

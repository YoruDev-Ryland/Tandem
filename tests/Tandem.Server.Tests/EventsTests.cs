using System.Net;
using System.Net.Http.Json;
using System.Text;
using Tandem.Protocol;
using Tandem.Server.Events;
using Microsoft.Extensions.DependencyInjection;
using Tandem.Server.Tests.Infrastructure;

namespace Tandem.Server.Tests;

public class EventsTests
{
    private const string Sword = "0100E66006406000";
    private const string Shield = "01008DB008C2C000";

    private static ServerFactory NewFactory()
    {
        var factory = new ServerFactory();
        factory.Settings["Tandem:AllowRegistration"] = "true";
        factory.Settings["Tandem:EventHeartbeatSeconds"] = "1";
        return factory;
    }

    private sealed class Sse(HttpResponseMessage response, StreamReader reader) : IAsyncDisposable
    {
        public HttpResponseMessage Response => response;

        /// <summary>Reads the next event (type, data), or throws OperationCanceledException at the timeout.</summary>
        public async Task<(string Event, string Data)> Next(CancellationToken ct)
        {
            string? ev = null, data = null;
            while (true)
            {
                var line = await reader.ReadLineAsync(ct) ?? throw new EndOfStreamException();
                if (line.Length == 0)
                {
                    if (ev is not null) return (ev, data ?? "");
                    continue;
                }
                if (line.StartsWith("event: ", StringComparison.Ordinal)) ev = line[7..];
                else if (line.StartsWith("data: ", StringComparison.Ordinal)) data = line[6..];
            }
        }

        public async Task<(string Event, string Data)> Until(Func<(string Event, string Data), bool> match, CancellationToken ct)
        {
            while (true)
            {
                var e = await Next(ct);
                if (match(e)) return e;
            }
        }

        public ValueTask DisposeAsync()
        {
            reader.Dispose();
            response.Dispose();
            return ValueTask.CompletedTask;
        }
    }

    private static async Task<Sse> Open(HttpClient client, CancellationToken ct)
    {
        var response = await client.GetAsync("/v1/events", HttpCompletionOption.ResponseHeadersRead, ct);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var reader = new StreamReader(await response.Content.ReadAsStreamAsync(ct), Encoding.UTF8);
        return new Sse(response, reader);
    }

    private static CancellationTokenSource Timeout() => new(TimeSpan.FromSeconds(5));

    private static async Task Install(HttpClient device, params string[] games)
    {
        var response = await device.PutAsJsonAsync("/v1/devices/me/installs/eden",
            new InstallsRequest(games.Select(g => new InstalledGame(g, null, null)).ToList()));
        response.EnsureSuccessStatusCode();
    }

    private static async Task Upload(HttpClient device, string game, string content, string? name = null)
    {
        var manifest = await Fixtures.Manifest(device, ("data.dat", Encoding.UTF8.GetBytes(content)));
        var response = await device.PostJsonAsync("/v1/saves/upload",
            new SaveUploadRequest("eden", ItemKind.Save, game, "", "", null, manifest, name));
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task Device_stream_receives_deliveries_event_after_other_device_uploads()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("e1@example.com", deviceName: "Thor");
        var (b, _) = await factory.PairSecondDevice(a, "Pocket");
        await Install(a, Sword);
        await Install(b, Sword);

        using var cts = Timeout();
        await using var sse = await Open(b, cts.Token);
        Assert.Equal("text/event-stream", sse.Response.Content.Headers.ContentType?.MediaType);
        Assert.Contains("no-cache", sse.Response.Headers.CacheControl!.ToString());
        Assert.Equal("no", sse.Response.Headers.GetValues("X-Accel-Buffering").Single());

        // Sent immediately on connect so a reconnecting device re-polls.
        var first = await sse.Next(cts.Token);
        Assert.Equal("deliveries", first.Event);
        Assert.Equal("""{"type":"deliveries"}""", first.Data);

        await Upload(a, Sword, "one");
        var next = await sse.Until(e => e.Event == "deliveries", cts.Token);
        Assert.Equal("""{"type":"deliveries"}""", next.Data);
    }

    [Fact]
    public async Task Web_stream_receives_changed_saves()
    {
        using var factory = NewFactory();
        var (device, _) = await factory.Register("e2@example.com");
        var web = await factory.WebLogin("e2@example.com");

        using var cts = Timeout();
        await using var sse = await Open(web, cts.Token);
        await Upload(device, Sword, "one");

        var seen = new List<string>();
        var e = await sse.Until(x => { seen.Add(x.Event); return x.Event == "changed" && x.Data == """{"type":"changed","topic":"saves"}"""; }, cts.Token);
        Assert.Equal("changed", e.Event);
        Assert.DoesNotContain("deliveries", seen); // a web connection has no device
    }

    [Fact]
    public async Task Stream_requires_authentication()
    {
        using var factory = NewFactory();
        var response = await factory.Client().GetAsync("/v1/events");
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Heartbeat_ping_sent()
    {
        using var factory = NewFactory(); // heartbeat 1 s, driven by the fake clock
        var (device, _) = await factory.Register("e3@example.com");
        using var cts = Timeout();
        await using var sse = await Open(device, cts.Token);
        Assert.Equal("deliveries", (await sse.Next(cts.Token)).Event);

        var advancer = Task.Run(async () =>
        {
            while (!cts.IsCancellationRequested)
            {
                factory.Time.Advance(TimeSpan.FromSeconds(1));
                await Task.Delay(50);
            }
        });
        var ping = await sse.Until(e => e.Event == "ping", cts.Token);
        Assert.Equal("""{"type":"ping"}""", ping.Data);
        await cts.CancelAsync();
        await advancer;
    }

    [Fact]
    public async Task A_retried_conflicting_save_upload_logs_conflict_opened_once()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("e6@example.com", deviceName: "Thor");
        var (b, _) = await factory.PairSecondDevice(a, "Pocket");
        await Upload(a, Sword, "a1", "Sword");
        await Upload(b, Sword, "b1"); // no base: first-sync conflict

        // The client re-uploads the same content on every scan while the conflict is open: no new row.
        await Upload(b, Sword, "b1");
        await Upload(b, Sword, "b1");
        async Task<int> Opened() => (await (await a.GetAsync("/v1/activity")).ReadAsync<ActivityPage>())
            .Items.Count(i => i.Kind == "conflict_opened");
        Assert.Equal(1, await Opened());

        // Different content is a new conflict version, and is logged.
        await Upload(b, Sword, "b2");
        Assert.Equal(2, await Opened());
    }

    [Fact]
    public async Task Activity_filters_by_device_and_game_and_pages()
    {
        using var factory = NewFactory();
        var (a, keyA) = await factory.Register("e4@example.com", deviceName: "Thor");
        var (b, keyB) = await factory.PairSecondDevice(a, "Pocket");
        await Upload(a, Sword, "a1", "Sword");
        factory.Time.Advance(TimeSpan.FromSeconds(1));
        await Upload(a, Shield, "a2");
        factory.Time.Advance(TimeSpan.FromSeconds(1));
        await Upload(b, Sword, "b1"); // no base: first-sync conflict

        var all = await (await a.GetAsync("/v1/activity")).ReadAsync<ActivityPage>();
        Assert.Null(all.NextBefore);
        Assert.Equal(4, all.Items.Count); // 1 pairing + 2 uploads + conflict
        Assert.Equal(all.Items.OrderByDescending(i => i.Id).Select(i => i.Id), all.Items.Select(i => i.Id));
        Assert.Contains(all.Items, i => i.Kind == "device_paired" && i.DeviceName == "Pocket");
        Assert.Contains(all.Items, i => i.Kind == "conflict_opened" && i.DeviceName == "Pocket" && i.GameKey == Sword);
        var upload = all.Items.Single(i => i.Kind == "save_uploaded" && i.GameKey == Sword);
        Assert.Equal("Thor uploaded save for Sword", upload.Message);
        Assert.Equal("eden", upload.EmulatorId);

        var byDevice = await (await a.GetAsync($"/v1/activity?device={keyB.DeviceId}")).ReadAsync<ActivityPage>();
        Assert.NotEmpty(byDevice.Items);
        Assert.All(byDevice.Items, i => Assert.Equal(keyB.DeviceId, i.DeviceId));

        var byGame = await (await a.GetAsync($"/v1/activity?game={Shield}&emulator=eden")).ReadAsync<ActivityPage>();
        var only = Assert.Single(byGame.Items);
        Assert.Equal("save_uploaded", only.Kind);

        var page1 = await (await a.GetAsync("/v1/activity?limit=2")).ReadAsync<ActivityPage>();
        Assert.Equal(2, page1.Items.Count);
        Assert.Equal(page1.Items[^1].Id, page1.NextBefore);
        var page2 = await (await a.GetAsync($"/v1/activity?limit=2&before={page1.NextBefore}")).ReadAsync<ActivityPage>();
        Assert.All(page2.Items, i => Assert.True(i.Id < page1.NextBefore));
        Assert.Equal(all.Items.Skip(2).Take(2).Select(i => i.Id), page2.Items.Select(i => i.Id));

        // Another account never sees this activity; revoked devices keep their name.
        var (other, _) = await factory.Register("e5@example.com");
        Assert.Empty((await (await other.GetAsync("/v1/activity")).ReadAsync<ActivityPage>()).Items);
        (await a.DeleteAsync($"/v1/devices/{keyB.DeviceId}")).EnsureSuccessStatusCode();
        var after = await (await a.GetAsync("/v1/activity")).ReadAsync<ActivityPage>();
        Assert.Equal("device_revoked", after.Items[0].Kind);
        Assert.Contains(after.Items, i => i.DeviceName == "Pocket");
        Assert.Equal(HttpStatusCode.BadRequest, (await a.GetAsync("/v1/activity?limit=0")).StatusCode);
    }

    [Fact]
    public async Task Disconnect_removes_subscriber()
    {
        using var factory = NewFactory();
        var (device, _) = await factory.Register("e6@example.com");
        var hub = (EventHub)factory.Services.GetRequiredService<IEventHub>();
        using var cts = Timeout();
        var sse = await Open(device, cts.Token);
        await sse.Next(cts.Token);
        Assert.Equal(1, hub.SubscriberCount);
        await sse.DisposeAsync();
        while (hub.SubscriberCount != 0)
        {
            cts.Token.ThrowIfCancellationRequested();
            await Task.Delay(20);
        }
    }

    [Fact]
    public async Task Burst_of_changed_events_does_not_evict_deliveries()
    {
        var time = new Microsoft.Extensions.Time.Testing.FakeTimeProvider();
        var hub = new EventHub(time, Microsoft.Extensions.Options.Options.Create(new ServerOptions()));
        var user = Guid.NewGuid();
        var deviceId = Guid.NewGuid();
        await using var sub = hub.Subscribe(user, deviceId, CancellationToken.None);
        for (var i = 0; i < 1000; i++) hub.NotifyUser(user, "t" + (i % 6));
        hub.NotifyDevice(deviceId);

        using var cts = Timeout();
        var got = new List<ServerEvent>();
        await foreach (var e in sub.Events.WithCancellation(cts.Token))
        {
            got.Add(e);
            if (got.Count == 7) break; // 6 topics + the one deliveries (the connect-time one is coalesced with it)
        }
        Assert.Single(got, e => e.Type == "deliveries");
        Assert.Equal(6, got.Count(e => e.Type == "changed"));
        Assert.Equal(got.Count, got.Distinct().Count());
    }

    private static async Task<DateTimeOffset?> LastSeen(HttpClient asDevice, Guid id)
    {
        var all = await (await asDevice.GetAsync("/v1/devices")).ReadAsync<List<DeviceSummary>>();
        return all.Single(d => d.Id == id).LastSeenUtc;
    }

    [Fact]
    public async Task Connected_device_stays_seen_while_its_stream_is_open_and_stops_after_disconnect()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("e3@example.com", deviceName: "Thor");
        var (b, bKey) = await factory.PairSecondDevice(a, "Pocket");

        using var cts = Timeout();
        var sse = await Open(b, cts.Token);
        await sse.Next(cts.Token); // connected

        // No other requests from B: only the stream heartbeat can keep it seen.
        for (var i = 0; i < 8; i++) // 4 minutes in 30 s steps
        {
            factory.Time.Advance(TimeSpan.FromSeconds(30));
            await Task.Delay(30);
        }
        while (factory.Time.GetUtcNow() - (await LastSeen(a, bKey.DeviceId)) > TimeSpan.FromMinutes(2))
        {
            cts.Token.ThrowIfCancellationRequested();
            await Task.Delay(20);
        }

        await sse.DisposeAsync();
        await Task.Delay(100);
        var after = await LastSeen(a, bKey.DeviceId);
        factory.Time.Advance(TimeSpan.FromMinutes(5));
        await Task.Delay(100);
        Assert.Equal(after, await LastSeen(a, bKey.DeviceId));
    }
}

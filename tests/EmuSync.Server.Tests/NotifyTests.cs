using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using EmuSync.Protocol;
using EmuSync.Server.Data;
using EmuSync.Server.Events;
using EmuSync.Server.Notify;
using EmuSync.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace EmuSync.Server.Tests;

public class NotifyTests
{
    private const string Game = "0100F2C0115B6000";
    private const string NtfyUrl = "https://ntfy.example.com/emusync";
    private const string DiscordUrl = "https://discord.example.com/api/webhooks/1/secret";
    private const string HookUrl = "https://hooks.example.com/emusync";

    private sealed record Sent(HttpMethod Method, string Url, Dictionary<string, string> Headers, string Body);

    /// <summary>Replaces the "notify" client's handler; records each request and answers with <see cref="Respond"/>.</summary>
    private sealed class CaptureHandler : HttpMessageHandler
    {
        public ConcurrentQueue<Sent> Requests { get; } = new();
        /// <summary>When set, every send waits for this task before answering.</summary>
        public Task? Gate { get; set; }
        public Func<HttpRequestMessage, HttpResponseMessage> Respond { get; set; } = _ => new HttpResponseMessage(HttpStatusCode.OK);

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var headers = request.Headers.ToDictionary(h => h.Key, h => string.Join(",", h.Value), StringComparer.OrdinalIgnoreCase);
            Requests.Enqueue(new Sent(request.Method, request.RequestUri!.ToString(), headers,
                request.Content is null ? "" : await request.Content.ReadAsStringAsync(ct)));
            if (Gate is not null) await Gate;
            return Respond(request);
        }
    }

    private sealed class CaptureLogs : ILoggerProvider
    {
        public ConcurrentQueue<string> Lines { get; } = new();
        public ILogger CreateLogger(string categoryName) => new Sink(this, categoryName);
        public void Dispose() { }

        private sealed class Sink(CaptureLogs owner, string category) : ILogger
        {
            public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
            public bool IsEnabled(LogLevel logLevel) => true;
            public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter) =>
                owner.Lines.Enqueue($"{category}: {formatter(state, exception)} {exception}");
        }
    }

    private static (ServerFactory Factory, CaptureHandler Handler) NewFactory(Action<IServiceCollection>? more = null, CaptureHandler? handler = null)
    {
        handler ??= new CaptureHandler();
        var factory = new ServerFactory();
        factory.Settings["EmuSync:AllowRegistration"] = "true";
        var h = handler;
        factory.ConfigureExtraServices = s =>
        {
            s.AddHttpClient(Notifier.ClientName).ConfigurePrimaryHttpMessageHandler(() => h);
            more?.Invoke(s);
        };
        return (factory, handler);
    }

    private static Task Flush(ServerFactory f) => f.Services.GetRequiredService<NotifyTriggers>().FlushAsync();

    private static async Task Configure(HttpClient client, NotifyEvents? events = null, params NotifyTarget[] targets)
    {
        var response = await client.PutAsJsonAsync("/v1/settings/notifications",
            new NotifySettingsDto(targets, events ?? new NotifyEvents()), TestApi.Json);
        Assert.True(response.IsSuccessStatusCode, await response.Content.ReadAsStringAsync());
    }

    private static async Task<SaveUploadResponse> Upload(HttpClient device, string content, Guid? baseId, string? displayName = null,
        string game = Game)
    {
        var manifest = await Fixtures.Manifest(device, ("data.dat", Encoding.UTF8.GetBytes(content)));
        var response = await device.PostJsonAsync("/v1/saves/upload",
            new SaveUploadRequest("eden", ItemKind.Save, game, "", "", baseId, manifest, displayName));
        response.EnsureSuccessStatusCode();
        return await response.ReadAsync<SaveUploadResponse>();
    }

    /// <summary>A (the user) and B (a second device named "Odin") with a conflict opened by B.</summary>
    private static async Task<(HttpClient A, HttpClient B, SaveUploadResponse V1, DeviceKeyResponse BKey)> Pair(ServerFactory f)
    {
        var (a, _) = await f.Register("a@example.com");
        var (b, bKey) = await f.PairSecondDevice(a, "Odin");
        return (a, b, await Upload(a, "v1", null, "Zelda"), bKey);
    }

    private static async Task OpenConflict(HttpClient a, HttpClient b, Guid v1, string bContent = "b-edit")
    {
        // A moves the head on; B uploads from the old base. Only the first call in a test moves the head.
        await Upload(b, bContent, v1);
    }

    private static Task SetStatus(HttpClient device, bool shizuku) =>
        device.PutAsJsonAsync("/v1/devices/me/status", new DeviceStatusRequest("android", "1.0", shizuku, [], new Dictionary<string, string>()))
            .ContinueWith(t => t.Result.EnsureSuccessStatusCode());

    private static async Task<T> InDb<T>(ServerFactory f, Func<EmuSyncDbContext, Task<T>> query)
    {
        using var scope = f.Services.CreateScope();
        return await query(scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>());
    }

    [Fact]
    public async Task Conflict_sends_ntfy_with_title_header()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        var (a, b, v1, _) = await Pair(f);
        await Configure(a, null, new NotifyTarget(NotifyKind.Ntfy, NtfyUrl, "tk_secret"));
        await Upload(a, "v2", v1.VersionId);

        await OpenConflict(a, b, v1.VersionId);
        await Flush(f);

        var sent = Assert.Single(handler.Requests);
        Assert.Equal(HttpMethod.Post, sent.Method);
        Assert.Equal(NtfyUrl, sent.Url);
        Assert.Equal("EmuSync: sync conflict", sent.Headers["Title"]);
        Assert.Equal("Bearer tk_secret", sent.Headers["Authorization"]);
        Assert.Contains("Zelda", sent.Body);
        Assert.Contains("Eden", sent.Body);
        Assert.Contains("Odin", sent.Body);
        Assert.DoesNotContain("tk_secret", sent.Body);
    }

    [Fact]
    public async Task Ntfy_without_token_sends_no_authorization()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        var (a, b, v1, _) = await Pair(f);
        await Configure(a, null, new NotifyTarget(NotifyKind.Ntfy, NtfyUrl));
        await Upload(a, "v2", v1.VersionId);
        await OpenConflict(a, b, v1.VersionId);
        await Flush(f);

        Assert.False(Assert.Single(handler.Requests).Headers.ContainsKey("Authorization"));
    }

    [Fact]
    public async Task Game_key_is_used_when_no_display_name()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        var (a, _) = await f.Register("a@example.com");
        var (b, _) = await f.PairSecondDevice(a, "Odin");
        var v1 = await Upload(a, "v1", null);
        await Configure(a, null, new NotifyTarget(NotifyKind.Ntfy, NtfyUrl));
        await Upload(a, "v2", v1.VersionId);
        await Upload(b, "b", v1.VersionId);
        await Flush(f);

        Assert.Contains(Game, Assert.Single(handler.Requests).Body);
    }

    [Fact]
    public async Task Discord_payload_shape()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        var (a, b, v1, _) = await Pair(f);
        await Configure(a, null, new NotifyTarget(NotifyKind.Discord, DiscordUrl));
        await Upload(a, "v2", v1.VersionId);
        await OpenConflict(a, b, v1.VersionId);
        await Flush(f);

        var sent = Assert.Single(handler.Requests);
        Assert.Equal(DiscordUrl, sent.Url);
        using var json = JsonDocument.Parse(sent.Body);
        var content = Assert.Single(json.RootElement.EnumerateObject()).Value.GetString()!;
        Assert.Equal("content", json.RootElement.EnumerateObject().Single().Name);
        Assert.StartsWith("**EmuSync: sync conflict**\n", content);
        Assert.Contains("Zelda", content);
    }

    [Fact]
    public async Task Webhook_payload_shape()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        var (a, b, v1, _) = await Pair(f);
        await Configure(a, null, new NotifyTarget(NotifyKind.Webhook, HookUrl));
        await Upload(a, "v2", v1.VersionId);
        await OpenConflict(a, b, v1.VersionId);
        await Flush(f);

        using var json = JsonDocument.Parse(Assert.Single(handler.Requests).Body);
        var root = json.RootElement;
        Assert.Equal(["event", "title", "body", "utc"], root.EnumerateObject().Select(p => p.Name).ToArray());
        Assert.Equal("conflict", root.GetProperty("event").GetString());
        Assert.Equal("EmuSync: sync conflict", root.GetProperty("title").GetString());
        Assert.Equal(f.Time.GetUtcNow(), root.GetProperty("utc").GetDateTimeOffset());
    }

    [Fact]
    public async Task Shizuku_off_30_min_notifies_once()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        var (a, _) = await f.Register("a@example.com", deviceName: "Thor");
        await Configure(a, null, new NotifyTarget(NotifyKind.Ntfy, NtfyUrl));
        var triggers = f.Services.GetRequiredService<NotifyTriggers>();
        await SetStatus(a, false);

        f.Time.Advance(TimeSpan.FromMinutes(29));
        Assert.Equal(0, await triggers.RunOnceAsync(default));
        f.Time.Advance(TimeSpan.FromMinutes(2));
        Assert.Equal(1, await triggers.RunOnceAsync(default));
        await Flush(f);

        var sent = Assert.Single(handler.Requests);
        Assert.Contains("Thor", sent.Body);
        Assert.Equal("EmuSync: Shizuku is off", sent.Headers["Title"]);
        // The flag is stored, so another check (or a restarted server) does not repeat it, however long it stays off.
        f.Time.Advance(TimeSpan.FromHours(5));
        await SetStatus(a, false);
        Assert.Equal(0, await triggers.RunOnceAsync(default));
        Assert.NotNull(await InDb(f, db => db.Devices.Select(d => d.ShizukuOffNotifiedUtc).SingleAsync()));

        // Back on clears it; a new off period notifies again.
        await SetStatus(a, true);
        Assert.Null(await InDb(f, db => db.Devices.Select(d => d.ShizukuOffNotifiedUtc).SingleAsync()));
        await SetStatus(a, false);
        f.Time.Advance(TimeSpan.FromMinutes(31));
        Assert.Equal(1, await triggers.RunOnceAsync(default));
        await Flush(f);
        Assert.Equal(2, handler.Requests.Count);
    }

    [Fact]
    public async Task Shizuku_ok_device_and_revoked_device_never_notify()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        var (a, _) = await f.Register("a@example.com");
        await Configure(a, null, new NotifyTarget(NotifyKind.Ntfy, NtfyUrl));
        await SetStatus(a, true);
        f.Time.Advance(TimeSpan.FromHours(2));

        Assert.Equal(0, await f.Services.GetRequiredService<NotifyTriggers>().RunOnceAsync(default));
        Assert.Empty(handler.Requests);
    }

    [Fact]
    public async Task Disabled_event_sends_nothing()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        var (a, b, v1, _) = await Pair(f);
        await Configure(a, new NotifyEvents(Conflicts: false), new NotifyTarget(NotifyKind.Ntfy, NtfyUrl));
        await Upload(a, "v2", v1.VersionId);

        await OpenConflict(a, b, v1.VersionId);
        await Flush(f);

        Assert.Empty(handler.Requests);
    }

    [Fact]
    public async Task Target_failure_does_not_throw()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        handler.Respond = _ => throw new HttpRequestException("boom");
        var (a, b, v1, _) = await Pair(f);
        await Configure(a, null, new NotifyTarget(NotifyKind.Ntfy, NtfyUrl), new NotifyTarget(NotifyKind.Webhook, HookUrl));
        await Upload(a, "v2", v1.VersionId);

        // The upload that opens the conflict still succeeds, and both targets are tried.
        var conflict = await Upload(b, "b-edit", v1.VersionId);
        await Flush(f);
        Assert.Equal(SaveUploadStatus.Conflict, conflict.Status);
        Assert.Equal(2, handler.Requests.Count);

        await f.Services.GetRequiredService<INotifier>()
            .SendAsync(Guid.NewGuid(), new NotifyMessage("t", "b", NotifyEventNames.Conflict), default);
    }

    [Fact]
    public async Task Duplicate_within_10_min_suppressed()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        var (a, b, v1, _) = await Pair(f);
        await Configure(a, null, new NotifyTarget(NotifyKind.Ntfy, NtfyUrl));
        await Upload(a, "v2", v1.VersionId);

        await Upload(b, "b-edit-1", v1.VersionId);
        await Flush(f);
        f.Time.Advance(TimeSpan.FromMinutes(9));
        await Upload(b, "b-edit-2", v1.VersionId); // another conflict version on the same item
        await Flush(f);
        Assert.Single(handler.Requests);

        f.Time.Advance(TimeSpan.FromMinutes(2));
        await Upload(b, "b-edit-3", v1.VersionId);
        await Flush(f);
        Assert.Equal(2, handler.Requests.Count);
    }

    [Fact]
    public async Task Failed_delivery_notifies_with_game_and_device()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        var (a, _) = await f.Register("a@example.com");
        var (b, _) = await f.PairSecondDevice(a, "Odin");
        await (await b.PutAsJsonAsync("/v1/devices/me/installs/eden",
            new InstallsRequest([new InstalledGame(Game, null, null)]))).Content.ReadAsStringAsync();
        await Upload(a, "v1", null, "Zelda");
        var delivery = (await (await b.GetAsync("/v1/deliveries")).ReadAsync<DeliveryList>()).Items.Single();
        await Configure(a, null, new NotifyTarget(NotifyKind.Ntfy, NtfyUrl));

        (await b.PostJsonAsync($"/v1/deliveries/{delivery.Id}/ack", new AckRequest(DeliveryResult.Failed, "disk full"))).EnsureSuccessStatusCode();
        await Flush(f);

        var sent = Assert.Single(handler.Requests);
        Assert.Equal("EmuSync: sync failed", sent.Headers["Title"]);
        Assert.Contains("Odin", sent.Body);
        Assert.Contains("Zelda", sent.Body);
        Assert.Contains("disk full", sent.Body);
    }

    [Fact]
    public async Task Backup_failure_goes_to_admins_with_the_toggle_on_only()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        var (admin, _) = await f.Register("admin@example.com"); // first account is the admin
        var (user, _) = await f.Register("user@example.com");
        await Configure(admin, null, new NotifyTarget(NotifyKind.Webhook, "https://hooks.example.com/admin"));
        await Configure(user, null, new NotifyTarget(NotifyKind.Webhook, "https://hooks.example.com/user"));
        var events = f.Services.GetRequiredService<IDomainEvents>();

        await events.Raise(new BackupFinished(true, null), default);
        await Flush(f);
        Assert.Empty(handler.Requests);

        await events.Raise(new BackupFinished(false, "Disk is full."), default);
        await Flush(f);
        var sent = Assert.Single(handler.Requests);
        Assert.Equal("https://hooks.example.com/admin", sent.Url);
        Assert.Contains("Disk is full.", sent.Body);

        await Configure(admin, new NotifyEvents(BackupFailed: false), new NotifyTarget(NotifyKind.Webhook, "https://hooks.example.com/admin"));
        f.Time.Advance(TimeSpan.FromMinutes(11));
        await events.Raise(new BackupFinished(false, "Disk is full."), default);
        await Flush(f);
        Assert.Single(handler.Requests);
    }

    [Fact]
    public async Task Token_is_write_only_and_kept_when_omitted()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        var (a, _) = await f.Register("a@example.com");
        await Configure(a, null, new NotifyTarget(NotifyKind.Ntfy, NtfyUrl, "tk_secret"));

        var raw = await a.GetStringAsync("/v1/settings/notifications");
        Assert.DoesNotContain("tk_secret", raw);
        var got = await a.GetFromJsonAsync<NotifySettingsDto>("/v1/settings/notifications", TestApi.Json);
        var target = Assert.Single(got!.Targets);
        Assert.True(target.TokenSet);
        Assert.Null(target.Token);
        Assert.True(got.Events.Conflicts && got.Events.FailedSyncs && got.Events.ShizukuOff && got.Events.BackupFailed);

        // Omitting the token for the same Url keeps it; sending "" clears it; a different Url does not inherit it.
        await Configure(a, null, new NotifyTarget(NotifyKind.Ntfy, NtfyUrl));
        await a.PostAsync("/v1/settings/notifications/test", null);
        Assert.Equal("Bearer tk_secret", handler.Requests.Last().Headers["Authorization"]);

        await Configure(a, null, new NotifyTarget(NotifyKind.Ntfy, NtfyUrl, ""));
        Assert.False((await a.GetFromJsonAsync<NotifySettingsDto>("/v1/settings/notifications", TestApi.Json))!.Targets[0].TokenSet);

        await Configure(a, null, new NotifyTarget(NotifyKind.Ntfy, NtfyUrl, "again"));
        await Configure(a, null, new NotifyTarget(NotifyKind.Ntfy, "https://other.example.com/x"));
        Assert.False((await a.GetFromJsonAsync<NotifySettingsDto>("/v1/settings/notifications", TestApi.Json))!.Targets[0].TokenSet);
    }

    [Fact]
    public async Task Invalid_settings_are_bad_value()
    {
        var (f, _) = NewFactory();
        using var _f = f;
        var (a, _) = await f.Register("a@example.com");

        foreach (var url in new[] { "ftp://example.com/x", "not a url", "", "javascript:alert(1)" })
            await AssertBad(a, new NotifySettingsDto([new NotifyTarget(NotifyKind.Webhook, url)], new NotifyEvents()));
        await AssertBad(a, new NotifySettingsDto(
            Enumerable.Range(0, 11).Select(i => new NotifyTarget(NotifyKind.Webhook, $"https://example.com/{i}")).ToList(), new NotifyEvents()));

        var ten = Enumerable.Range(0, 10).Select(i => new NotifyTarget(NotifyKind.Webhook, $"https://example.com/{i}")).ToList();
        Assert.Equal(HttpStatusCode.OK, (await a.PutAsJsonAsync("/v1/settings/notifications", new NotifySettingsDto(ten, new NotifyEvents()), TestApi.Json)).StatusCode);

        static async Task AssertBad(HttpClient c, NotifySettingsDto body)
        {
            var r = await c.PutAsJsonAsync("/v1/settings/notifications", body, TestApi.Json);
            Assert.Equal(HttpStatusCode.BadRequest, r.StatusCode);
            Assert.Equal("bad_value", (await r.ReadAsync<ErrorResponse>()).Code);
        }
    }

    [Fact]
    public async Task Test_endpoint_reports_each_target()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        handler.Respond = r => r.RequestUri!.Host == "bad.example.com"
            ? new HttpResponseMessage(HttpStatusCode.Forbidden)
            : r.RequestUri.Host == "down.example.com" ? throw new HttpRequestException("refused") : new HttpResponseMessage(HttpStatusCode.OK);
        var (a, _) = await f.Register("a@example.com");
        await Configure(a, new NotifyEvents(false, false, false, false),
            new NotifyTarget(NotifyKind.Ntfy, NtfyUrl), new NotifyTarget(NotifyKind.Discord, "https://bad.example.com/x"),
            new NotifyTarget(NotifyKind.Webhook, "https://down.example.com/x"));

        var response = await a.PostAsync("/v1/settings/notifications/test", null);
        var results = await response.ReadAsync<List<NotifyTestResult>>();

        Assert.Equal(3, results.Count);
        Assert.Equal(new NotifyTestResult(NotifyKind.Ntfy, true, 200), results[0]);
        Assert.Equal((NotifyKind.Discord, false, 403), (results[1].Kind, results[1].Ok, results[1].Status));
        Assert.NotNull(results[1].Error);
        Assert.False(results[2].Ok);
        Assert.Null(results[2].Status);
        Assert.NotNull(results[2].Error);
        Assert.Equal(3, handler.Requests.Count);
    }

    [Fact]
    public async Task Config_conflict_notifies_with_emulator_and_device()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        var (a, key) = await f.Register("a@example.com", deviceName: "Thor");
        await Configure(a, null, new NotifyTarget(NotifyKind.Ntfy, NtfyUrl));

        await f.Services.GetRequiredService<IDomainEvents>()
            .Raise(new ConfigConflictOpened(key.UserId, Guid.NewGuid(), Guid.NewGuid(), "retroarch", key.DeviceId), default);
        await Flush(f);

        var sent = Assert.Single(handler.Requests);
        Assert.Equal("EmuSync: config conflict", sent.Headers["Title"]);
        Assert.Contains("RetroArch", sent.Body);
        Assert.Contains("Thor", sent.Body);
    }

    [Fact]
    public async Task No_log_entry_contains_target_urls_or_tokens()
    {
        var logs = new CaptureLogs();
        var (f, handler) = NewFactory(s => s.AddLogging(b => { b.SetMinimumLevel(LogLevel.Trace); b.AddFilter(null, LogLevel.Trace); b.AddProvider(logs); }));
        using var _ = f;
        var (a, b, v1, _) = await Pair(f);
        await Configure(a, null, new NotifyTarget(NotifyKind.Discord, DiscordUrl),
            new NotifyTarget(NotifyKind.Ntfy, NtfyUrl, "tk_secret"));
        await Upload(a, "v2", v1.VersionId);

        await OpenConflict(a, b, v1.VersionId);
        await Flush(f);
        await a.PostAsync("/v1/settings/notifications/test", null);
        handler.Respond = _ => throw new HttpRequestException("boom"); // the failure path logs too
        f.Time.Advance(TimeSpan.FromMinutes(11));
        await Upload(b, "b-edit-2", v1.VersionId);
        await Flush(f);

        Assert.Equal(6, handler.Requests.Count);
        Assert.NotEmpty(logs.Lines);
        Assert.All(logs.Lines, l =>
        {
            Assert.DoesNotContain("api/webhooks", l);
            Assert.DoesNotContain("secret", l);
            Assert.DoesNotContain("tk_secret", l);
            Assert.DoesNotContain("ntfy.example.com", l);
        });
    }

    [Fact]
    public async Task Other_users_receive_nothing()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        var (a, b, v1, _) = await Pair(f);
        var (other, _) = await f.Register("b@example.com", deviceName: "Other");
        await SetStatus(other, false);
        await Configure(a, null, new NotifyTarget(NotifyKind.Webhook, "https://hooks.example.com/a"));
        await Configure(other, null, new NotifyTarget(NotifyKind.Webhook, "https://hooks.example.com/other"));
        await Upload(a, "v2", v1.VersionId);

        await OpenConflict(a, b, v1.VersionId);
        await SetStatus(a, true);
        await SetStatus(b, true);
        f.Time.Advance(TimeSpan.FromMinutes(31));
        // Only the other user's device is off: A's conflict went to A alone, and the Shizuku notice goes to the other user alone.
        await f.Services.GetRequiredService<NotifyTriggers>().RunOnceAsync(default);
        await Flush(f);

        Assert.Equal(["https://hooks.example.com/a", "https://hooks.example.com/other"], handler.Requests.Select(r => r.Url).ToArray());
        Assert.Contains("conflict", handler.Requests.First().Body);
        Assert.Contains("Other", handler.Requests.Last().Body);
    }

    [Fact]
    public async Task Full_queue_drops_notifications_and_the_request_still_succeeds()
    {
        var gate = new TaskCompletionSource();
        var (f, handler) = NewFactory(handler: new CaptureHandler { Gate = gate.Task });
        f.Settings["EmuSync:NotifyQueueCapacity"] = "1";
        using var _ = f;
        var (a, _) = await f.Register("a@example.com");
        var (b, _) = await f.PairSecondDevice(a, "Odin");
        await Configure(a, null, new NotifyTarget(NotifyKind.Webhook, HookUrl));
        var bases = new List<Guid>();
        foreach (var g in new[] { "g1", "g2", "g3" }) bases.Add((await Upload(a, "v1", null, game: g)).VersionId);

        // 1st conflict: taken by the consumer, which then blocks in the target. 2nd: waits in the queue. 3rd: queue full, dropped.
        var first = await Upload(b, "x1", null, game: "g1");
        await WaitFor(() => handler.Requests.Count == 1);
        var second = await Upload(b, "x2", null, game: "g2");
        var third = await Upload(b, "x3", null, game: "g3");
        Assert.All(new[] { first, second, third }, r => Assert.Equal(SaveUploadStatus.Conflict, r.Status));

        gate.SetResult();
        await Flush(f);
        Assert.Equal(2, handler.Requests.Count);
        Assert.DoesNotContain(handler.Requests, r => r.Body.Contains("g3"));
    }

    private static async Task WaitFor(Func<bool> condition)
    {
        for (var i = 0; i < 400 && !condition(); i++) await Task.Delay(10);
        Assert.True(condition());
    }

    // Retries and Superseded results open no conflict, so they must not notify. Real uploads, not hand-raised events.
    /// <summary>A uploads the head; a second device, B, then uploads its own copy on no base: a first-sync conflict.</summary>
    private static async Task<(HttpClient Web, Guid Head, SaveUploadResponse Conflict, HttpClient B)> SaveConflictThenResolve(ServerFactory f, HttpClient a)
    {
        var web = await f.WebLogin("a@example.com");
        var (b, _) = await f.PairSecondDevice(a, "B");
        var head = await Upload(a, "head", null);
        var conflict = await Upload(b, "other", null);
        Assert.Equal(SaveUploadStatus.Conflict, conflict.Status);
        return (web, head.VersionId, conflict, b);
    }

    [Fact]
    public async Task Save_upload_retry_of_an_open_conflict_does_not_notify()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        var (a, _) = await f.Register("a@example.com");
        await Configure(a, null, new NotifyTarget(NotifyKind.Webhook, HookUrl));
        var (_, _, conflict, b) = await SaveConflictThenResolve(f, a);
        await Flush(f);
        Assert.Single(handler.Requests);

        f.Time.Advance(TimeSpan.FromMinutes(30)); // outside the dedupe window: only "no new conflict" can explain silence
        var retry = await Upload(b, "other", null);
        await Flush(f);

        Assert.Equal(SaveUploadStatus.Conflict, retry.Status);
        Assert.Single(handler.Requests);
    }

    [Fact]
    public async Task Save_superseded_upload_does_not_notify()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        var (a, _) = await f.Register("a@example.com");
        await Configure(a, null, new NotifyTarget(NotifyKind.Webhook, HookUrl));
        var (web, head, conflict, b) = await SaveConflictThenResolve(f, a);
        (await web.PostJsonAsync($"/v1/conflicts/{conflict.ItemId}/resolve", new ResolveConflictRequest(ResolveChoice.KeepVersion, head))).EnsureSuccessStatusCode();
        await Flush(f);
        Assert.Single(handler.Requests);

        f.Time.Advance(TimeSpan.FromMinutes(30));
        var again = await Upload(b, "other", null);
        await Flush(f);

        Assert.Equal(SaveUploadStatus.Superseded, again.Status);
        Assert.Single(handler.Requests);
    }

    private static async Task<ConfigUploadResponse> ConfigUpload(HttpClient device, string text, Guid? baseId)
    {
        var files = await Fixtures.Manifest(device, ("Eden/config/qt-config.ini", Encoding.UTF8.GetBytes(text)));
        var r = await device.PostJsonAsync("/v1/configs/upload", new ConfigUploadRequest("eden", baseId, files));
        Assert.True(r.StatusCode == HttpStatusCode.OK, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
        return await r.ReadAsync<ConfigUploadResponse>();
    }

    /// <summary>A uploads its config (v1); the file is then edited in the browser (the new head) while A, still on v1,
    /// changes it locally and uploads on base v1: a concurrent conflict. Head is the browser edit; Base is v1.</summary>
    private static async Task<(HttpClient A, ConfigUploadResponse Head, ConfigUploadResponse Conflict, Guid Base)> ConfigConflict(ServerFactory f,
        CaptureHandler handler)
    {
        var (a, _) = await f.Register("a@example.com");
        await a.PutAsJsonAsync("/v1/devices/me/status", new DeviceStatusRequest("android", "1.0", true,
            [new EmulatorStatus("eden", "dev.eden", "0.1", null)], new Dictionary<string, string> { ["{INTERNAL}"] = "/storage/emulated/0" }));
        await Configure(a, null, new NotifyTarget(NotifyKind.Webhook, HookUrl));
        var v1 = await ConfigUpload(a, "[Renderer]\nresolution_setup=1\n", null);
        var web = await f.WebLogin("a@example.com");
        var edit = await web.PutAsJsonAsync($"/v1/configs/bundles/{v1.BundleId}/files/Eden/config/qt-config.ini",
            new EditConfigFileRequest(v1.VersionId, "[Renderer]\nresolution_setup=9\n"), TestApi.Json);
        Assert.True(edit.IsSuccessStatusCode, $"{edit.StatusCode}: {await edit.Content.ReadAsStringAsync()}");
        var head = await edit.ReadAsync<ConfigUploadResponse>();
        var conflict = await ConfigUpload(a, "[Renderer]\nresolution_setup=2\n", v1.VersionId);
        Assert.Equal(SaveUploadStatus.Conflict, conflict.Status);
        await Flush(f);
        Assert.Single(handler.Requests); // the one real new conflict notified
        return (a, head, conflict, v1.VersionId);
    }

    [Fact]
    public async Task Config_upload_retry_of_an_open_conflict_does_not_notify()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        var (a, _, _, baseId) = await ConfigConflict(f, handler);

        f.Time.Advance(TimeSpan.FromMinutes(30));
        var retry = await ConfigUpload(a, "[Renderer]\nresolution_setup=2\n", baseId);
        await Flush(f);

        Assert.Equal(SaveUploadStatus.Conflict, retry.Status);
        Assert.Single(handler.Requests);
    }

    [Fact]
    public async Task Config_superseded_upload_does_not_notify()
    {
        var (f, handler) = NewFactory();
        using var _ = f;
        var (a, head, conflict, baseId) = await ConfigConflict(f, handler);
        var web = await f.WebLogin("a@example.com");
        (await web.PostJsonAsync($"/v1/configs/bundles/{conflict.BundleId}/conflicts/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepVersion, head.VersionId))).EnsureSuccessStatusCode();

        f.Time.Advance(TimeSpan.FromMinutes(30));
        var again = await ConfigUpload(a, "[Renderer]\nresolution_setup=2\n", baseId);
        await Flush(f);

        Assert.Equal(SaveUploadStatus.Superseded, again.Status);
        Assert.Single(handler.Requests);
    }
}

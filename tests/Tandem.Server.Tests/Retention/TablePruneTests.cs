using System.Net;
using System.Net.Http.Json;
using Tandem.Protocol;
using Tandem.Server.Data;
using Tandem.Server.Retention;
using Tandem.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Tandem.Server.Tests.Retention;

/// <summary>F-03 item 3: activity, acknowledged deliveries and expired sessions are pruned; the state age rule.</summary>
public class TablePruneTests
{
    private static async Task<T> InDb<T>(ServerFactory f, Func<TandemDbContext, Task<T>> query)
    {
        using var scope = f.Services.CreateScope();
        return await query(scope.ServiceProvider.GetRequiredService<TandemDbContext>());
    }

    private static Task<Pruner.TablePruneResult> PruneTables(ServerFactory f) =>
        f.Services.GetRequiredService<Pruner>().PruneTablesAsync(CancellationToken.None);

    [Fact]
    public async Task Old_activity_old_replaced_deliveries_and_expired_sessions_are_deleted()
    {
        using var f = new ServerFactory();
        var (a, _) = await f.Register("a@example.com", deviceName: "Thor");
        var (b, _) = await f.PairSecondDevice(a, "Odin");
        await b.Install("g");
        var v1 = await a.UploadSave("g", "one", null);
        var first = Assert.Single(await b.PendingDeliveries());
        await b.AckApplied(first.Id);
        await f.WebLogin("a@example.com"); // a session that will expire

        f.Time.Advance(TimeSpan.FromDays(31));
        await f.WebLogin("a@example.com"); // a fresh session
        await a.UploadSave("g", "two", v1.VersionId);
        var second = Assert.Single(await b.PendingDeliveries());
        await b.AckApplied(second.Id);
        var activityBefore = await InDb(f, db => db.ActivityEvents.CountAsync());

        var result = await PruneTables(f);
        Assert.Equal(1, result.Deliveries); // the first, replaced by the second
        Assert.Equal(1, result.Sessions);
        Assert.Equal(0, result.Activity);
        Assert.Equal([second.Id], await InDb(f, db => db.Deliveries.Select(d => d.Id).ToListAsync()));

        // The newest delivery per target stays however old it gets; activity goes after 180 days.
        f.Time.Advance(TimeSpan.FromDays(181));
        result = await PruneTables(f);
        Assert.Equal(0, result.Deliveries);
        Assert.Equal(activityBefore, result.Activity);
        Assert.Single(await InDb(f, db => db.Deliveries.ToListAsync()));
    }

    [Fact]
    public async Task The_newest_applied_ack_stays_while_a_newer_delivery_is_pending_or_failed()
    {
        using var f = new ServerFactory();
        var (a, _) = await f.Register("a@example.com", deviceName: "Thor");
        var (b, _) = await f.PairSecondDevice(a, "Odin");
        await b.Install("g");
        var v1 = await a.UploadSave("g", "one", null);
        var first = Assert.Single(await b.PendingDeliveries());
        await b.AckApplied(first.Id);

        f.Time.Advance(TimeSpan.FromDays(40));
        var v2 = await a.UploadSave("g", "two", v1.VersionId);
        var pending = Assert.Single(await b.PendingDeliveries());
        Assert.Equal(0, (await PruneTables(f)).Deliveries); // the newer one is still pending: the applied ack stays

        (await b.PostJsonAsync($"/v1/deliveries/{pending.Id}/ack", new AckRequest(DeliveryResult.Failed, "untracked file")))
            .EnsureSuccessStatusCode();
        Assert.Equal(0, (await PruneTables(f)).Deliveries); // a failure does not replace the last success either
        Assert.Contains(first.Id, await InDb(f, db => db.Deliveries.Select(d => d.Id).ToListAsync()));

        f.Time.Advance(Tandem.Server.Deliveries.DeliveryService.FailedBackoff + TimeSpan.FromMinutes(1));
        await a.UploadSave("g", "three", v2.VersionId);
        await b.AckApplied(Assert.Single(await b.PendingDeliveries()).Id);
        Assert.Equal(1, (await PruneTables(f)).Deliveries); // a newer success does
        Assert.DoesNotContain(first.Id, await InDb(f, db => db.Deliveries.Select(d => d.Id).ToListAsync()));
    }

    [Fact]
    public async Task The_state_age_rule_drops_old_states_but_never_the_head_or_saves()
    {
        using var f = new ServerFactory();
        var (a, _) = await f.Register("a@example.com");
        async Task<SaveUploadResponse> State(string content, Guid? baseId)
        {
            var manifest = await Fixtures.Manifest(a, ("s.state", System.Text.Encoding.UTF8.GetBytes(content)));
            var r = await a.PostJsonAsync("/v1/saves/upload",
                new SaveUploadRequest("retroarch", ItemKind.State, "retroarch/g", "1", "mgba", baseId, manifest, null));
            r.EnsureSuccessStatusCode();
            return await r.ReadAsync<SaveUploadResponse>();
        }
        var s1 = await State("one", null);
        var s2 = await State("two", s1.VersionId);
        var save1 = await a.UploadSave("game", "one", null);
        await a.UploadSave("game", "two", save1.VersionId);
        f.Time.Advance(TimeSpan.FromDays(91));
        var s3 = await State("three", s2.VersionId);

        await f.Services.GetRequiredService<Pruner>().RunOnceAsync(CancellationToken.None);
        var states = await InDb(f, db => db.SaveVersions.Where(v => v.ItemId == s1.ItemId).Select(v => v.Id).ToListAsync());
        Assert.Equal([s3.VersionId], states);
        Assert.Equal(2, await InDb(f, db => db.SaveVersions.CountAsync(v => v.ItemId == save1.ItemId)));
    }

    [Fact]
    public async Task The_state_age_rule_can_be_turned_off_and_is_validated()
    {
        using var f = new ServerFactory();
        var (a, _) = await f.Register("a@example.com");
        Assert.Equal(90, (await a.GetOk<RetentionSettingsDto>("/v1/settings/retention")).StateMaxAgeDays);
        var bad = await a.PutAsJsonAsync("/v1/settings/retention", new RetentionSettingsDto(30, 30, 5, -1), TestApi.Json);
        Assert.Equal(HttpStatusCode.BadRequest, bad.StatusCode);
        (await a.PutAsJsonAsync("/v1/settings/retention", new RetentionSettingsDto(30, 30, 5, 0), TestApi.Json)).EnsureSuccessStatusCode();
        Assert.Equal(0, (await a.GetOk<RetentionSettingsDto>("/v1/settings/retention")).StateMaxAgeDays);
    }
}

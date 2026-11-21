using System.Net;
using System.Text;
using Tandem.Protocol;
using Tandem.Server.Data;
using Tandem.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Tandem.Server.Tests.Saves;

/// <summary>
/// A device that forgot its item state (it switched the emulator's variant, whose folder may hold an older save, or
/// signed in again) uploads with Fresh: the server takes the base as sent instead of the head it recorded the
/// device as knowing, so different content opens a conflict rather than fast-forwarding over the head.
/// </summary>
public class FreshUploadTests
{
    private const string Game = "0100E66006406000";

    private static ServerFactory NewFactory()
    {
        var factory = new ServerFactory();
        factory.Settings["Tandem:AllowRegistration"] = "true";
        return factory;
    }

    private static async Task<SaveUploadResponse> Upload(HttpClient device, string content, Guid? baseId, bool? fresh = null)
    {
        var manifest = await Fixtures.Manifest(device, ("data.dat", Encoding.UTF8.GetBytes(content)));
        var response = await device.PostJsonAsync("/v1/saves/upload",
            new SaveUploadRequest("eden", ItemKind.Save, Game, "", "", baseId, manifest, null, fresh));
        Assert.True(response.StatusCode == HttpStatusCode.OK, $"{response.StatusCode}: {await response.Content.ReadAsStringAsync()}");
        return await response.ReadAsync<SaveUploadResponse>();
    }

    private static async Task<SaveVersion> Version(ServerFactory f, Guid id)
    {
        using var scope = f.Services.CreateScope();
        return await scope.ServiceProvider.GetRequiredService<TandemDbContext>().SaveVersions.AsNoTracking().SingleAsync(v => v.Id == id);
    }

    private static async Task<Guid?> Head(ServerFactory f, Guid itemId)
    {
        using var scope = f.Services.CreateScope();
        return (await scope.ServiceProvider.GetRequiredService<TandemDbContext>().SaveItems.AsNoTracking()
            .SingleAsync(i => i.Id == itemId)).HeadVersionId;
    }

    [Fact]
    public async Task Fresh_upload_of_other_content_opens_a_first_sync_conflict_and_keeps_the_head()
    {
        using var f = NewFactory();
        var (a, _) = await f.Register("a@example.com");
        var v1 = await Upload(a, "newest", null);   // the server records this device as knowing the head

        var up = await Upload(a, "older, from the other variant", null, fresh: true);

        Assert.Equal(SaveUploadStatus.Conflict, up.Status);
        Assert.Equal(ConflictReason.FirstSync, (await Version(f, up.VersionId)).ConflictReason);
        Assert.Equal(v1.VersionId, await Head(f, v1.ItemId));
    }

    [Fact]
    public async Task Without_fresh_the_known_head_rule_still_applies()
    {
        using var f = NewFactory();
        var (a, _) = await f.Register("a@example.com");
        await Upload(a, "newest", null);

        var up = await Upload(a, "edited", null);

        Assert.Equal(SaveUploadStatus.Accepted, up.Status);
    }

    [Fact]
    public async Task Fresh_upload_of_the_head_content_is_unchanged()
    {
        using var f = NewFactory();
        var (a, _) = await f.Register("a@example.com");
        var v1 = await Upload(a, "newest", null);

        var up = await Upload(a, "newest", null, fresh: true);

        Assert.Equal(SaveUploadStatus.Unchanged, up.Status);
        Assert.Equal(v1.VersionId, up.VersionId);
    }
}

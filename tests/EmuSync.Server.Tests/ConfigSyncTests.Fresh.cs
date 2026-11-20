using System.Net;
using EmuSync.Protocol;
using EmuSync.Server.Tests.Infrastructure;

namespace EmuSync.Server.Tests;

/// <summary>
/// A device that forgot its config state (a variant switch, a new sign-in) uploads with Fresh: the server must not
/// treat the upload as based on the head it last recorded the device as applying.
/// </summary>
public partial class ConfigSyncTests
{
    private static async Task<ConfigUploadResponse> UploadFresh(HttpClient device, IReadOnlyList<ManifestEntry> files, Guid? baseId)
    {
        var r = await device.PostJsonAsync("/v1/configs/upload", new ConfigUploadRequest("eden", baseId, files, Fresh: true));
        Assert.True(r.StatusCode == HttpStatusCode.OK, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
        return await r.ReadAsync<ConfigUploadResponse>();
    }

    [Fact]
    public async Task Fresh_upload_with_other_files_opens_a_first_sync_conflict_and_keeps_the_head()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var v1 = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink))), null);

        var up = await UploadFresh(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "2"))), null);

        Assert.Equal(SaveUploadStatus.Conflict, up.Status);
        Assert.Equal(ConflictReason.FirstSync, (await Version(f, up.VersionId)).ConflictReason);
        Assert.Equal(v1.VersionId, (await Bundle(f, v1.BundleId)).HeadVersionId);
    }

    [Fact]
    public async Task Without_fresh_the_server_record_still_makes_the_upload_based_on_the_head()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink))), null);

        var up = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "2"))), null);

        Assert.Equal(SaveUploadStatus.Accepted, up.Status);
    }

    [Fact]
    public async Task Fresh_upload_of_the_head_files_is_unchanged()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var v1 = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink))), null);

        var up = await UploadFresh(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink))), null);

        Assert.Equal(SaveUploadStatus.Unchanged, up.Status);
        Assert.Equal(v1.VersionId, up.VersionId);
    }
}

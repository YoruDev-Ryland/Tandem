using EmuSync.Protocol;
using EmuSync.Server.Data;
using EmuSync.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;

namespace EmuSync.Server.Tests;

public partial class ConfigSyncTests
{
    /// <summary>Thor uploads two versions (resolution 6, then 7); Odin has a solo config.</summary>
    private static async Task<(Setup S, ConfigUploadResponse ThorOld, ConfigUploadResponse ThorHead)> ThorWithTwoVersions(ServerFactory f)
    {
        var s = await TwoDevices(f);
        var old = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "6"))), null);
        var head = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "7"))), old.VersionId);
        await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink))), null);
        return (s, old, head);
    }

    private static Task<List<ConfigVersion>> GroupVersions(ServerFactory f, Guid bundleId) =>
        InDb(f, db => db.ConfigVersions.AsNoTracking().Where(v => v.BundleId == bundleId).OrderBy(v => v.Seq).ToListAsync());

    [Fact]
    public async Task Linked_WithOlderSourceVersion_MakesItTheGroupHead()
    {
        using var f = NewFactory();
        var (s, old, head) = await ThorWithTwoVersions(f);

        var r = await s.Web.PostJsonAsync("/v1/configs/apply", new ApplyConfigRequest(s.OdinId, "eden", old.VersionId, ApplyMode.Linked));

        Assert.True(r.IsSuccessStatusCode, await r.Content.ReadAsStringAsync());
        var applied = await r.ReadAsync<ApplyConfigResponse>();
        var bundle = await Bundle(f, applied.BundleId);
        Assert.Equal(applied.HeadVersionId, bundle.HeadVersionId);
        var newHead = await Version(f, applied.HeadVersionId);
        Assert.Equal(ConfigSource.Copy, newHead.Source);
        Assert.Equal((await Version(f, old.VersionId)).ManifestHash, newHead.ManifestHash);
        Assert.NotEqual((await Version(f, head.VersionId)).ManifestHash, newHead.ManifestHash);
        // The group's earlier head stays in history.
        Assert.Equal(2, (await GroupVersions(f, applied.BundleId)).Count);
    }

    [Fact]
    public async Task Linked_WithHeadSource_WritesNoNewVersion()
    {
        using var f = NewFactory();
        var (s, _, head) = await ThorWithTwoVersions(f);

        var applied = await (await s.Web.PostJsonAsync("/v1/configs/apply",
            new ApplyConfigRequest(s.OdinId, "eden", head.VersionId, ApplyMode.Linked))).ReadAsync<ApplyConfigResponse>();

        var versions = await GroupVersions(f, applied.BundleId);
        var only = Assert.Single(versions);
        Assert.Equal(applied.HeadVersionId, only.Id);
        Assert.Equal(ConfigSource.Join, only.Source);
        Assert.Equal((await Version(f, head.VersionId)).ManifestHash, only.ManifestHash);
    }

    [Fact]
    public async Task Linked_DeliversChosenVersionToTarget()
    {
        using var f = NewFactory();
        var (s, old, _) = await ThorWithTwoVersions(f);

        var applied = await (await s.Web.PostJsonAsync("/v1/configs/apply",
            new ApplyConfigRequest(s.OdinId, "eden", old.VersionId, ApplyMode.Linked))).ReadAsync<ApplyConfigResponse>();

        var d = Assert.Single(await Deliveries(s.Odin));
        Assert.Equal(applied.HeadVersionId, d.VersionId);
        Assert.Equal(EdenIni(OdinLink, resolution: "6"), await FileText(s.Odin, d.Manifest, Ini));
    }
}

using System.Net;
using EmuSync.Protocol;
using EmuSync.Server.Tests.Infrastructure;

namespace EmuSync.Server.Tests;

/// <summary>POST /v1/devices/me/forget for configs: the device's applied version for the emulator is dropped.</summary>
public partial class ConfigSyncTests
{
    private static async Task ForgetOk(HttpClient device, params string[] ids)
    {
        var r = await device.PostJsonAsync("/v1/devices/me/forget", new ForgetRequest(ids));
        Assert.True(r.StatusCode == HttpStatusCode.NoContent, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
    }

    [Fact]
    public async Task After_forget_other_config_files_without_a_base_conflict_and_the_same_files_are_unchanged()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var v1 = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink))), null);

        await ForgetOk(s.Thor, "eden");
        Assert.Null((await Config(f, s.ThorId)).AppliedVersionId);

        var same = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink))), null);
        Assert.Equal(SaveUploadStatus.Unchanged, same.Status);
        await ForgetOk(s.Thor, "eden");
        var other = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "2"))), null);   // no Fresh
        Assert.Equal(SaveUploadStatus.Conflict, other.Status);
        Assert.Equal(ConflictReason.FirstSync, (await Version(f, other.VersionId)).ConflictReason);
        Assert.Equal(v1.VersionId, (await Bundle(f, v1.BundleId)).HeadVersionId);
    }

    [Fact]
    public async Task Forget_leaves_other_devices_config_records()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var odin = await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink))), null);
        await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink))), null);

        await ForgetOk(s.Thor, "eden");

        Assert.Equal(odin.VersionId, (await Config(f, s.OdinId)).AppliedVersionId);
    }
}

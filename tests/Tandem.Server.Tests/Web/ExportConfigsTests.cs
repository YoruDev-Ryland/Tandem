using System.IO.Compression;
using System.Net;
using System.Text;
using Tandem.Protocol;
using Tandem.Server.Tests.Infrastructure;

namespace Tandem.Server.Tests.Web;

/// <summary>C10: export.zip also holds every config bundle head.</summary>
public class ExportConfigsTests
{
    [Fact]
    public async Task Export_includes_config_heads_under_configs_emulator_and_device_name()
    {
        using var f = new ServerFactory();
        var (thor, _) = await f.Register("a@example.com", deviceName: "AYN Thor");
        await thor.UploadSave("g", "save", null);
        var files = await Fixtures.Manifest(thor, ("config/qt-config.ini", Encoding.UTF8.GetBytes("[Renderer]\nresolution_setup=2\n")));
        var upload = await thor.PostJsonAsync("/v1/configs/upload", new ConfigUploadRequest("eden", null, files));
        Assert.Equal(HttpStatusCode.OK, upload.StatusCode);

        var web = await f.WebLogin("a@example.com");
        var response = await web.GetAsync("/v1/export.zip");
        using var zip = new ZipArchive(await response.Content.ReadAsStreamAsync(), ZipArchiveMode.Read);
        var names = zip.Entries.Select(e => e.FullName).Order(StringComparer.Ordinal).ToList();
        Assert.Contains("eden/g/save/default/data.dat", names);
        var config = Assert.Single(names, n => n.StartsWith("configs/", StringComparison.Ordinal));
        Assert.StartsWith("configs/eden/AYN Thor/", config);
        Assert.EndsWith("qt-config.ini", config);

        using var other = new ZipArchive(await (await web.GetAsync("/v1/export.zip?emulator=retroarch")).Content.ReadAsStreamAsync(),
            ZipArchiveMode.Read);
        Assert.Empty(other.Entries);
    }
}

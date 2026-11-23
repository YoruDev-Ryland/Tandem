using System.Net;
using System.Net.Http.Json;
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.Formats.Jpeg;
using SixLabors.ImageSharp.PixelFormats;
using Tandem.Protocol;
using Tandem.Server.Art;
using Tandem.Server.Tests.Infrastructure;
using static Tandem.Server.Tests.Art.ArtEndpointTests;

namespace Tandem.Server.Tests.Art;

public class SpineEndpointTests
{
    private const string Email = "spines@example.com";
    private static readonly Uri SsxScan = new("https://art.gametdb.com/wii/coverfullHQ/US/GXBE69.png");
    private static readonly Uri SsxCover = new("https://art.gametdb.com/wii/cover/US/GXBE69.png");
    private static readonly Uri PlatinumScan = new("https://art.gametdb.com/ds/coverfullHQ/US/CPUE.jpg");

    private static HttpResponseMessage Bytes(byte[] b, string type)
    {
        var content = new ByteArrayContent(b);
        content.Headers.ContentType = new(type);
        return new HttpResponseMessage(HttpStatusCode.OK) { Content = content };
    }

    private static byte[] JpegScan()
    {
        using var img = Image.Load<Rgb24>(SpineCutterTests.Scan(1616, 680, 766, 836));
        using var ms = new MemoryStream();
        img.Save(ms, new JpegEncoder { Quality = 95 });
        return ms.ToArray();
    }

    [Fact]
    public async Task Real_spine_is_pending_then_a_cut_image()
    {
        var sources = new FakeSources(u =>
            u == SsxScan ? Bytes(SpineCutterTests.Scan(1024, 680, 487, 537), "image/png")
            : u == SsxCover ? FakeSources.Image(FakePng())
            : FakeSources.NotFound());
        using var f = Factory(sources);
        var (device, _) = await f.Register(Email);
        await device.UploadSave("GXBE69", "card", null, "dolphin");

        var first = await device.GetAsync("/v1/web/spine/dolphin/GXBE69");
        Assert.Equal(HttpStatusCode.Accepted, first.StatusCode);
        Assert.Equal("spine_pending", await ErrorCode(first));
        Assert.Equal("3", first.Headers.GetValues("Retry-After").Single());

        await Drain(f);
        var second = await device.GetAsync("/v1/web/spine/dolphin/GXBE69");
        Assert.Equal(HttpStatusCode.OK, second.StatusCode);
        Assert.Equal("image/png", second.Content.Headers.ContentType!.MediaType);
        Assert.Equal("private, max-age=604800", CacheControl(second));
        using (var img = Image.Load<Rgb24>(await second.Content.ReadAsByteArrayAsync()))
            Assert.Equal((50, 680), (img.Width, img.Height));

        // The cover is its own fetch and its own file.
        Assert.Equal(HttpStatusCode.Accepted, (await device.GetAsync("/v1/web/art/dolphin/GXBE69")).StatusCode);
        await Drain(f);
        var cover = await device.GetAsync("/v1/web/art/dolphin/GXBE69");
        Assert.Equal(HttpStatusCode.OK, cover.StatusCode);
        Assert.Equal(FakePng(), await cover.Content.ReadAsByteArrayAsync());
    }

    [Fact]
    public async Task Ds_spine_uses_the_reported_id()
    {
        var sources = new FakeSources(u => u == PlatinumScan ? Bytes(JpegScan(), "image/jpeg") : FakeSources.NotFound());
        using var f = Factory(sources);
        var (device, _) = await f.Register(Email);
        await device.PutAsJsonAsync("/v1/devices/me/installs/melonds",
            new InstallsRequest([new InstalledGame("nds/Pokemon - Platinum Version (US)", null, null, null, "CPUE")]));

        const string url = "/v1/web/spine/melonds/nds/Pokemon%20-%20Platinum%20Version%20(US)";
        Assert.Equal(HttpStatusCode.Accepted, (await device.GetAsync(url)).StatusCode);
        await Drain(f);
        var r = await device.GetAsync(url);

        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        using var img = Image.Load<Rgb24>(await r.Content.ReadAsByteArrayAsync());
        Assert.Equal((68, 680), (img.Width, img.Height));
    }

    [Fact]
    public async Task A_game_without_a_spine_is_no_spine()
    {
        var sources = new FakeSources(_ => FakeSources.Image(FakePng()));
        using var f = Factory(sources);
        var (device, _) = await f.Register(Email);
        await device.UploadSave("0100F2C0115B6000", "sav", null, "eden");
        await device.PutAsJsonAsync("/v1/devices/me/installs/melonds", new InstallsRequest([new InstalledGame("nds/NoId", null, null)]));

        var eden = await device.GetAsync("/v1/web/spine/eden/0100F2C0115B6000");
        Assert.Equal(HttpStatusCode.NotFound, eden.StatusCode);
        Assert.Equal("no_spine", await ErrorCode(eden));
        var noId = await device.GetAsync("/v1/web/spine/melonds/nds/NoId");
        Assert.Equal("no_spine", await ErrorCode(noId));
        Assert.Equal(0, sources.Calls);

        using var off = Factory(sources, enabled: false);
        var (d2, _) = await off.Register(Email);
        await d2.UploadSave("GXBE69", "card", null, "dolphin");
        var r = await d2.GetAsync("/v1/web/spine/dolphin/GXBE69");
        Assert.Equal(HttpStatusCode.NotFound, r.StatusCode);
        Assert.Equal("no_spine", await ErrorCode(r));
    }

    [Fact]
    public async Task A_scan_that_cannot_be_cut_is_no_spine()
    {
        var sources = new FakeSources(u => u.AbsolutePath.Contains("coverfullHQ") ? FakeSources.Image(SquarePng()) : FakeSources.NotFound());
        using var f = Factory(sources);
        var (device, _) = await f.Register(Email);
        await device.UploadSave("GXBE69", "card", null, "dolphin");

        Assert.Equal(HttpStatusCode.Accepted, (await device.GetAsync("/v1/web/spine/dolphin/GXBE69")).StatusCode);
        await Drain(f);
        var calls = sources.Calls;
        var r = await device.GetAsync("/v1/web/spine/dolphin/GXBE69");

        Assert.Equal(HttpStatusCode.NotFound, r.StatusCode);
        Assert.Equal("no_spine", await ErrorCode(r));
        await Drain(f);
        Assert.Equal(calls, sources.Calls);
    }

    private static byte[] SquarePng() => SpineCutterTests.Scan(512, 512, 0, 0);

    [Fact]
    public async Task Another_users_game_is_not_found_for_spines()
    {
        var sources = new FakeSources(_ => FakeSources.Image(FakePng()));
        using var f = Factory(sources);
        f.Settings["Tandem:AllowRegistration"] = "true";
        var (owner, _) = await f.Register(Email);
        var (other, _) = await f.Register("other@example.com");
        await owner.UploadSave("GXBE69", "card", null, "dolphin");

        var r = await other.GetAsync("/v1/web/spine/dolphin/GXBE69");

        Assert.Equal(HttpStatusCode.NotFound, r.StatusCode);
        Assert.Equal("not_found", await ErrorCode(r));
        Assert.Equal(0, sources.Calls);
    }
}

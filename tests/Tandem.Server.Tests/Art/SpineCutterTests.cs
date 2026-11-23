using SixLabors.ImageSharp;
using SixLabors.ImageSharp.Formats.Png;
using SixLabors.ImageSharp.PixelFormats;
using Tandem.Server.Art;

namespace Tandem.Server.Tests.Art;

public class SpineCutterTests
{
    private static readonly Rgb24 Grey = new(128, 128, 128);
    private static readonly Rgb24 Red = new(0xC0, 0, 0);

    /// <summary>A generated "case scan": grey back and front with a red spine strip at [from, to).</summary>
    internal static byte[] Scan(int width, int height, int from, int to, Rgb24? spine = null, Rgb24? rest = null)
    {
        using var img = new Image<Rgb24>(width, height, rest ?? Grey);
        img.ProcessPixelRows(rows =>
        {
            for (var y = 0; y < rows.Height; y++)
            {
                var row = rows.GetRowSpan(y);
                for (var x = from; x < to; x++) row[x] = spine ?? Red;
            }
        });
        using var ms = new MemoryStream();
        img.Save(ms, new PngEncoder());
        return ms.ToArray();
    }

    private static Image<Rgb24> Decode(byte[] png) => Image.Load<Rgb24>(png);

    private static void AllNear(Image<Rgb24> img, Rgb24 want, int tolerance = 8)
    {
        img.ProcessPixelRows(rows =>
        {
            for (var y = 0; y < rows.Height; y++)
                foreach (var p in rows.GetRowSpan(y))
                    Assert.True(Math.Abs(p.R - want.R) <= tolerance && Math.Abs(p.G - want.G) <= tolerance && Math.Abs(p.B - want.B) <= tolerance,
                        $"pixel {p} at row {y} is not near {want}");
        });
    }

    [Fact]
    public void Cuts_the_strip_between_the_edges()
    {
        var png = SpineCutter.Cut(Scan(1024, 680, 487, 537), "GC");
        Assert.NotNull(png);
        using var img = Decode(png!);
        Assert.Equal((50, 680), (img.Width, img.Height));
        AllNear(img, Red);
    }

    [Fact]
    public void Finds_an_off_centre_spine()
    {
        var png = SpineCutter.Cut(Scan(1616, 680, 766, 836), "DS");
        Assert.NotNull(png);
        using var img = Decode(png!);
        Assert.Equal((68, 680), (img.Width, img.Height));
        AllNear(img, Red);
    }

    [Fact]
    public void Falls_back_to_the_expected_strip_without_edges()
    {
        // One flat colour with a 1-level step at x 500: no edge strong enough to trust.
        var flat = new Rgb24(90, 100, 110);
        var png = SpineCutter.Cut(Scan(1024, 680, 500, 1024, new Rgb24(91, 100, 110), flat), "GC");
        Assert.NotNull(png);
        using var img = Decode(png!);
        Assert.Equal((50, 680), (img.Width, img.Height));
        AllNear(img, flat, tolerance: 2);
    }

    [Fact]
    public void Rejects_images_that_are_not_a_full_wrap()
    {
        Assert.Null(SpineCutter.Cut(Scan(512, 512, 0, 0), "GC"));
        Assert.Null(SpineCutter.Cut(Scan(600, 250, 0, 0), "GC"));
        Assert.Null(SpineCutter.Cut("not an image"u8.ToArray(), "GC"));
    }

    [Fact]
    public void Output_sizes_follow_the_case_ratios()
    {
        Assert.Equal(50, SpineCutter.OutputWidth("GC"));
        Assert.Equal(50, SpineCutter.OutputWidth("WII"));
        Assert.Equal(68, SpineCutter.OutputWidth("DS"));
        Assert.Equal(68, SpineCutter.OutputWidth("3DS"));
        Assert.Equal(0.0735, SpineCutter.ScanRatio("GC"));
        Assert.Equal(0.103, SpineCutter.ScanRatio("3DS"));
    }
}

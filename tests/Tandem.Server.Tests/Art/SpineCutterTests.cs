using SkiaSharp;
using Tandem.Server.Art;

namespace Tandem.Server.Tests.Art;

public class SpineCutterTests
{
    private static readonly SKColor Grey = new(128, 128, 128);
    private static readonly SKColor Red = new(0xC0, 0, 0);

    /// <summary>A generated "case scan": grey back and front with a red spine strip at [from, to).</summary>
    internal static byte[] Scan(int width, int height, int from, int to, SKColor? spine = null, SKColor? rest = null,
        SKEncodedImageFormat format = SKEncodedImageFormat.Png)
    {
        using var bmp = new SKBitmap(new SKImageInfo(width, height, SKColorType.Rgba8888, SKAlphaType.Opaque));
        using (var canvas = new SKCanvas(bmp))
        {
            canvas.Clear(rest ?? Grey);
            if (to > from) canvas.DrawRect(SKRect.Create(from, 0, to - from, height), new SKPaint { Color = spine ?? Red, IsAntialias = false });
        }
        using var image = SKImage.FromBitmap(bmp);
        using var data = image.Encode(format, 95);
        return data.ToArray();
    }

    internal static SKBitmap Decode(byte[] png) => SKBitmap.Decode(png);

    private static void AllNear(SKBitmap img, SKColor want, int tolerance = 8)
    {
        for (var y = 0; y < img.Height; y++)
            for (var x = 0; x < img.Width; x++)
            {
                var p = img.GetPixel(x, y);
                Assert.True(Math.Abs(p.Red - want.Red) <= tolerance && Math.Abs(p.Green - want.Green) <= tolerance && Math.Abs(p.Blue - want.Blue) <= tolerance,
                    $"pixel {p} at ({x},{y}) is not near {want}");
            }
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
        var flat = new SKColor(90, 100, 110);
        var png = SpineCutter.Cut(Scan(1024, 680, 500, 1024, new SKColor(91, 100, 110), flat), "GC");
        Assert.NotNull(png);
        using var img = Decode(png!);
        Assert.Equal((50, 680), (img.Width, img.Height));
        AllNear(img, flat, tolerance: 2);
    }

    [Fact]
    public void Keeps_the_whole_height_of_the_spine()
    {
        // A GC scan whose spine has a green top row and a blue bottom row: both must survive the cut.
        var bytes = Scan(1024, 680, 487, 537);
        using var bmp = SKBitmap.Decode(bytes);
        using (var c = new SKCanvas(bmp))
        {
            c.DrawRect(SKRect.Create(487, 0, 50, 12), new SKPaint { Color = new SKColor(0, 200, 0) });
            c.DrawRect(SKRect.Create(487, 668, 50, 12), new SKPaint { Color = new SKColor(0, 0, 200) });
        }
        using var img = SKImage.FromBitmap(bmp);
        using var data = img.Encode(SKEncodedImageFormat.Png, 100);
        using var cut = Decode(SpineCutter.Cut(data.ToArray(), "GC")!);
        var top = cut.GetPixel(25, 2);
        var bottom = cut.GetPixel(25, cut.Height - 3);
        Assert.True(top.Green > 150 && top.Red < 60, $"top row is {top}");
        Assert.True(bottom.Blue > 150 && bottom.Red < 60, $"bottom row is {bottom}");
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

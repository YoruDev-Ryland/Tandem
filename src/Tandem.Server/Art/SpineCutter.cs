using SkiaSharp;

namespace Tandem.Server.Art;

/// <summary>
/// Cuts the spine strip out of a full case scan (back, spine, front side by side, as GameTDB's coverfullHQ images are)
/// and returns it as a PNG of <see cref="OutputWidth"/> × <see cref="OutputHeight"/>.
/// </summary>
public static class SpineCutter
{
    public const int OutputHeight = 680;

    /// <summary>An edge has to change brightness by at least this much (0–255, averaged down the scan) to be trusted.</summary>
    private const double MinEdge = 4.0;

    /// <summary>The spine's expected width in the scan, as a fraction of the scan's height.</summary>
    public static double ScanRatio(string system) => system is "DS" or "3DS" ? 0.103 : 0.0735;

    /// <summary>The output width: the case ratio (GC and WII 0.074, DS and 3DS 0.10) × <see cref="OutputHeight"/>.</summary>
    public static int OutputWidth(string system) =>
        (int)Math.Round(OutputHeight * (system is "DS" or "3DS" ? 0.10 : 0.074));

    /// <summary>The largest scan decoded, in pixels: a decompression bomb is refused before it is decoded.</summary>
    private const long MaxPixels = 16L * 1024 * 1024;

    /// <summary>The spine as a PNG, or null when the bytes aren't a usable full-wrap scan.</summary>
    public static byte[]? Cut(byte[] scan, string system)
    {
        using var img = Decode(scan);
        if (img is null || img.Height < 300 || (double)img.Width / img.Height < 1.2) return null;

        var (left, right) = FindStrip(img, ScanRatio(system));
        var trim = Math.Max(2, (int)Math.Round(0.04 * (right - left)));
        left += trim;
        right -= trim;
        if (right - left < 4) return null;

        // Scale the strip to cover the output box, keeping its aspect, then take the middle.
        var outW = OutputWidth(system);
        float cropW = right - left, cropH = img.Height;
        var scale = Math.Max(outW / cropW, OutputHeight / cropH);
        float srcW = outW / scale, srcH = OutputHeight / scale;
        var src = SKRect.Create(left + (cropW - srcW) / 2, (cropH - srcH) / 2, srcW, srcH);

        using var surface = SKSurface.Create(new SKImageInfo(outW, OutputHeight, SKColorType.Rgba8888, SKAlphaType.Opaque));
        using (var image = SKImage.FromBitmap(img))
            surface.Canvas.DrawImage(image, src, SKRect.Create(0, 0, outW, OutputHeight), new SKSamplingOptions(SKFilterMode.Linear, SKMipmapMode.None));
        using var snap = surface.Snapshot();
        using var png = snap.Encode(SKEncodedImageFormat.Png, 100);
        return png.ToArray();
    }

    /// <summary>PNG or JPEG bytes as an opaque RGBA bitmap, or null when they aren't a decodable image.</summary>
    private static SKBitmap? Decode(byte[] bytes)
    {
        using var data = SKData.CreateCopy(bytes);
        using var codec = SKCodec.Create(data);
        if (codec is null) return null;
        var info = codec.Info;
        if (info.Width <= 0 || info.Height <= 0 || (long)info.Width * info.Height > MaxPixels) return null;
        var want = new SKImageInfo(info.Width, info.Height, SKColorType.Rgba8888, SKAlphaType.Opaque);
        var bmp = new SKBitmap(want);
        var r = codec.GetPixels(want, bmp.GetPixels());
        if (r is SKCodecResult.Success or SKCodecResult.IncompleteInput) return bmp;
        bmp.Dispose();
        return null;
    }

    /// <summary>
    /// The spine's columns [left, right): the strongest vertical edge in each window either side of the centre, when
    /// both are clear and the width is plausible; otherwise the expected strip around the centre.
    /// </summary>
    private static (int Left, int Right) FindStrip(SKBitmap img, double ratio)
    {
        var w = img.Height * ratio;
        var c = img.Width / 2.0;
        var edges = EdgeStrength(img);

        var left = Peak(edges, c - 0.75 * w, c - 0.25 * w);
        var right = Peak(edges, c + 0.25 * w, c + 0.75 * w);
        if (left is { } l && right is { } r && r - l >= 0.8 * w && r - l <= 1.2 * w) return (l, r);

        var half = w / 2;
        return ((int)Math.Round(c - half), (int)Math.Round(c + half));
    }

    /// <summary>E(x): the mean over rows of |L(x, y) − L(x − 1, y)|, with L the luminance. E(0) is 0.</summary>
    private static double[] EdgeStrength(SKBitmap img)
    {
        var sums = new double[img.Width];
        var px = img.GetPixelSpan();   // RGBA, 4 bytes a pixel, rows packed (RowBytes = Width × 4)
        var stride = img.RowBytes;
        for (var y = 0; y < img.Height; y++)
        {
            var row = px.Slice(y * stride, img.Width * 4);
            var prev = Luma(row[0], row[1], row[2]);
            for (var x = 1; x < img.Width; x++)
            {
                var l = Luma(row[x * 4], row[x * 4 + 1], row[x * 4 + 2]);
                sums[x] += Math.Abs(l - prev);
                prev = l;
            }
        }
        for (var x = 0; x < sums.Length; x++) sums[x] /= img.Height;
        return sums;
    }

    private static double Luma(byte r, byte g, byte b) => 0.299 * r + 0.587 * g + 0.114 * b;

    /// <summary>The column of the strongest edge in [from, to], when it is at least twice the window's median and
    /// clears <see cref="MinEdge"/>; otherwise null.</summary>
    private static int? Peak(double[] edges, double from, double to)
    {
        var lo = Math.Clamp((int)Math.Round(from), 1, edges.Length - 1);
        var hi = Math.Clamp((int)Math.Round(to), 1, edges.Length - 1);
        if (hi <= lo) return null;
        var window = edges[lo..(hi + 1)];
        var best = lo;
        for (var x = lo; x <= hi; x++) if (edges[x] > edges[best]) best = x;
        var sorted = window.Order().ToArray();
        var median = sorted[sorted.Length / 2];
        return edges[best] >= MinEdge && edges[best] >= 2 * median ? best : null;
    }
}

using SixLabors.ImageSharp;
using SixLabors.ImageSharp.Formats.Png;
using SixLabors.ImageSharp.PixelFormats;
using SixLabors.ImageSharp.Processing;

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

    /// <summary>The spine as a PNG, or null when the bytes aren't a usable full-wrap scan.</summary>
    public static byte[]? Cut(byte[] scan, string system)
    {
        Image<Rgb24> img;
        try { img = Image.Load<Rgb24>(scan); }
        catch (Exception e) when (e is UnknownImageFormatException or InvalidImageContentException or NotSupportedException) { return null; }
        using (img)
        {
            if (img.Height < 300 || (double)img.Width / img.Height < 1.2) return null;

            var (left, right) = FindStrip(img, ScanRatio(system));
            var trim = Math.Max(2, (int)Math.Round(0.04 * (right - left)));
            left += trim;
            right -= trim;
            if (right - left < 4) return null;

            var outW = OutputWidth(system);
            img.Mutate(x => x
                .Crop(new Rectangle(left, 0, right - left, img.Height))
                .Resize(new ResizeOptions { Size = new Size(outW, OutputHeight), Mode = ResizeMode.Crop }));
            using var ms = new MemoryStream();
            img.Save(ms, new PngEncoder());
            return ms.ToArray();
        }
    }

    /// <summary>
    /// The spine's columns [left, right): the strongest vertical edge in each window either side of the centre, when
    /// both are clear and the width is plausible; otherwise the expected strip around the centre.
    /// </summary>
    private static (int Left, int Right) FindStrip(Image<Rgb24> img, double ratio)
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
    private static double[] EdgeStrength(Image<Rgb24> img)
    {
        var sums = new double[img.Width];
        img.ProcessPixelRows(rows =>
        {
            for (var y = 0; y < rows.Height; y++)
            {
                var row = rows.GetRowSpan(y);
                var prev = Luma(row[0]);
                for (var x = 1; x < row.Length; x++)
                {
                    var l = Luma(row[x]);
                    sums[x] += Math.Abs(l - prev);
                    prev = l;
                }
            }
        });
        for (var x = 0; x < sums.Length; x++) sums[x] /= img.Height;
        return sums;
    }

    private static double Luma(Rgb24 p) => 0.299 * p.R + 0.587 * p.G + 0.114 * p.B;

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

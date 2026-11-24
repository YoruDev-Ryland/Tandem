using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Tandem.Protocol;
using Tandem.Server.Blobs;
using Tandem.Server.Data;
using Tandem.Server.Http;

namespace Tandem.Server.Tests.Blobs;

public class MoveTargetCheckTests : IDisposable
{
    private readonly string _tmp = Directory.CreateTempSubdirectory("tandem-movecheck-").FullName;
    private readonly FakeMounts _mounts = new();
    private readonly FakeProbe _probe = new();
    private readonly ServerOptions _options;
    private readonly FilesLocation _files;

    public MoveTargetCheckTests()
    {
        _options = new ServerOptions { DataDirectory = Path.Combine(_tmp, "data"), MinFreeBytes = 1000 };
        _files = new FilesLocation(Options.Create(_options));
    }

    public void Dispose() => Directory.Delete(_tmp, true);

    private sealed class FakeMounts : IMountTable
    {
        public bool InContainer { get; set; }
        public string? Mount { get; set; } = "/mnt/nas";
        public string? MountPointOf(string path) => Mount;
    }

    private sealed class FakeProbe : IFreeSpaceProbe
    {
        public long? Free { get; set; } = 1L << 40;
        public long? FreeBytes(string directory) => Free;
        public long? TotalBytes(string directory) => Free;
    }

    private MoveCheckResult Run(string path, long bytes = 0) =>
        new MoveTargetCheck(_files, _probe, _mounts, Options.Create(_options)).Run(path, bytes);

    private async Task UseCustomLocation(string path)
    {
        await using var ctx = new SqliteTandemDbContext(new DbContextOptionsBuilder<SqliteTandemDbContext>()
            .UseSqlite($"Data Source={_tmp}/t.db;Pooling=False").Options);
        await ctx.Database.EnsureCreatedAsync();
        await _files.SwitchAsync(ctx, path, Guid.NewGuid(), default);
        Assert.False(_files.IsDefault);
    }

    private string Target(string name = "target") => Path.Combine(_tmp, name);

    private static MoveCheckItem Item(MoveCheckResult r, string name) => r.Items.Single(i => i.Name == name);

    [Fact]
    public void Check_accepts_an_empty_writable_mounted_folder()
    {
        _mounts.InContainer = true;
        var dir = Target();
        Directory.CreateDirectory(dir);
        var r = Run(dir, 5);
        Assert.True(r.Ok);
        Assert.Equal(5, r.BytesToCopy);
        Assert.Equal(["absolute", "outside", "writable", "space", "mount", "empty"], r.Items.Select(i => i.Name));
        Assert.All(r.Items, i => { Assert.True(i.Ok); Assert.Null(i.Problem); });
        Assert.Null(r.MountLine);
        Assert.Empty(Directory.GetFileSystemEntries(dir));
    }

    [Fact]
    public void Check_creates_a_missing_folder_and_removes_it_again_when_the_check_fails()
    {
        _probe.Free = 10;
        var dir = Path.Combine(_tmp, "new", "deep");
        var r = Run(dir, 5);
        Assert.False(r.Ok);
        Assert.False(Directory.Exists(dir));
        _probe.Free = 1L << 40;
        Assert.True(Run(dir, 5).Ok);
        Assert.True(Directory.Exists(dir));
    }

    [Fact]
    public void Check_refuses_a_relative_path()
    {
        var r = Run("relative/folder");
        Assert.False(r.Ok);
        Assert.Equal("Use a full path, starting with /.", Item(r, "absolute").Problem);
        Assert.False(Item(r, "absolute").Ok);
        Assert.False(Directory.Exists("relative/folder"));
    }

    [Fact]
    public void Check_refuses_a_folder_inside_the_current_location()
    {
        var r = Run(Path.Combine(_files.Root, "sub"));
        Assert.False(r.Ok);
        Assert.Equal("The new folder can't be inside the current one, or hold it.", Item(r, "outside").Problem);
        Assert.False(Directory.Exists(Path.Combine(_files.Root, "sub")));
    }

    [Fact]
    public void Check_refuses_the_current_location_inside_the_target()
    {
        var r = Run(_options.DataDirectory);
        Assert.False(Item(r, "outside").Ok);
        Assert.False(r.Ok);
    }

    [Fact]
    public async Task Check_does_not_treat_a_sibling_with_the_same_prefix_as_inside()
    {
        await UseCustomLocation(Target("cur"));
        Assert.True(Item(Run(Target("cur2")), "outside").Ok);
        Assert.False(Item(Run(Target("cur") + "/x"), "outside").Ok);
    }

    [Fact]
    public void Check_refuses_a_folder_that_is_not_a_mount_in_a_container()
    {
        _mounts.InContainer = true;
        _mounts.Mount = "/";
        var dir = Target();
        var r = Run(dir);
        Assert.False(r.Ok);
        Assert.Equal("This folder is inside the container and would be lost on the next update. Mount a folder there first:",
            Item(r, "mount").Problem);
        Assert.Equal($"- /your/host/folder:{dir}", r.MountLine);
    }

    [Fact]
    public void Check_skips_the_mount_rule_outside_a_container()
    {
        _mounts.InContainer = false;
        _mounts.Mount = "/";
        var r = Run(Target());
        Assert.True(Item(r, "mount").Ok);
        Assert.True(r.Ok);
        Assert.Null(r.MountLine);
    }

    [Fact]
    public void Check_refuses_a_non_empty_folder()
    {
        var dir = Target();
        Directory.CreateDirectory(dir);
        File.WriteAllText(Path.Combine(dir, "x.txt"), "x");
        var r = Run(dir);
        Assert.False(r.Ok);
        Assert.Equal("The folder already holds other files. Pick an empty folder.", Item(r, "empty").Problem);
        Assert.True(Item(r, "writable").Ok);
    }

    [Fact]
    public void Check_accepts_a_folder_holding_only_a_marker_from_a_cancelled_move()
    {
        var dir = Target();
        FilesLocation.WriteMarker(dir, Guid.NewGuid());
        Assert.True(Run(dir).Ok);
        File.WriteAllText(Path.Combine(dir, "x.txt"), "x");
        Assert.False(Item(Run(dir), "empty").Ok);
    }

    [Fact]
    public void Check_refuses_when_free_space_is_below_bytes_plus_the_floor()
    {
        _probe.Free = 1499;
        var r = Run(Target(), 500);
        Assert.False(r.Ok);
        Assert.Equal("Not enough free space: 1.5 KB needed, 1.5 KB free.", Item(r, "space").Problem);
        _probe.Free = 1500;
        Assert.True(Item(Run(Target(), 500), "space").Ok);
    }

    [Fact]
    public async Task Check_allows_moving_back_to_the_default_location()
    {
        await UseCustomLocation(Target("custom"));
        var r = Run(_files.DefaultRoot);
        Assert.True(Item(r, "outside").Ok);
        Assert.True(r.Ok);
    }

    [Fact]
    public void Check_refuses_a_folder_inside_the_data_directory_other_than_the_default_location()
    {
        Assert.False(Item(Run(Path.Combine(_options.DataDirectory, "elsewhere")), "outside").Ok);
    }

    [Theory]
    [InlineData(0, "0 B")]
    [InlineData(1023, "1023 B")]
    [InlineData(1024, "1.0 KB")]
    [InlineData(82_800_000, "79.0 MB")]
    [InlineData(1_288_490_189, "1.2 GB")]
    [InlineData(3L << 40, "3.0 TB")]
    public void HumanSize_formats_with_one_decimal(long bytes, string expected) =>
        Assert.Equal(expected, HumanSize.Format(bytes));
}

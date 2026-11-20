using EmuSync.Server.Configs;

namespace EmuSync.Server.Tests;

public class PathTokenTests
{
    private static readonly Dictionary<string, string> Roots = new()
    {
        ["{INTERNAL}"] = "/storage/emulated/0",
        ["{SD}"] = "/storage/1234-ABCD/",
        ["{EMU:eden}"] = "/storage/emulated/0/Android/data/dev.eden.eden_emulator/files",
    };

    [Fact]
    public void Tokenize_longest_root_wins()
    {
        var r = PathTokens.Tokenize("/storage/emulated/0/Android/data/dev.eden.eden_emulator/files/nand", Roots);
        Assert.Equal("{EMU:eden}/nand", r.Value);
        Assert.False(r.DeviceLocal);
        Assert.Equal("{INTERNAL}/Games/x", PathTokens.Tokenize("/storage/emulated/0/Games/x", Roots).Value);
    }

    [Fact]
    public void Tokenize_requires_path_boundary()
    {
        var r = PathTokens.Tokenize("/storage/emulated/01/Games", Roots);
        Assert.True(r.DeviceLocal);
        Assert.Equal("/storage/emulated/01/Games", r.Value);
    }

    [Fact]
    public void Tokenize_root_with_trailing_slash_and_exact_root()
    {
        Assert.Equal("{SD}/ROMs/psx", PathTokens.Tokenize("/storage/1234-ABCD/ROMs/psx", Roots).Value);
        Assert.Equal("{SD}", PathTokens.Tokenize("/storage/1234-ABCD", Roots).Value);
        Assert.Equal("{INTERNAL}", PathTokens.Tokenize("/storage/emulated/0", Roots).Value);
    }

    [Fact]
    public void Tokenize_is_case_sensitive()
    {
        Assert.True(PathTokens.Tokenize("/Storage/Emulated/0/x", Roots).DeviceLocal);
    }

    [Fact]
    public void Content_uri_is_device_local()
    {
        const string uri = "content://com.android.externalstorage.documents/tree/primary%3AGames%2Fswitch";
        var r = PathTokens.Tokenize(uri, Roots);
        Assert.True(r.DeviceLocal);
        Assert.Equal(uri, r.Value);
    }

    [Fact]
    public void Path_outside_roots_is_device_local()
    {
        var r = PathTokens.Tokenize("/mnt/other/roms", Roots);
        Assert.True(r.DeviceLocal);
        Assert.Equal("/mnt/other/roms", r.Value);
    }

    [Fact]
    public void Non_path_is_untouched_and_not_device_local()
    {
        var r = PathTokens.Tokenize("Vulkan", Roots);
        Assert.False(r.DeviceLocal);
        Assert.Equal("Vulkan", r.Value);
    }

    [Fact]
    public void Quotes_survive_tokenize_and_expand()
    {
        Assert.True(PathTokens.IsPath("\"/storage/x\""));
        Assert.True(PathTokens.IsPath("'content://a'"));
        Assert.True(PathTokens.IsPath("~/x"));
        Assert.True(PathTokens.IsPath("C:\\Games"));
        Assert.True(PathTokens.IsPath("D:/Games"));
        Assert.False(PathTokens.IsPath("\"/x'"));
        Assert.False(PathTokens.IsPath("Vulkan"));
        Assert.False(PathTokens.IsPath("C:"));
        var t = PathTokens.Tokenize("\"/storage/emulated/0/Games\"", Roots);
        Assert.Equal("\"{INTERNAL}/Games\"", t.Value);
        Assert.Equal("\"/storage/emulated/0/Games\"", PathTokens.Expand(t.Value, Roots));
    }

    [Fact]
    public void Expand_replaces_tokens()
    {
        Assert.Equal("/storage/1234-ABCD/ROMs/psx", PathTokens.Expand("{SD}/ROMs/psx", Roots));
        Assert.Equal("/storage/emulated/0", PathTokens.Expand("{INTERNAL}", Roots));
        Assert.Equal("/plain", PathTokens.Expand("/plain", Roots));
    }

    [Fact]
    public void Expand_unknown_token_returns_null()
    {
        Assert.Null(PathTokens.Expand("{EMU:ppsspp}/x", Roots));
        Assert.Null(PathTokens.Expand("{INTERNAL}/a/{SD2}/b", Roots));
    }
}

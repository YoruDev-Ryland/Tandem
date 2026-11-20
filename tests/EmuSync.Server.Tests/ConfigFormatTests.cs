using EmuSync.Server.Configs;
using EmuSync.Server.Configs.Formats;

namespace EmuSync.Server.Tests;

public class ConfigFormatTests
{
    private static string Fixture(string name) =>
        File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Fixtures", "configs", name));

    private static string Apply(string text, ConfigValue v, string replacement) =>
        text[..v.Start] + replacement + text[(v.Start + v.Length)..];

    [Fact]
    public void Ini_replace_changes_only_target_span()
    {
        var text = Fixture("eden-thor.ini");
        var fmt = new IniFormat();
        var values = fmt.Read(text);

        var save = values.Single(v => v.Key == @"Data%20Storage/save_directory");
        Assert.Equal("/storage/emulated/0/Games/switch-saves", save.Value);
        Assert.Equal(save.Value, text.Substring(save.Start, save.Length));

        var dir = values.Single(v => v.Key.EndsWith(@"/Paths\gamedirs\1\path"));
        Assert.Equal("\"content://com.android.externalstorage.documents/tree/primary%3AGames%2Fswitch\"", dir.Value);

        var result = fmt.Replace(text, new Dictionary<string, string> { [save.Key] = "{INTERNAL}/Games/saves" });
        Assert.Equal(Apply(text, save, "{INTERNAL}/Games/saves"), result);
    }

    [Fact]
    public void Ini_keys_with_spaces_comments_and_empty_values()
    {
        const string text = "; c\r\n[A B]\r\nk = v \r\nempty = \r\nnoeq\r\n# x=y\r\n[C]\r\nz=\"q\"\r\n";
        var fmt = new IniFormat();
        var v = fmt.Read(text);
        Assert.Equal(new[] { "A B/k", "A B/empty", "C/z" }, v.Select(x => x.Key));
        Assert.Equal(new[] { "v", "", "\"q\"" }, v.Select(x => x.Value));
        Assert.Equal("; c\r\n[A B]\r\nk = NEW \r\nempty = E\r\nnoeq\r\n# x=y\r\n[C]\r\nz=\"q\"\r\n",
            fmt.Replace(text, new Dictionary<string, string> { ["A B/k"] = "NEW", ["A B/empty"] = "E" }));
    }

    [Fact]
    public void RetroArch_cfg_reads_quoted_values()
    {
        const string text = "# comment\r\nsavefile_directory = \"/storage/emulated/0/RetroArch/saves\"\r\nvideo_vsync = \"true\"\r\n";
        var fmt = new RetroArchCfgFormat();
        var v = fmt.Read(text);
        Assert.Equal(2, v.Count);
        Assert.Equal("savefile_directory", v[0].Key);
        Assert.Equal("/storage/emulated/0/RetroArch/saves", v[0].Value);
        Assert.Equal(v[0].Value, text.Substring(v[0].Start, v[0].Length));
        var r = fmt.Replace(text, new Dictionary<string, string> { ["savefile_directory"] = "{INTERNAL}/RA/saves" });
        Assert.Equal(text.Replace("/storage/emulated/0/RetroArch/saves", "{INTERNAL}/RA/saves"), r);
    }

    [Fact]
    public void RetroArch_real_fixture_reads_paths()
    {
        var v = new RetroArchCfgFormat().Read(Fixture("retroarch-odin.cfg"));
        Assert.True(v.Count > 1000);
        Assert.Contains(v, x => x.Key == "cheevos_password" && x.Value == "");
    }

    [Fact]
    public void Yaml_nested_keys_dotted()
    {
        var text = Fixture("vita3k-config.yml");
        var fmt = new YamlLineFormat();
        var v = fmt.Read(text).ToDictionary(x => x.Key, x => x.Value);
        Assert.Equal("/storage/emulated/0/Android/data/org.vita3k.emulator/files/vita/", v["pref-path"]);
        Assert.Equal("true", v["cpu.cpu-opt"]);
        Assert.Equal("default", v["gui.theme-id"]);
        Assert.Equal("content://com.android.externalstorage.documents/tree/primary%3AVita%2Fbg", v["gui.paths.background"]);
        Assert.Equal("00", v["user-id"]);
        Assert.Equal("end", v["last"]);
        Assert.DoesNotContain(v.Keys, k => k.Contains("SceLibc") || k.StartsWith("notes.") || k == "notes" || k.Contains("multi"));
        Assert.DoesNotContain("lle-modules", v.Keys);
        var r = fmt.Replace(text, new Dictionary<string, string> { ["gui.theme-id"] = "dark", ["user-id"] = "01" });
        Assert.Equal(text.Replace("theme-id: default ", "theme-id: dark ").Replace("\"00\"", "\"01\""), r);
    }

    private const string Xml =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n<!-- c <x a=\"1\"/> -->\r\n<config version=\"2\">\r\n  <paths>\r\n" +
        "    <dir name='roms' path=\"/storage/emulated/0/ROMs?a=1&amp;b=2\"/>\r\n    <dir name=\"two\">/storage/x &lt;y&gt;</dir>\r\n" +
        "    <empty></empty>\r\n  </paths>\r\n  <cd><![CDATA[ignored]]></cd>\r\n</config>\r\n";

    [Fact]
    public void Xml_attribute_and_text_keys()
    {
        var fmt = new XmlFormat();
        var v = fmt.Read(Xml).ToDictionary(x => x.Key, x => x.Value);
        Assert.Equal("2", v["/config/@version"]);
        Assert.Equal("roms", v["/config/paths/dir/@name"]);
        Assert.Equal("/storage/emulated/0/ROMs?a=1&b=2", v["/config/paths/dir/@path"]);
        Assert.Equal("two", v["/config/paths/dir[2]/@name"]);
        Assert.Equal("/storage/x <y>", v["/config/paths/dir[2]"]);
        Assert.DoesNotContain("/config/cd", v.Keys);
        var r = fmt.Replace(Xml, new Dictionary<string, string> { ["/config/paths/dir[2]"] = "{SD}/a&b", ["/config/paths/dir/@path"] = "{SD}/\"q\"" });
        Assert.Equal(Xml.Replace("/storage/x &lt;y&gt;", "{SD}/a&amp;b").Replace("/storage/emulated/0/ROMs?a=1&amp;b=2", "{SD}/&quot;q&quot;"), r);
    }

    private const string Json =
        "{\r\n  \"a\": \"/storage/emulated/0/x\",\r\n  \"n\": 5,\r\n  \"o\": { \"p/q\": \"u\\u00e9\\/z\", \"t\": true },\r\n  \"l\": [\"one\", {\"k\": \"two\"}, null]\r\n}\r\n";

    [Fact]
    public void Json_pointer_keys()
    {
        var fmt = new JsonFormat();
        var v = fmt.Read(Json).ToDictionary(x => x.Key, x => x.Value);
        Assert.Equal(new[] { "/a", "/o/p~1q", "/l/0", "/l/1/k" }.OrderBy(x => x), v.Keys.OrderBy(x => x));
        Assert.Equal("ué/z", v["/o/p~1q"]);
        var r = fmt.Replace(Json, new Dictionary<string, string> { ["/a"] = "{INTERNAL}/x\"", ["/l/1/k"] = "2" });
        Assert.Equal(Json.Replace("/storage/emulated/0/x", "{INTERNAL}/x\\\"").Replace("\"two\"", "\"2\""), r);
    }

    [Theory]
    [InlineData("malformed", "{ \"a\": ")]
    public void Malformed_json_reads_nothing(string _, string text)
    {
        Assert.Empty(new JsonFormat().Read(text));
    }

    [Fact]
    public void Malformed_xml_reads_nothing()
    {
        Assert.Empty(new XmlFormat().Read("<a b=\"1"));
    }

    public static IEnumerable<object[]> RoundtripCases()
    {
        foreach (var (name, fmt) in new (string, IConfigFormat)[]
        {
            ("eden-thor.ini", new IniFormat()),
            ("eden-odin.ini", new IniFormat()),
            ("azahar-thor.ini", new IniFormat()),
            ("dolphin-odin.ini", new IniFormat()),
            ("retroarch-odin.cfg", new RetroArchCfgFormat()),
            ("vita3k-config.yml", new YamlLineFormat()),
        })
            foreach (var crlf in new[] { false, true })
                yield return new object[] { name, fmt, crlf };
    }

    private static void AssertRoundtrip(IConfigFormat fmt, string text)
    {
        var values = fmt.Read(text);
        Assert.NotEmpty(values);
        // Only keys whose occurrences all agree; Replace is keyed, not positional.
        var same = values.GroupBy(v => v.Key).Where(g => g.Select(v => v.Value).Distinct().Count() == 1)
            .ToDictionary(g => g.Key, g => g.First().Value);
        Assert.Equal(text, fmt.Replace(text, same));
        Assert.Equal(text, fmt.Replace(text, new Dictionary<string, string>()));
    }

    [Theory]
    [MemberData(nameof(RoundtripCases))]
    public void Roundtrip_without_changes_is_byte_identical(string fixture, IConfigFormat fmt, bool crlf)
    {
        var text = Fixture(fixture);
        if (crlf) text = text.Replace("\n", "\r\n");
        AssertRoundtrip(fmt, text);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void Roundtrip_xml_and_json_inline(bool crlf)
    {
        string N(string s) => crlf ? s.Replace("\r\n", "\n").Replace("\n", "\r\n") : s.Replace("\r\n", "\n");
        AssertRoundtrip(new XmlFormat(), N(Xml));
        AssertRoundtrip(new JsonFormat(), N(Json));
    }

    [Fact]
    public void Span_lengths_cover_the_exact_value()
    {
        foreach (var (name, fmt) in new (string, IConfigFormat)[] { ("eden-odin.ini", new IniFormat()), ("retroarch-odin.cfg", new RetroArchCfgFormat()) })
        {
            var text = Fixture(name).Replace("\n", "\r\n");
            foreach (var v in fmt.Read(text))
            {
                Assert.Equal(v.Value, text.Substring(v.Start, v.Length));
                Assert.DoesNotContain('\r', v.Value);
            }
        }
    }

    [Theory]
    [InlineData("eden", "config/config.ini", typeof(IniFormat))]
    [InlineData("retroarch", "retroarch.cfg", typeof(RetroArchCfgFormat))]
    [InlineData("retroarch", "config/Core/Core.opt", typeof(RetroArchCfgFormat))]
    [InlineData("retroarch", "config/remaps/Core/Game.rmp", typeof(RetroArchCfgFormat))]
    [InlineData("vita3k", "config.YML", typeof(YamlLineFormat))]
    [InlineData("vita3k", "a/b.yaml", typeof(YamlLineFormat))]
    [InlineData("x", "a.xml", typeof(XmlFormat))]
    [InlineData("x", "a.json", typeof(JsonFormat))]
    public void ForPath_dispatches_on_extension(string emu, string path, Type expected)
    {
        Assert.IsType(expected, ConfigFormats.ForPath(emu, path));
    }

    [Theory]
    [InlineData("a.bin")]
    [InlineData("noext")]
    [InlineData("shaders/x.glsl")]
    public void ForPath_unknown_is_opaque(string path)
    {
        Assert.Null(ConfigFormats.ForPath("x", path));
    }
}

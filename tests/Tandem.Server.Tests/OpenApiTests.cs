using System.Text.Json;
using Tandem.Server.Tests.Infrastructure;

namespace Tandem.Server.Tests;

public class OpenApiTests
{
    [Fact]
    public async Task OpenApi_document_lists_saves_upload()
    {
        using var f = new ServerFactory();
        var response = await f.Client().GetAsync("/v1/openapi.json");
        response.EnsureSuccessStatusCode();
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        var paths = doc.RootElement.GetProperty("paths");
        Assert.True(paths.TryGetProperty("/v1/saves/upload", out _));
        Assert.True(paths.TryGetProperty("/v1/admin/backup", out _));
    }
}

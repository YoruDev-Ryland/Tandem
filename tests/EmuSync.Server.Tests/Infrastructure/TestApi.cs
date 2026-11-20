using System.Net.Http.Json;

namespace EmuSync.Server.Tests.Infrastructure;

/// <summary>Helpers over ServerFactory; later tasks add account/device/sync helpers here.</summary>
public static class TestApi
{
    public static async Task<T> ReadAsync<T>(this HttpResponseMessage response)
    {
        var value = await response.Content.ReadFromJsonAsync<T>();
        return value ?? throw new InvalidOperationException("Response body was empty.");
    }
}

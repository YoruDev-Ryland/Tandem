using System.Net.Http.Headers;
using System.Net.Http.Json;
using EmuSync.Protocol;

namespace EmuSync.Server.Tests.Infrastructure;

/// <summary>Helpers over ServerFactory; later tasks add device/sync helpers here.</summary>
public static class TestApi
{
    public const string Password = "correct horse battery";

    public static async Task<T> ReadAsync<T>(this HttpResponseMessage response)
    {
        var value = await response.Content.ReadFromJsonAsync<T>();
        return value ?? throw new InvalidOperationException("Response body was empty.");
    }

    /// <summary>Registers an account and returns a client authenticated with the new device key.</summary>
    public static async Task<(HttpClient Device, DeviceKeyResponse Key)> Register(
        this ServerFactory factory, string email, string password = Password, string deviceName = "Test device")
    {
        var response = await factory.Client().PostAsJsonAsync("/v1/auth/register",
            new RegisterRequest(email, password, deviceName, "android"));
        response.EnsureSuccessStatusCode();
        var key = await response.ReadAsync<DeviceKeyResponse>();
        var client = factory.Client();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", key.DeviceKey);
        return (client, key);
    }

    /// <summary>Logs in with email and password; the client keeps the session cookie.</summary>
    public static async Task<HttpClient> WebLogin(this ServerFactory factory, string email, string password = Password)
    {
        var client = factory.CreateClient(new() { HandleCookies = true });
        client.DefaultRequestHeaders.Add(Protocol.Protocol.Header, Protocol.Protocol.Version.ToString());
        var response = await client.PostAsJsonAsync("/v1/web/session", new WebSessionRequest(email, password, null));
        response.EnsureSuccessStatusCode();
        return client;
    }
}

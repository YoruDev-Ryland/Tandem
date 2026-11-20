using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;
using EmuSync.Protocol;

namespace EmuSync.Server.Tests.Infrastructure;

/// <summary>Helpers over ServerFactory; later tasks add device/sync helpers here.</summary>
public static class TestApi
{
    public const string Password = "correct horse battery";

    /// <summary>The server's wire format: web defaults with enums as their declared names.</summary>
    public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        Converters = { new JsonStringEnumConverter() },
    };

    public static async Task<T> ReadAsync<T>(this HttpResponseMessage response)
    {
        var value = await response.Content.ReadFromJsonAsync<T>(Json);
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

    /// <summary>Pairs a second device onto the same user via a pairing code created by <paramref name="device"/>.</summary>
    public static async Task<(HttpClient Device, DeviceKeyResponse Key)> PairSecondDevice(
        this ServerFactory factory, HttpClient device, string name = "Second device")
    {
        var codeResponse = await device.PostAsync("/v1/pairing/codes", null);
        codeResponse.EnsureSuccessStatusCode();
        var code = await codeResponse.ReadAsync<PairingCodeResponse>();
        var redeem = await factory.Client().PostAsJsonAsync("/v1/pairing/redeem",
            new RedeemPairingRequest(code.Code, name, "android"));
        redeem.EnsureSuccessStatusCode();
        var key = await redeem.ReadAsync<DeviceKeyResponse>();
        var client = factory.Client();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", key.DeviceKey);
        return (client, key);
    }

    public static Task<HttpResponseMessage> PatchAsJsonAsync<T>(this HttpClient client, string url, T body) =>
        client.PatchAsync(url, JsonContent.Create(body, options: Json));

    /// <summary>POST with the server's wire format (enums as strings).</summary>
    public static Task<HttpResponseMessage> PostJsonAsync<T>(this HttpClient client, string url, T body) =>
        client.PostAsync(url, JsonContent.Create(body, options: Json));
}

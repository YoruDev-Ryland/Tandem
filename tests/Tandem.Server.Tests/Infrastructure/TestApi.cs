using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;
using Tandem.Protocol;

namespace Tandem.Server.Tests.Infrastructure;

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

    // ---- sync helpers ----

    /// <summary>Uploads <paramref name="content"/> as a one-file save (data.dat) and returns the response.</summary>
    public static async Task<SaveUploadResponse> UploadSave(this HttpClient device, string gameKey, string content, Guid? baseVersionId,
        string emulatorId = "eden")
    {
        var manifest = await Fixtures.Manifest(device, ("data.dat", System.Text.Encoding.UTF8.GetBytes(content)));
        var response = await device.PostJsonAsync("/v1/saves/upload",
            new SaveUploadRequest(emulatorId, ItemKind.Save, gameKey, "", "", baseVersionId, manifest, null));
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException($"{response.StatusCode}: {await response.Content.ReadAsStringAsync()}");
        return await response.ReadAsync<SaveUploadResponse>();
    }

    public static async Task Install(this HttpClient device, string gameKey, string emulatorId = "eden") =>
        (await device.PutAsJsonAsync($"/v1/devices/me/installs/{emulatorId}",
            new InstallsRequest([new InstalledGame(gameKey, null, null)]))).EnsureSuccessStatusCode();

    public static Task<HttpResponseMessage> ReportStatus(this HttpClient device, string[]? runningEmulators) =>
        device.PutAsJsonAsync("/v1/devices/me/status", new DeviceStatusRequest("android", "1.0", true, [],
            new Dictionary<string, string>(), runningEmulators));

    public static async Task<IReadOnlyList<DeliveryDto>> PendingDeliveries(this HttpClient device)
    {
        var response = await device.GetAsync("/v1/deliveries");
        response.EnsureSuccessStatusCode();
        return (await response.ReadAsync<DeliveryList>()).Items;
    }

    public static async Task AckApplied(this HttpClient device, Guid deliveryId) =>
        (await device.PostJsonAsync($"/v1/deliveries/{deliveryId}/ack", new AckRequest(DeliveryResult.Applied, null)))
            .EnsureSuccessStatusCode();

    public static async Task<T> GetOk<T>(this HttpClient client, string url)
    {
        var response = await client.GetAsync(url);
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException($"{response.StatusCode}: {await response.Content.ReadAsStringAsync()}");
        return await response.ReadAsync<T>();
    }
}

using System.Text.Json;
using System.Text.Json.Serialization;
using EmuSync.Protocol;
using EmuSync.Server.Auth;
using EmuSync.Server.Data;
using EmuSync.Server.Http;
using EmuSync.Server.Saves;
using Microsoft.EntityFrameworkCore;

namespace EmuSync.Server.Notify;

/// <summary>
/// Per-user notification settings, stored as one JSON <see cref="UserSetting"/> row. Tokens are kept as given (sending
/// needs them) but are write-only on the API: GET returns <see cref="NotifyTarget.TokenSet"/> instead.
/// </summary>
public static class NotifySettings
{
    public const string Key = "notify.settings";
    public const int MaxTargets = 10;

    public static readonly NotifyEvents DefaultEvents = new();
    public static readonly NotifySettingsDto Defaults = new([], DefaultEvents);

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { Converters = { new JsonStringEnumConverter() } };

    /// <summary>The stored settings, tokens included. For sending only; never return this from the API.</summary>
    public static async Task<NotifySettingsDto> LoadAsync(EmuSyncDbContext db, Guid userId, CancellationToken ct)
    {
        var row = await db.UserSettings.AsNoTracking().FirstOrDefaultAsync(s => s.UserId == userId && s.Key == Key, ct);
        return row is null ? Defaults : Parse(row.Value);
    }

    private static NotifySettingsDto Parse(string json)
    {
        try
        {
            var value = JsonSerializer.Deserialize<NotifySettingsDto>(json, Json);
            return value is null ? Defaults : value with { Targets = value.Targets ?? [], Events = value.Events ?? DefaultEvents };
        }
        catch (JsonException) { return Defaults; }
    }

    /// <summary>The API view: tokens removed, <see cref="NotifyTarget.TokenSet"/> filled.</summary>
    public static NotifySettingsDto ToResponse(NotifySettingsDto s) =>
        s with { Targets = s.Targets.Select(t => t with { Token = null, TokenSet = !string.IsNullOrEmpty(t.Token) }).ToList() };

    /// <summary>Validates (400 bad_value), keeps the stored token of a target whose token was omitted and whose Url is
    /// unchanged, and stores.</summary>
    public static async Task SaveAsync(EmuSyncDbContext db, Guid userId, NotifySettingsDto? value, CancellationToken ct)
    {
        if (value is null) throw new ApiException(400, "bad_request", "A request body is required.");
        var targets = value.Targets ?? [];
        if (targets.Count > MaxTargets) throw Bad($"At most {MaxTargets} targets are allowed.");

        var current = await LoadAsync(db, userId, ct);
        var stored = new List<NotifyTarget>();
        foreach (var t in targets)
        {
            if (t is null) throw Bad("A target is required.");
            if (!Enum.IsDefined(t.Kind)) throw Bad("kind must be Ntfy, Discord or Webhook.");
            var url = t.Url?.Trim() ?? "";
            if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https"))
                throw Bad("Each target url must be an http(s) URL.");
            var token = t.Token is null
                ? current.Targets.FirstOrDefault(c => c.Url == url)?.Token
                : t.Token.Length == 0 ? null : t.Token;
            stored.Add(new NotifyTarget(t.Kind, url, token));
        }
        var events = value.Events ?? DefaultEvents;

        var text = JsonSerializer.Serialize(new NotifySettingsDto(stored, events), Json);
        var row = await db.UserSettings.FirstOrDefaultAsync(s => s.UserId == userId && s.Key == Key, ct);
        if (row is null) db.UserSettings.Add(new UserSetting { UserId = userId, Key = Key, Value = text });
        else row.Value = text;
        await db.SaveChangesAsync(ct);
    }

    private static ApiException Bad(string message) => new(400, "bad_value", message);
}

public static class NotifyEndpoints
{
    public static IEndpointRouteBuilder MapNotifyEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/v1/settings/notifications").RequireAuthorization();

        group.MapGet("", async (HttpContext http, EmuSyncDbContext db, CancellationToken ct) =>
            NotifySettings.ToResponse(await NotifySettings.LoadAsync(db, http.User.UserId(), ct)));

        group.MapPut("", async (HttpContext http, EmuSyncDbContext db, NotifySettingsDto? body, CancellationToken ct) =>
        {
            var userId = http.User.UserId();
            await NotifySettings.SaveAsync(db, userId, body, ct);
            return NotifySettings.ToResponse(await NotifySettings.LoadAsync(db, userId, ct));
        });

        // Sends to every target now, whatever the event toggles say, and reports each outcome.
        group.MapPost("/test", async (HttpContext http, INotifier notifier, CancellationToken ct) =>
            await notifier.TestAsync(http.User.UserId(), ct));
        return app;
    }
}

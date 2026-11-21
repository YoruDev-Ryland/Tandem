using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Tandem.Protocol;
using Tandem.Server.Data;

namespace Tandem.Server.Notify;

/// <summary>A plain-text notification. <paramref name="Event"/> is one of <see cref="NotifyEventNames"/>.</summary>
public sealed record NotifyMessage(string Title, string Body, string Event);

public static class NotifyEventNames
{
    public const string Conflict = "conflict";
    public const string FailedSync = "failed_sync";
    public const string ShizukuOff = "shizuku_off";
    public const string BackupFailed = "backup_failed";
    public const string Test = "test";

    public static bool Enabled(NotifyEvents e, string name) => name switch
    {
        Conflict => e.Conflicts,
        FailedSync => e.FailedSyncs,
        ShizukuOff => e.ShizukuOff,
        BackupFailed => e.BackupFailed,
        _ => true,
    };
}

public interface INotifier
{
    /// <summary>Sends to each of the user's targets (nothing when the message's event is switched off). Failures are
    /// logged, never thrown (only cancellation propagates).</summary>
    Task SendAsync(Guid userId, NotifyMessage message, CancellationToken ct);

    /// <summary>Sends a test message to every target, ignoring the event toggles, and returns each outcome.</summary>
    Task<IReadOnlyList<NotifyTestResult>> TestAsync(Guid userId, CancellationToken ct);
}

/// <summary>
/// Posts to ntfy, Discord and generic webhook targets through the named "notify" HttpClient (10 s timeout). Target URLs
/// and tokens are never logged (a Discord webhook URL is itself a secret): this class logs none, and the client is
/// registered with RemoveAllLoggers so IHttpClientFactory's request logging (which prints the URI) is off.
/// </summary>
public sealed class Notifier(IServiceScopeFactory scopes, IHttpClientFactory clients, TimeProvider time, ILogger<Notifier> logger) : INotifier
{
    public const string ClientName = "notify";
    public static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(10);

    public async Task SendAsync(Guid userId, NotifyMessage message, CancellationToken ct)
    {
        NotifySettingsDto settings;
        try
        {
            using var scope = scopes.CreateScope();
            settings = await NotifySettings.LoadAsync(scope.ServiceProvider.GetRequiredService<TandemDbContext>(), userId, ct);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            logger.LogError(ex, "Could not load notification settings");
            return;
        }
        if (settings.Targets.Count == 0 || !NotifyEventNames.Enabled(settings.Events, message.Event)) return;

        foreach (var target in settings.Targets)
        {
            var result = await SendOneAsync(target, message, ct);
            if (!result.Ok)
                logger.LogWarning("Notification ({Kind}, {Event}) failed: {Error}", target.Kind, message.Event, result.Error ?? $"HTTP {result.Status}");
        }
    }

    public async Task<IReadOnlyList<NotifyTestResult>> TestAsync(Guid userId, CancellationToken ct)
    {
        NotifySettingsDto settings;
        using (var scope = scopes.CreateScope())
            settings = await NotifySettings.LoadAsync(scope.ServiceProvider.GetRequiredService<TandemDbContext>(), userId, ct);
        var message = new NotifyMessage("Tandem test", "This is a test notification from your Tandem server.", NotifyEventNames.Test);
        var results = new List<NotifyTestResult>();
        foreach (var target in settings.Targets) results.Add(await SendOneAsync(target, message, ct));
        return results;
    }

    private async Task<NotifyTestResult> SendOneAsync(NotifyTarget target, NotifyMessage message, CancellationToken ct)
    {
        try
        {
            using var request = Build(target, message);
            using var response = await clients.CreateClient(ClientName).SendAsync(request, ct);
            var status = (int)response.StatusCode;
            return response.IsSuccessStatusCode ? new(target.Kind, true, status) : new(target.Kind, false, status, $"The target answered HTTP {status}.");
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (TaskCanceledException) { return new(target.Kind, false, null, "The target did not answer within 10 seconds."); }
        catch (Exception ex) { return new(target.Kind, false, null, $"Could not reach the target: {ex.GetType().Name}"); }
    }

    private HttpRequestMessage Build(NotifyTarget target, NotifyMessage message)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, target.Url);
        switch (target.Kind)
        {
            case NotifyKind.Ntfy:
                request.Content = new StringContent(message.Body, Encoding.UTF8, "text/plain");
                request.Headers.TryAddWithoutValidation("Title", AsciiHeader(message.Title));
                if (!string.IsNullOrEmpty(target.Token))
                    request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", target.Token);
                break;
            case NotifyKind.Discord:
                request.Content = JsonBody(new { content = $"**{message.Title}**\n{message.Body}" });
                break;
            default:
                request.Content = JsonBody(new { @event = message.Event, title = message.Title, body = message.Body, utc = time.GetUtcNow() });
                break;
        }
        return request;
    }

    private static StringContent JsonBody(object value) =>
        new(JsonSerializer.Serialize(value), Encoding.UTF8, "application/json");

    /// <summary>HTTP header values must be ASCII here; the full text is in the body.</summary>
    private static string AsciiHeader(string s) =>
        new(s.Select(c => c is >= ' ' and <= '~' ? c : '?').ToArray());
}

using Tandem.Server.Http;

namespace Tandem.Server.Auth;

/// <summary>
/// Per-account password guessing limit (F-08), on top of the per-IP rate limit: after <see cref="FreeFailures"/>
/// failed sign-ins in a row for one email, each further attempt must wait, doubling from <see cref="FirstDelay"/> up to
/// <see cref="MaxDelay"/>; a success clears it. While it waits, attempts are refused with 429 too_many_attempts without
/// checking the password. Unknown emails are counted the same way, so the answer never tells whether an account exists.
/// In memory: a restart forgets it, which only shortens a wait.
/// </summary>
public sealed class LoginThrottle(TimeProvider time)
{
    public const int FreeFailures = 5;
    public static readonly TimeSpan FirstDelay = TimeSpan.FromSeconds(30);
    public static readonly TimeSpan MaxDelay = TimeSpan.FromMinutes(15);
    private static readonly TimeSpan Forget = TimeSpan.FromDays(1);
    private const int MaxEntries = 10_000;

    private sealed record Entry(int Failures, DateTimeOffset LastFailure, DateTimeOffset BlockedUntil);

    private readonly object _gate = new();
    private readonly Dictionary<string, Entry> _entries = new(StringComparer.Ordinal);

    /// <summary>429 too_many_attempts while the account must wait.</summary>
    public void Check(string email)
    {
        var now = time.GetUtcNow();
        lock (_gate)
        {
            if (_entries.TryGetValue(email, out var e) && e.BlockedUntil > now)
                throw new ApiException(429, "too_many_attempts",
                    $"Too many failed sign-ins for this account. Try again in {Math.Max(1, (int)Math.Ceiling((e.BlockedUntil - now).TotalSeconds))} seconds.");
        }
    }

    public void Failed(string email)
    {
        var now = time.GetUtcNow();
        lock (_gate)
        {
            if (_entries.Count >= MaxEntries)
                foreach (var key in _entries.Where(p => now - p.Value.LastFailure > Forget).Select(p => p.Key).ToList()) _entries.Remove(key);
            var failures = _entries.TryGetValue(email, out var e) && now - e.LastFailure <= Forget ? e.Failures + 1 : 1;
            var blocked = failures < FreeFailures
                ? now
                : now + TimeSpan.FromTicks(Math.Min(MaxDelay.Ticks, FirstDelay.Ticks << Math.Min(20, failures - FreeFailures)));
            if (_entries.Count < MaxEntries || _entries.ContainsKey(email)) _entries[email] = new Entry(failures, now, blocked);
        }
    }

    public void Succeeded(string email)
    {
        lock (_gate) _entries.Remove(email);
    }
}

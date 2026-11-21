using Tandem.Server.Http;

namespace Tandem.Server.Auth;

/// <summary>
/// Per-account password guessing limit (F-08), on top of the per-IP rate limit: after <see cref="FreeFailures"/>
/// failed sign-ins in a row for one email from one client bucket (<see cref="AuthSetup.PartitionKey"/>: an IPv4
/// address, or an IPv6 /64), each further attempt from that bucket must wait, doubling from <see cref="FirstDelay"/>
/// up to <see cref="MaxDelay"/>; a success clears it. While it waits, attempts are refused with 429 too_many_attempts
/// without checking the password. Failures from elsewhere never block the owner's own address, and a wait never
/// exceeds five minutes, so nobody can keep an account locked out. A password reset (CLI or admin) clears every
/// bucket of the account. Unknown emails are counted the same way, so the answer never tells whether an account
/// exists. In memory: a restart forgets it, which only shortens a wait.
/// </summary>
public sealed class LoginThrottle(TimeProvider time)
{
    public const int FreeFailures = 5;
    public static readonly TimeSpan FirstDelay = TimeSpan.FromSeconds(30);
    public static readonly TimeSpan MaxDelay = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan Forget = TimeSpan.FromDays(1);
    private const int MaxEntries = 10_000;

    private sealed record Entry(int Failures, DateTimeOffset LastFailure, DateTimeOffset BlockedUntil);

    private readonly object _gate = new();
    private readonly Dictionary<string, Entry> _entries = new(StringComparer.Ordinal);

    /// <summary>429 too_many_attempts while the account must wait.</summary>
    private static string Key(string email, string client) => email + "\n" + client;

    public void Check(string email, string client)
    {
        var now = time.GetUtcNow();
        lock (_gate)
        {
            if (_entries.TryGetValue(Key(email, client), out var e) && e.BlockedUntil > now)
                throw new ApiException(429, "too_many_attempts",
                    $"Too many failed sign-ins for this account. Try again in {Math.Max(1, (int)Math.Ceiling((e.BlockedUntil - now).TotalSeconds))} seconds.");
        }
    }

    public void Failed(string email, string client)
    {
        var key = Key(email, client);
        var now = time.GetUtcNow();
        lock (_gate)
        {
            if (_entries.Count >= MaxEntries)
                foreach (var old in _entries.Where(p => now - p.Value.LastFailure > Forget).Select(p => p.Key).ToList()) _entries.Remove(old);
            var failures = _entries.TryGetValue(key, out var e) && now - e.LastFailure <= Forget ? e.Failures + 1 : 1;
            var blocked = failures < FreeFailures
                ? now
                : now + TimeSpan.FromTicks(Math.Min(MaxDelay.Ticks, FirstDelay.Ticks << Math.Min(20, failures - FreeFailures)));
            if (_entries.Count < MaxEntries || _entries.ContainsKey(key)) _entries[key] = new Entry(failures, now, blocked);
        }
    }

    public void Succeeded(string email, string client)
    {
        lock (_gate) _entries.Remove(Key(email, client));
    }

    /// <summary>Forgets every failure of the account, from any client (a password reset).</summary>
    public void Clear(string email)
    {
        var prefix = email + "\n";
        lock (_gate)
            foreach (var key in _entries.Keys.Where(k => k.StartsWith(prefix, StringComparison.Ordinal)).ToList()) _entries.Remove(key);
    }
}

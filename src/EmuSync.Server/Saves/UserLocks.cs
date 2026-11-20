using System.Collections.Concurrent;

namespace EmuSync.Server.Saves;

/// <summary>
/// One async mutex per user, held around every read-decide-write of that user's versioned items. Single-instance
/// only; on Postgres the services additionally lock the item row (SELECT ... FOR UPDATE).
/// </summary>
public sealed class UserLocks
{
    private readonly ConcurrentDictionary<Guid, SemaphoreSlim> _locks = new();

    public async Task<IDisposable> Acquire(Guid userId, CancellationToken ct = default)
    {
        var gate = _locks.GetOrAdd(userId, _ => new SemaphoreSlim(1, 1));
        await gate.WaitAsync(ct);
        return new Releaser(gate);
    }

    private sealed class Releaser(SemaphoreSlim gate) : IDisposable
    {
        private int _released;

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _released, 1) == 0) gate.Release();
        }
    }
}

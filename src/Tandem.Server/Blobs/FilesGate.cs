namespace Tandem.Server.Blobs;

/// <summary>
/// Lets the stored files be moved while the server runs. Blob writes take the shared side (<see cref="EnterWriteAsync"/>)
/// for the rename that puts a file in place; the move takes the exclusive side (<see cref="HoldAsync"/>) for its last
/// pass. While a hold is requested or taken, new writers wait and device uploads are answered 503 (<c>RequireNotHolding</c>).
/// </summary>
public sealed class FilesGate
{
    private readonly object _lock = new();
    private int _active;
    private volatile bool _holding;
    private TaskCompletionSource _opened = NewSignal();
    private TaskCompletionSource? _drained;

    /// <summary>A hold is requested or taken: file uploads are refused and writers wait.</summary>
    public bool Holding => _holding;

    /// <summary>A move is running (set by the move job; read by the pruner, backup and deletes).</summary>
    public bool Moving { get; set; }

    private static TaskCompletionSource NewSignal() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    /// <summary>The shared side: waits while a hold is requested or taken. Dispose to leave.</summary>
    public async Task<IDisposable> EnterWriteAsync(CancellationToken ct)
    {
        while (true)
        {
            Task wait;
            lock (_lock)
            {
                if (!_holding)
                {
                    _active++;
                    return new Releaser(this, writer: true);
                }
                wait = _opened.Task;
            }
            await wait.WaitAsync(ct);
        }
    }

    /// <summary>Sets <see cref="Holding"/>, waits for writes in flight to finish, and returns the exclusive releaser;
    /// null (and the hold is dropped) when they do not finish within <paramref name="timeout"/>.</summary>
    public async Task<IDisposable?> HoldAsync(TimeSpan timeout, CancellationToken ct)
    {
        Task drained;
        lock (_lock)
        {
            if (_holding) throw new InvalidOperationException("The files are already on hold.");
            _holding = true;
            if (_active == 0) return new Releaser(this, writer: false);
            _drained = NewSignal();
            drained = _drained.Task;
        }
        try
        {
            await drained.WaitAsync(timeout, ct);
        }
        catch (Exception ex) when (ex is TimeoutException or OperationCanceledException)
        {
            lock (_lock) { _drained = null; Open(); }
            if (ex is TimeoutException) return null;
            throw;
        }
        return new Releaser(this, writer: false);
    }

    private void Open()
    {
        _holding = false;
        var signal = _opened;
        _opened = NewSignal();
        signal.TrySetResult();
    }

    private void Release(bool writer)
    {
        lock (_lock)
        {
            if (writer)
            {
                if (--_active == 0 && _drained is { } d) { _drained = null; d.TrySetResult(); }
            }
            else Open();
        }
    }

    private sealed class Releaser(FilesGate gate, bool writer) : IDisposable
    {
        private int _done;
        public void Dispose()
        {
            if (Interlocked.Exchange(ref _done, 1) == 0) gate.Release(writer);
        }
    }
}

/// <summary>Called by <see cref="BlobStore"/> after a body is received and verified, before it is put in place.
/// A seam for tests; the server registers a no-op.</summary>
public interface IBlobPutHook
{
    Task BeforeCommitAsync(CancellationToken ct);
}

public sealed class NoBlobPutHook : IBlobPutHook
{
    public Task BeforeCommitAsync(CancellationToken ct) => Task.CompletedTask;
}

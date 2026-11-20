namespace EmuSync.Server.Events;

/// <summary>In-process synchronous domain event dispatcher.</summary>
public interface IDomainEvents
{
    /// <summary>Awaits every handler subscribed for the event's runtime type (or a base type/interface).</summary>
    Task Raise(object evt, CancellationToken ct);

    void Subscribe<T>(Func<T, CancellationToken, Task> handler);
}

public sealed class DomainEvents : IDomainEvents
{
    private readonly object _gate = new();
    private readonly List<(Type Type, Func<object, CancellationToken, Task> Handler)> _handlers = [];

    public void Subscribe<T>(Func<T, CancellationToken, Task> handler)
    {
        lock (_gate)
            _handlers.Add((typeof(T), (e, ct) => handler((T)e, ct)));
    }

    public async Task Raise(object evt, CancellationToken ct)
    {
        (Type Type, Func<object, CancellationToken, Task> Handler)[] snapshot;
        lock (_gate)
            snapshot = [.. _handlers];
        foreach (var (type, handler) in snapshot)
            if (type.IsInstanceOfType(evt))
                await handler(evt, ct);
    }
}

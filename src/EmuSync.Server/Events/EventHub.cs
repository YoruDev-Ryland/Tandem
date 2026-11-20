using System.Threading.Channels;

namespace EmuSync.Server.Events;

/// <summary>In-process synchronous domain event dispatcher.</summary>
public interface IDomainEvents
{
    /// <summary>Awaits every (isolated; failures are logged, handlers get CancellationToken.None) handler subscribed for the event's runtime type (or a base type/interface).</summary>
    Task Raise(object evt, CancellationToken ct);

    void Subscribe<T>(Func<T, CancellationToken, Task> handler);
}

public sealed class DomainEvents(ILogger<DomainEvents> logger) : IDomainEvents
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
            {
                try { await handler(evt, CancellationToken.None); }
                catch (Exception ex) { logger.LogError(ex, "Domain event handler for {Event} failed", evt.GetType().Name); }
            }
    }
}

/// <summary>A live-stream event. <see cref="Type"/> is <c>deliveries</c>, <c>changed</c> (with a topic) or <c>ping</c>.</summary>
public sealed record ServerEvent(string Type, string? Topic = null);

/// <summary>Pushes "something changed" nudges to connected clients.</summary>
public interface IEventHub
{
    /// <summary>The device has new deliveries.</summary>
    void NotifyDevice(Guid deviceId);

    /// <summary>Topics: saves, configs, devices, vault, activity, conflicts.</summary>
    void NotifyUser(Guid userId, string topic);

    /// <summary>
    /// Registers the subscriber immediately (events raised after this call returns are not missed) and returns its
    /// stream; enumerating it to completion or disposing the enumerator unsubscribes. A device subscriber gets a
    /// <c>deliveries</c> event first, so a reconnecting device re-polls.
    /// </summary>
    IAsyncEnumerable<ServerEvent> Subscribe(Guid userId, Guid? deviceId, CancellationToken ct);
}

public sealed class NoOpEventHub : IEventHub
{
    public void NotifyDevice(Guid deviceId) { }

    public void NotifyUser(Guid userId, string topic) { }

    public IAsyncEnumerable<ServerEvent> Subscribe(Guid userId, Guid? deviceId, CancellationToken ct) =>
        AsyncEnumerable.Empty<ServerEvent>();
}

public sealed class EventHub(TimeProvider time, Microsoft.Extensions.Options.IOptions<ServerOptions> options) : IEventHub
{
    private const int QueueSize = 64;

    private sealed class Subscriber
    {
        private readonly object _gate = new();
        private bool _deliveriesQueued;

        public required Guid UserId { get; init; }
        public Guid? DeviceId { get; init; }
        public Channel<ServerEvent> Channel { get; private set; } = null!;

        public static Subscriber Create(Guid userId, Guid? deviceId)
        {
            var s = new Subscriber { UserId = userId, DeviceId = deviceId };
            s.Channel = System.Threading.Channels.Channel.CreateBounded<ServerEvent>(
                new BoundedChannelOptions(QueueSize) { FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true },
                dropped => { if (dropped.Type == "deliveries") lock (s._gate) s._deliveriesQueued = false; });
            return s;
        }

        public void Post(ServerEvent evt)
        {
            lock (_gate)
            {
                if (evt.Type == "deliveries")
                {
                    if (_deliveriesQueued) return; // coalesce
                    _deliveriesQueued = true;
                }
                Channel.Writer.TryWrite(evt);
            }
        }

        public void Delivered(ServerEvent evt)
        {
            if (evt.Type != "deliveries") return;
            lock (_gate) _deliveriesQueued = false;
        }
    }

    private readonly object _gate = new();
    private readonly List<Subscriber> _subscribers = [];

    public void NotifyDevice(Guid deviceId) => Fanout(s => s.DeviceId == deviceId, new ServerEvent("deliveries"));

    public void NotifyUser(Guid userId, string topic) => Fanout(s => s.UserId == userId, new ServerEvent("changed", topic));

    private void Fanout(Func<Subscriber, bool> match, ServerEvent evt)
    {
        Subscriber[] snapshot;
        lock (_gate) snapshot = [.. _subscribers];
        foreach (var s in snapshot)
            if (match(s)) s.Post(evt);
    }

    public IAsyncEnumerable<ServerEvent> Subscribe(Guid userId, Guid? deviceId, CancellationToken ct)
    {
        var sub = Subscriber.Create(userId, deviceId);
        lock (_gate) _subscribers.Add(sub);
        var heartbeat = TimeSpan.FromSeconds(Math.Max(1, options.Value.EventHeartbeatSeconds));
        var timer = time.CreateTimer(_ => sub.Post(new ServerEvent("ping")), null, heartbeat, heartbeat);
        if (deviceId is not null) sub.Post(new ServerEvent("deliveries"));
        return Read(sub, timer, ct);
    }

    private async IAsyncEnumerable<ServerEvent> Read(Subscriber sub, ITimer timer,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken ct)
    {
        try
        {
            await foreach (var evt in sub.Channel.Reader.ReadAllAsync(ct))
            {
                sub.Delivered(evt);
                yield return evt;
            }
        }
        finally
        {
            await timer.DisposeAsync();
            lock (_gate) _subscribers.Remove(sub);
            sub.Channel.Writer.TryComplete();
        }
    }
}

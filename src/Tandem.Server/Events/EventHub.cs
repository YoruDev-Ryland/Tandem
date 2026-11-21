using System.Threading.Channels;

namespace Tandem.Server.Events;

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

/// <summary>A live subscription. Disposing it unsubscribes and stops its heartbeat; it is safe to dispose twice.</summary>
public interface IEventSubscription : IAsyncDisposable
{
    IAsyncEnumerable<ServerEvent> Events { get; }
}

/// <summary>Pushes "something changed" nudges to connected clients.</summary>
public interface IEventHub
{
    /// <summary>The device has new deliveries.</summary>
    void NotifyDevice(Guid deviceId);

    /// <summary>Topics: saves, configs, devices, vault, activity, conflicts.</summary>
    void NotifyUser(Guid userId, string topic);

    /// <summary>Ends every open stream of the device (it was revoked): the response completes and the client sees
    /// 401 device_revoked when it reconnects.</summary>
    void CloseDevice(Guid deviceId);

    /// <summary>Ends every open stream of the user (the account was deleted).</summary>
    void CloseUser(Guid userId);

    /// <summary>
    /// Registers the subscriber immediately (events raised after this call returns are not missed). The caller must
    /// dispose the result. A device subscriber gets a <c>deliveries</c> event first, so a reconnecting device re-polls.
    /// </summary>
    IEventSubscription Subscribe(Guid userId, Guid? deviceId, CancellationToken ct);
}

public sealed class NoOpEventHub : IEventHub
{
    private sealed class Empty : IEventSubscription
    {
        public IAsyncEnumerable<ServerEvent> Events => AsyncEnumerable.Empty<ServerEvent>();
        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }

    public void NotifyDevice(Guid deviceId) { }

    public void NotifyUser(Guid userId, string topic) { }

    public void CloseDevice(Guid deviceId) { }

    public void CloseUser(Guid userId) { }

    public IEventSubscription Subscribe(Guid userId, Guid? deviceId, CancellationToken ct) => new Empty();
}

public sealed class EventHub(TimeProvider time, Microsoft.Extensions.Options.IOptions<ServerOptions> options) : IEventHub
{
    private const int QueueSize = 64;

    /// <summary>
    /// Every event kind is queued at most once per subscriber until it is read (deliveries, ping, and each changed
    /// topic), so the queue stays tiny and the DropOldest backstop is never reached in practice.
    /// </summary>
    private sealed class Subscriber : IEventSubscription
    {
        private readonly object _gate = new();
        private readonly HashSet<ServerEvent> _queued = [];
        private readonly EventHub _hub;
        private readonly ITimer _timer;
        private int _disposed;

        public Guid UserId { get; }
        public Guid? DeviceId { get; }
        public Channel<ServerEvent> Channel { get; }

        public Subscriber(EventHub hub, Guid userId, Guid? deviceId, TimeSpan heartbeat)
        {
            _hub = hub;
            UserId = userId;
            DeviceId = deviceId;
            Channel = System.Threading.Channels.Channel.CreateBounded<ServerEvent>(
                new BoundedChannelOptions(QueueSize) { FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true },
                dropped => { lock (_gate) _queued.Remove(dropped); });
            _timer = hub.time.CreateTimer(_ => Post(new ServerEvent("ping")), null, heartbeat, heartbeat);
        }

        public IAsyncEnumerable<ServerEvent> Events => ReadAsync();

        private async IAsyncEnumerable<ServerEvent> ReadAsync(
            [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken ct = default)
        {
            await foreach (var evt in Channel.Reader.ReadAllAsync(ct))
            {
                lock (_gate) _queued.Remove(evt);
                yield return evt;
            }
        }

        public void Post(ServerEvent evt)
        {
            lock (_gate)
            {
                if (!_queued.Add(evt)) return; // coalesce
                Channel.Writer.TryWrite(evt);
            }
        }

        /// <summary>Ends the stream: the reader sees the end of its events.</summary>
        public void Close() => Channel.Writer.TryComplete();

        public async ValueTask DisposeAsync()
        {
            if (Interlocked.Exchange(ref _disposed, 1) == 1) return;
            await _timer.DisposeAsync();
            lock (_hub._gate) _hub._subscribers.Remove(this);
            Channel.Writer.TryComplete();
        }
    }

    private readonly object _gate = new();
    private readonly List<Subscriber> _subscribers = [];
    private readonly TimeProvider time = time;

    /// <summary>For tests: currently registered subscribers.</summary>
    internal int SubscriberCount { get { lock (_gate) return _subscribers.Count; } }

    public void NotifyDevice(Guid deviceId) => Fanout(s => s.DeviceId == deviceId, new ServerEvent("deliveries"));

    public void NotifyUser(Guid userId, string topic) => Fanout(s => s.UserId == userId, new ServerEvent("changed", topic));

    public void CloseDevice(Guid deviceId) => Close(s => s.DeviceId == deviceId);

    public void CloseUser(Guid userId) => Close(s => s.UserId == userId);

    private void Close(Func<Subscriber, bool> match)
    {
        Subscriber[] snapshot;
        lock (_gate) snapshot = [.. _subscribers];
        foreach (var s in snapshot)
            if (match(s)) s.Close();
    }

    private void Fanout(Func<Subscriber, bool> match, ServerEvent evt)
    {
        Subscriber[] snapshot;
        lock (_gate) snapshot = [.. _subscribers];
        foreach (var s in snapshot)
            if (match(s)) s.Post(evt);
    }

    public IEventSubscription Subscribe(Guid userId, Guid? deviceId, CancellationToken ct)
    {
        var heartbeat = TimeSpan.FromSeconds(Math.Max(1, options.Value.EventHeartbeatSeconds));
        var sub = new Subscriber(this, userId, deviceId, heartbeat);
        lock (_gate) _subscribers.Add(sub);
        if (deviceId is not null) sub.Post(new ServerEvent("deliveries"));
        return sub;
    }
}

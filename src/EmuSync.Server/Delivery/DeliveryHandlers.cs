using EmuSync.Server.Events;

namespace EmuSync.Server.Deliveries;

/// <summary>
/// Subscribes the delivery recompute to domain events at startup. Each handler reconciles from the database in its
/// own scope, so a repeated or late event changes nothing.
/// </summary>
public sealed class DeliveryHandlers(IDomainEvents events, IServiceScopeFactory scopes) : IHostedService
{
    public Task StartAsync(CancellationToken cancellationToken)
    {
        events.Subscribe<SaveHeadChanged>((e, ct) => Run(svc => svc.RecomputeForItemAsync(e.ItemId, ct)));
        // Recompute holds every un-acked delivery of an item with an open conflict, i.e. deletes them.
        events.Subscribe<ConflictOpened>((e, ct) => Run(svc => svc.RecomputeForItemAsync(e.ItemId, ct)));
        events.Subscribe<InstallsChanged>((e, ct) => Run(svc => svc.RecomputeForDeviceAsync(e.DeviceId, ct)));
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;

    private async Task Run(Func<DeliveryService, Task> action)
    {
        await using var scope = scopes.CreateAsyncScope();
        await action(scope.ServiceProvider.GetRequiredService<DeliveryService>());
    }
}

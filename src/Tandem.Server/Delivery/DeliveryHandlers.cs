using Tandem.Server.Events;

namespace Tandem.Server.Deliveries;

/// <summary>
/// Subscribes the delivery recompute to domain events at startup. Each handler reconciles from the database in its
/// own scope, so a repeated or late event changes nothing.
/// </summary>
public sealed class DeliveryHandlers(IDomainEvents events, IServiceScopeFactory scopes) : IHostedService
{
    public Task StartAsync(CancellationToken cancellationToken)
    {
        events.Subscribe<SaveHeadChanged>((e, ct) => Run(svc => svc.RecomputeForItemAsync(e.ItemId, ct)));
        // With an open conflict the recompute soft-closes (supersedes) every un-acked delivery of the item.
        events.Subscribe<ConflictOpened>((e, ct) => Run(svc => svc.RecomputeForItemAsync(e.ItemId, ct)));
        events.Subscribe<ConfigsChanged>((e, ct) => Run(svc => svc.RecomputeConfigsAsync(e.UserId, ct)));
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

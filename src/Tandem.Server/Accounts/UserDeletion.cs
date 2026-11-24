using Tandem.Server.Blobs;
using Tandem.Server.Data;
using Tandem.Server.Events;
using Tandem.Server.Http;
using Tandem.Server.Saves;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace Tandem.Server.Accounts;

/// <summary>Deletes a user and everything they own (admin delete and self-delete share it).</summary>
public sealed class UserDeletion(TandemDbContext db, MaintenanceLock maintenance, UserLocks locks, FilesLocation files,
    IEventHub hub, ILogger<UserDeletion> logger)
{
    /// <summary>
    /// The usual lock order: maintenance (no backup is reading the files, no prune runs) and then the user's lock (no
    /// upload or other write of this user is in progress). Rows go first (every table cascades from Users), then, best
    /// effort, the blob directory: a leftover directory holds only unreachable files. 404 not_found when there is no
    /// such user. <paramref name="check"/> runs under the locks with the tracked user, before anything is deleted.
    /// </summary>
    public async Task DeleteAsync(Guid id, CancellationToken ct, Func<User, Task>? check = null)
    {
        using (await maintenance.AcquireAsync(ct))
        using (await locks.Acquire(id, ct))
        {
            var user = await db.Users.FirstOrDefaultAsync(u => u.Id == id, ct)
                ?? throw new ApiException(404, "not_found", "No such user.");
            if (check is not null) await check(user);
            db.Users.Remove(user);
            await db.SaveChangesAsync(ct);

            var dir = files.UserDir(id);
            try
            {
                if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                logger.LogWarning(ex, "Could not delete the blob directory of deleted user {UserId}", id);
            }
        }
        hub.CloseUser(id);
    }
}

using System.Text;
using Tandem.Protocol;
using Tandem.Server.Blobs;
using Tandem.Server.Data;
using Tandem.Server.Events;
using Tandem.Server.Http;
using Tandem.Server.Saves;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage;

namespace Tandem.Server.Configs;

/// <summary>
/// Config bundles (one per solo device or sync group, per emulator), their versions, groups and apply. Versions hold
/// the canonical manifest (see <see cref="ConfigTransform"/>); head/conflict decisions compare canonical hashes and
/// follow the save rules: ordering never uses device clocks and a conflict blocks the bundle's deliveries until it
/// is resolved. Every read-decide-write runs under the user's lock (plus the bundle row lock on Postgres) and
/// raises <see cref="ConfigsChanged"/> after commit, which reconciles config deliveries.
/// </summary>
public sealed class ConfigService(TandemDbContext db, BlobService blobs, ConfigTransform transform, UserLocks locks,
    IDomainEvents events, TimeProvider time, IActivityLog activity)
{
    public const int MaxGroupName = 60;

    public async Task<ConfigUploadResponse> UploadAsync(Guid userId, Guid deviceId, ConfigUploadRequest request, CancellationToken ct)
    {
        RequireEmulator(request?.EmulatorId);
        var emulatorId = request!.EmulatorId;
        var raw = Manifests.Normalize(request.Files);
        await blobs.RequireAllAsync(userId, raw.Select(e => e.Hash), ct);
        await blobs.RequireSizesAsync(userId, raw, ct);
        var roots = await transform.RootsAsync(deviceId, ct);

        ConfigUploadResponse response;
        ConfigConflictOpened? opened = null;
        using (await locks.Acquire(userId, ct))
        {
            // Again under the lock: the pruner (which holds it) may have removed an old unreferenced blob since.
            await blobs.RequireSizesAsync(userId, raw, ct);
            await using var tx = await BeginAsync(ct);
            var config = await db.DeviceConfigs.FirstOrDefaultAsync(c => c.DeviceId == deviceId && c.EmulatorId == emulatorId, ct);
            var bundle = config is null ? null
                : await LoadBundleForUpdateAsync(userId, config.BundleId, ct)
                  ?? throw new InvalidOperationException("Config bundle vanished while locked.");
            var head = bundle?.HeadVersionId is Guid headId ? await db.ConfigVersions.SingleAsync(v => v.Id == headId, ct) : null;

            // Before any tracked change: canonicalize may store blobs (SaveChanges).
            var canonical = await transform.CanonicalizeAsync(userId, emulatorId, raw, roots,
                head is null ? null : Manifests.FromJson(head.ManifestJson), ct);
            var hash = Manifests.Hash(canonical);

            if (bundle is null)
            {
                bundle = NewSoloBundle(userId, emulatorId, deviceId);
                config = new DeviceConfig { DeviceId = deviceId, EmulatorId = emulatorId, BundleId = bundle.Id };
                db.DeviceConfigs.Add(config);
            }
            config!.LastRawVersionId = await RecordRawAsync(deviceId, emulatorId, raw, ct);

            if (head is not null && head.ManifestHash == hash)
            {
                config.AppliedVersionId = head.Id;
                response = new ConfigUploadResponse(SaveUploadStatus.Unchanged, bundle.Id, head.Id);
            }
            else if (head is not null && request.BaseVersionId != head.Id && await db.ConfigVersions
                         .Where(v => v.BundleId == bundle.Id && v.Id != head.Id && v.ManifestHash == hash
                                     && (v.ConflictReason == null || v.Resolved) && (v.Resolved || v.Pinned))
                         .OrderByDescending(v => v.Seq).Select(v => (Guid?)v.Id).FirstOrDefaultAsync(ct) is Guid settled)
            {
                // A settled copy (a resolved conflict or a pinned version, never an open conflict) coming back on an old base: no new version
                // and no conflict. The device is recorded as holding that copy and is sent the head.
                config.AppliedVersionId = settled;
                response = new ConfigUploadResponse(SaveUploadStatus.Superseded, bundle.Id, settled);
            }
            else if (head is not null && request.BaseVersionId != head.Id && await db.ConfigVersions
                         .Where(v => v.BundleId == bundle.Id && v.DeviceId == deviceId && v.ConflictReason != null && !v.Resolved
                                     && v.ManifestHash == hash)
                         .OrderBy(v => v.Seq).Select(v => (Guid?)v.Id).FirstOrDefaultAsync(ct) is Guid existingConflict)
            {
                response = new ConfigUploadResponse(SaveUploadStatus.Conflict, bundle.Id, existingConflict); // a retry
            }
            else
            {
                // The server may have recorded this device as holding the head without telling it (group creation,
                // leaving a group, or a resolve made its files the head), so the device still names its older base.
                // The server's own record, never the request, decides this: only then is the upload based on the head.
                // A Fresh upload comes from a device that forgot what it knew (a variant switch, a new sign-in): its
                // base is taken as sent.
                var baseId = request.BaseVersionId;
                if (head is not null && baseId != head.Id && request.Fresh != true && config.AppliedVersionId == head.Id) baseId = head.Id;

                var version = await NewVersionAsync(bundle.Id, baseId, deviceId, ConfigSource.Device,
                    Manifests.ToJson(canonical), hash, canonical.Sum(e => e.Size), ct);
                if (head is null || baseId == head.Id)
                {
                    bundle.HeadVersionId = version.Id;
                    config.AppliedVersionId = version.Id;
                    response = new ConfigUploadResponse(SaveUploadStatus.Accepted, bundle.Id, version.Id);
                }
                else
                {
                    version.ConflictReason = baseId is null ? ConflictReason.FirstSync : ConflictReason.Concurrent;
                    bundle.OpenConflict = true;
                    response = new ConfigUploadResponse(SaveUploadStatus.Conflict, bundle.Id, version.Id);
                    opened = new ConfigConflictOpened(userId, bundle.Id, version.Id, emulatorId, deviceId);
                }
            }

            await db.SaveChangesAsync(ct);
            if (tx is not null) await tx.CommitAsync(ct);
        }

        await events.Raise(new ConfigsChanged(userId), ct);
        if (opened is not null) await events.Raise(opened, ct);
        // A retry of an upload that already opened a conflict creates nothing: only a new conflict version is logged.
        if (response.Status == SaveUploadStatus.Accepted || opened is not null)
        {
            var device = await DeviceNameAsync(deviceId, ct);
            if (response.Status == SaveUploadStatus.Accepted)
                await activity.WriteAsync(userId, "config_uploaded", $"{device} uploaded the {Games.EmulatorNames.Display(emulatorId)} config", deviceId, emulatorId,
                    data: new { bundleId = response.BundleId, versionId = response.VersionId });
            else
                await activity.WriteAsync(userId, "conflict_opened", $"{device} uploaded the {Games.EmulatorNames.Display(emulatorId)} config, which conflicts",
                    deviceId, emulatorId, data: new { bundleId = response.BundleId, versionId = response.VersionId });
        }
        return response;
    }

    public async Task<List<ConfigVersionSummary>> ListVersionsAsync(Guid userId, Guid bundleId, CancellationToken ct)
    {
        if (!await db.ConfigBundles.AnyAsync(b => b.Id == bundleId && b.UserId == userId, ct))
            throw new ApiException(404, "bundle_not_found", "Config bundle not found.");
        return await db.ConfigVersions.AsNoTracking().Where(v => v.BundleId == bundleId)
            .OrderByDescending(v => v.Seq)
            .Select(v => new ConfigVersionSummary(v.Id, v.Seq, v.Source,
                db.Devices.Where(d => d.Id == v.DeviceId).Select(d => d.Name).FirstOrDefault(),
                v.ReceivedUtc, v.TotalSize, v.Pinned, v.ConflictReason, v.Resolved))
            .ToListAsync(ct);
    }

    /// <summary>
    /// Edits one file of the bundle head in the browser. <paramref name="request"/>.Text is the file as the chosen
    /// device sees it (expanded): it is canonicalized with that device's roots and its raw values, spliced into the
    /// head's canonical manifest and decided like an upload (Unchanged when nothing differs). The new version is
    /// the head, so every member whose applied version differs is sent it, the chosen device included.
    /// </summary>
    public async Task<ConfigUploadResponse> EditFileAsync(Guid userId, Guid bundleId, string path, Guid? deviceId,
        EditConfigFileRequest request, CancellationToken ct)
    {
        if (request is null || request.Text is null) throw new ApiException(400, "bad_request", "Text is required.");
        if (Encoding.UTF8.GetByteCount(request.Text) > ConfigTransform.MaxTextBytes)
            throw new ApiException(400, "too_large", "Config files can be edited in the browser up to 4 MiB.");

        ConfigUploadResponse response;
        string emulatorId;
        using (await locks.Acquire(userId, ct))
        {
            await using var tx = await BeginAsync(ct);
            var bundle = await LoadBundleForUpdateAsync(userId, bundleId, ct)
                ?? throw new ApiException(404, "bundle_not_found", "Config bundle not found.");
            emulatorId = bundle.EmulatorId;
            if (bundle.GroupId is not null && deviceId is null)
                throw new ApiException(400, "device_required", "Say which device's view of the file this is (?device=).");
            var device = deviceId ?? bundle.OwnerDeviceId
                ?? throw new ApiException(400, "bad_request", "The bundle has no owner device; say which device (?device=).");
            var isMember = bundle.GroupId is null
                ? bundle.OwnerDeviceId == device
                : await db.DeviceConfigs.AnyAsync(c => c.BundleId == bundle.Id && c.DeviceId == device, ct);
            if (!isMember || !await db.Devices.AnyAsync(d => d.Id == device && d.UserId == userId && d.RevokedUtc == null, ct))
                throw new ApiException(400, "not_member", "That device does not use this config.");

            var head = bundle.HeadVersionId is Guid headId ? await db.ConfigVersions.SingleAsync(v => v.Id == headId, ct) : null;
            if (head is null || head.Id != request.BaseVersionId)
                throw new ApiException(409, "stale_base", "The config changed since you opened it; reload and edit again.");
            if (bundle.OpenConflict)
                throw new ApiException(409, "conflict_open", "Resolve the config conflict first.");
            var headManifest = Manifests.FromJson(head.ManifestJson);
            if (headManifest.All(e => e.Path != path))
                throw new ApiException(404, "file_not_found", "That file is not in this config.");

            // Before any tracked change: both calls may store blobs (SaveChanges).
            var (rawHash, rawSize) = await transform.PutTextAsync(userId, request.Text, ct);
            var roots = await transform.RootsAsync(device, ct);
            var edited = (await transform.CanonicalizeAsync(userId, emulatorId, [new ManifestEntry(path, rawHash, rawSize)], roots,
                headManifest, ct)).Single();
            var canonical = headManifest.Select(e => e.Path == path ? edited : e).ToList();
            var hash = Manifests.Hash(canonical);

            if (head.ManifestHash == hash)
            {
                response = new ConfigUploadResponse(SaveUploadStatus.Unchanged, bundle.Id, head.Id);
            }
            else
            {
                var version = await NewVersionAsync(bundle.Id, head.Id, null, ConfigSource.WebEdit, Manifests.ToJson(canonical),
                    hash, canonical.Sum(e => e.Size), ct);
                bundle.HeadVersionId = version.Id;
                response = new ConfigUploadResponse(SaveUploadStatus.Accepted, bundle.Id, version.Id);
            }
            await db.SaveChangesAsync(ct);
            if (tx is not null) await tx.CommitAsync(ct);
        }

        if (response.Status == SaveUploadStatus.Accepted)
        {
            await events.Raise(new ConfigsChanged(userId), ct);
            await activity.WriteAsync(userId, "config_uploaded", $"The {Games.EmulatorNames.Display(emulatorId)} config file {path} was edited in the browser",
                null, emulatorId, data: new { bundleId, versionId = response.VersionId, path });
        }
        return response;
    }

    public async Task ResolveAsync(Guid userId, Guid bundleId, ResolveConflictRequest request, CancellationToken ct)
    {
        if (request is null || !Enum.IsDefined(request.Choice))
            throw new ApiException(400, "bad_request", "Unknown choice.");
        if (request.Choice == ResolveChoice.KeepBoth)
            throw new ApiException(400, "not_supported", "Keep both is not offered for configs; keep one version.");

        string emulatorId;
        using (await locks.Acquire(userId, ct))
        {
            await using var tx = await BeginAsync(ct);
            var bundle = await LoadBundleForUpdateAsync(userId, bundleId, ct)
                ?? throw new ApiException(404, "bundle_not_found", "Config bundle not found.");
            var open = await db.ConfigVersions.Where(v => v.BundleId == bundle.Id && v.ConflictReason != null && !v.Resolved).ToListAsync(ct);
            if (open.Count == 0)
                throw new ApiException(409, "no_conflict", "This config has no open conflict.");
            var keep = request.KeepVersionId is Guid keepId
                ? await db.ConfigVersions.FirstOrDefaultAsync(v => v.Id == keepId && v.BundleId == bundle.Id, ct)
                : null;
            if (keep is null)
                throw new ApiException(400, "bad_version", "KeepVersionId must be a version of this config.");

            foreach (var v in open) v.Resolved = true;
            bundle.HeadVersionId = keep.Id;
            bundle.OpenConflict = false;
            // The winning device already holds the head, but only if the winner is one of the conflict versions being
            // settled and that device's latest upload (a winner picked from history says nothing about its files now).
            if (open.Any(v => v.Id == keep.Id) && keep.DeviceId is Guid uploader)
            {
                var latest = await db.ConfigVersions.Where(v => v.BundleId == bundle.Id && v.DeviceId == uploader)
                    .OrderByDescending(v => v.Seq).Select(v => (Guid?)v.Id).FirstOrDefaultAsync(ct);
                var config = await db.DeviceConfigs.FirstOrDefaultAsync(c => c.DeviceId == uploader && c.BundleId == bundle.Id, ct);
                if (latest == keep.Id && config is not null) config.AppliedVersionId = keep.Id;
            }
            await db.SaveChangesAsync(ct);
            if (tx is not null) await tx.CommitAsync(ct);
            emulatorId = bundle.EmulatorId;
        }

        await events.Raise(new ConfigsChanged(userId), ct);
        await activity.WriteAsync(userId, "conflict_resolved", $"{Games.EmulatorNames.Display(emulatorId)} config conflict resolved", null, emulatorId,
            data: new { bundleId });
    }

    public async Task<SyncGroupList> ListGroupsAsync(Guid userId, CancellationToken ct)
    {
        var groups = await db.SyncGroups.AsNoTracking().Where(g => g.UserId == userId)
            .OrderBy(g => g.EmulatorId).ThenBy(g => g.Name).ThenBy(g => g.Id).ToListAsync(ct);
        var result = new List<SyncGroupDto>();
        foreach (var g in groups) result.Add(await GroupDtoAsync(g, ct));
        return new SyncGroupList(result);
    }

    public async Task<SyncGroupDto> CreateGroupAsync(Guid userId, CreateGroupRequest request, CancellationToken ct)
    {
        RequireEmulator(request?.EmulatorId);
        var name = request!.Name?.Trim() ?? "";
        if (name.Length is 0 or > MaxGroupName)
            throw new ApiException(400, "bad_request", $"Name must be 1-{MaxGroupName} characters.");
        await RequireActiveDeviceAsync(userId, request.SeedDeviceId, ct);

        SyncGroup group;
        using (await locks.Acquire(userId, ct))
        {
            await using var tx = await BeginAsync(ct);
            (group, _) = await CreateGroupCoreAsync(userId, request.EmulatorId, name, request.SeedDeviceId, ct);
            await db.SaveChangesAsync(ct);
            if (tx is not null) await tx.CommitAsync(ct);
        }

        await events.Raise(new ConfigsChanged(userId), ct);
        var device = await DeviceNameAsync(request.SeedDeviceId, ct);
        await activity.WriteAsync(userId, "group_changed", $"Created {Games.EmulatorNames.Display(request.EmulatorId)} group {name} from {device}",
            request.SeedDeviceId, request.EmulatorId, data: new { groupId = group.Id });
        return await GroupDtoAsync(group, ct);
    }

    public async Task<SyncGroupDto> JoinAsync(Guid userId, Guid groupId, JoinGroupRequest request, CancellationToken ct)
    {
        if (request is null) throw new ApiException(400, "bad_request", "A device is required.");
        await RequireActiveDeviceAsync(userId, request.DeviceId, ct);

        SyncGroup group;
        bool joined;
        using (await locks.Acquire(userId, ct))
        {
            await using var tx = await BeginAsync(ct);
            group = await FindGroupAsync(userId, groupId, ct);
            var bundle = await LoadBundleForUpdateAsync(userId, group.BundleId, ct)
                ?? throw new InvalidOperationException("Group bundle missing.");
            joined = await JoinCoreAsync(userId, bundle, request.DeviceId, ct);
            await db.SaveChangesAsync(ct);
            if (tx is not null) await tx.CommitAsync(ct);
        }

        if (joined)
        {
            await events.Raise(new ConfigsChanged(userId), ct);
            var device = await DeviceNameAsync(request.DeviceId, ct);
            await activity.WriteAsync(userId, "group_changed", $"{device} joined {Games.EmulatorNames.Display(group.EmulatorId)} group {group.Name}",
                request.DeviceId, group.EmulatorId, data: new { groupId = group.Id });
        }
        return await GroupDtoAsync(group, ct);
    }

    /// <summary>The device becomes solo with a new bundle seeded from the files it holds (normally the group head),
    /// so it needs no delivery.</summary>
    public async Task LeaveAsync(Guid userId, Guid groupId, Guid deviceId, CancellationToken ct)
    {
        SyncGroup group;
        using (await locks.Acquire(userId, ct))
        {
            await using var tx = await BeginAsync(ct);
            group = await FindGroupAsync(userId, groupId, ct);
            var groupBundle = await LoadBundleForUpdateAsync(userId, group.BundleId, ct)
                ?? throw new InvalidOperationException("Group bundle missing.");
            var config = await db.DeviceConfigs.FirstOrDefaultAsync(c => c.DeviceId == deviceId && c.BundleId == groupBundle.Id, ct)
                ?? throw new ApiException(404, "not_member", "That device is not in this group.");

            // What the device holds: the version it last applied (any of the user's bundles), else the group head.
            var held = config.AppliedVersionId is Guid applied
                ? await db.ConfigVersions.FirstOrDefaultAsync(v => v.Id == applied
                                                               && db.ConfigBundles.Any(b => b.Id == v.BundleId && b.UserId == userId), ct)
                : null;
            held ??= await db.ConfigVersions.SingleAsync(v => v.Id == groupBundle.HeadVersionId, ct);

            var solo = NewSoloBundle(userId, group.EmulatorId, deviceId);
            var copy = await NewVersionAsync(solo.Id, null, null, ConfigSource.Copy, held.ManifestJson, held.ManifestHash,
                held.TotalSize, ct);
            solo.HeadVersionId = copy.Id;
            config.BundleId = solo.Id;
            config.AppliedVersionId = copy.Id;
            await db.SaveChangesAsync(ct);
            if (tx is not null) await tx.CommitAsync(ct);
        }

        await events.Raise(new ConfigsChanged(userId), ct);
        var device = await DeviceNameAsync(deviceId, ct);
        await activity.WriteAsync(userId, "group_changed", $"{device} left {Games.EmulatorNames.Display(group.EmulatorId)} group {group.Name}", deviceId,
            group.EmulatorId, data: new { groupId = group.Id });
    }

    /// <summary>Only an empty group (no non-revoked members) can be deleted, with its bundle and versions.</summary>
    public async Task DeleteGroupAsync(Guid userId, Guid groupId, CancellationToken ct)
    {
        SyncGroup group;
        using (await locks.Acquire(userId, ct))
        {
            await using var tx = await BeginAsync(ct);
            group = await FindGroupAsync(userId, groupId, ct);
            var bundle = await LoadBundleForUpdateAsync(userId, group.BundleId, ct);
            var members = await db.DeviceConfigs.Where(c => c.BundleId == group.BundleId).ToListAsync(ct);
            var memberIds = members.Select(m => m.DeviceId).ToList();
            if (await db.Devices.AnyAsync(d => memberIds.Contains(d.Id) && d.RevokedUtc == null, ct))
                throw new ApiException(409, "group_not_empty", "Remove every device from the group first.");

            // ConfigBundle.GroupId -> SyncGroup is NoAction and SyncGroup.BundleId has no FK: dependents go first
            // (EF orders the deletes by the model's relationships).
            db.DeviceConfigs.RemoveRange(members); // revoked members only
            db.ConfigVersions.RemoveRange(await db.ConfigVersions.Where(v => v.BundleId == group.BundleId).ToListAsync(ct));
            if (bundle is not null) db.ConfigBundles.Remove(bundle);
            db.SyncGroups.Remove(group);
            await db.SaveChangesAsync(ct);
            if (tx is not null) await tx.CommitAsync(ct);
        }

        await events.Raise(new ConfigsChanged(userId), ct);
        await activity.WriteAsync(userId, "group_changed", $"Deleted {Games.EmulatorNames.Display(group.EmulatorId)} group {group.Name}", null, group.EmulatorId,
            data: new { groupId = group.Id });
    }

    public async Task<ApplyConfigResponse> ApplyAsync(Guid userId, ApplyConfigRequest request, CancellationToken ct)
    {
        RequireEmulator(request?.EmulatorId);
        if (!Enum.IsDefined(request!.Mode))
            throw new ApiException(400, "bad_request", "Unknown mode.");
        await RequireActiveDeviceAsync(userId, request.TargetDeviceId, ct);
        var emulatorId = request.EmulatorId;
        var targetId = request.TargetDeviceId;

        ApplyConfigResponse response;
        bool changed;
        using (await locks.Acquire(userId, ct))
        {
            await using var tx = await BeginAsync(ct);
            var source = await db.ConfigVersions.Where(v => v.Id == request.SourceVersionId)
                    .Join(db.ConfigBundles.Where(b => b.UserId == userId), v => v.BundleId, b => b.Id, (v, b) => new { Version = v, Bundle = b })
                    .FirstOrDefaultAsync(ct)
                ?? throw new ApiException(404, "version_not_found", "Version not found.");
            if (source.Bundle.EmulatorId != emulatorId)
                throw new ApiException(400, "wrong_emulator", "The source version is for another emulator.");
            var targetConfig = await db.DeviceConfigs.FirstOrDefaultAsync(c => c.DeviceId == targetId && c.EmulatorId == emulatorId, ct);

            if (request.Mode == ApplyMode.Linked)
            {
                if (targetConfig is null)
                    throw new ApiException(400, "no_config", "The target device has not uploaded its config yet.");
                SyncGroup group;
                ConfigBundle groupBundle;
                var ownerGroup = source.Bundle.OwnerDeviceId is Guid owner
                    ? await db.DeviceConfigs.Where(c => c.DeviceId == owner && c.EmulatorId == emulatorId)
                        .Join(db.ConfigBundles.Where(b => b.GroupId != null), c => c.BundleId, b => b.Id, (c, b) => b.GroupId)
                        .FirstOrDefaultAsync(ct)
                    : source.Bundle.GroupId;
                if (ownerGroup is Guid groupId)
                {
                    group = await FindGroupAsync(userId, groupId, ct);
                    groupBundle = await LoadBundleForUpdateAsync(userId, group.BundleId, ct)
                        ?? throw new InvalidOperationException("Group bundle missing.");
                }
                else
                {
                    var seed = source.Bundle.OwnerDeviceId!.Value;
                    if (seed == targetId)
                        throw new ApiException(400, "same_device", "The source is the target's own config.");
                    (group, groupBundle) = await CreateGroupCoreAsync(userId, emulatorId, $"{emulatorId} group", seed, ct);
                }
                // The group must carry the chosen version: when its head differs, that version's files become a new head.
                var groupHead = await db.ConfigVersions.FindAsync([groupBundle.HeadVersionId!.Value], ct)
                    ?? throw new InvalidOperationException("Group head version missing."); // FindAsync sees a just-created, unsaved head
                var headChanged = groupHead.ManifestHash != source.Version.ManifestHash;
                if (headChanged)
                {
                    if (groupBundle.OpenConflict)
                        throw new ApiException(409, "conflict_open", "Resolve the group's open config conflict first.");
                    var copy = await NewVersionAsync(groupBundle.Id, groupHead.Id, null, ConfigSource.Copy, source.Version.ManifestJson,
                        source.Version.ManifestHash, source.Version.TotalSize, ct);
                    groupBundle.HeadVersionId = copy.Id;
                }
                changed = await JoinCoreAsync(userId, groupBundle, targetId, ct) || ownerGroup is null || headChanged;
                response = new ApplyConfigResponse(groupBundle.Id, groupBundle.HeadVersionId!.Value, group.Id);
            }
            else
            {
                // The target's own files must already be on the server before anything replaces them.
                if (targetConfig is null)
                    throw new ApiException(400, "no_config", $"Open {emulatorId} once on that device so its config is backed up first.");
                var bundle = await LoadBundleForUpdateAsync(userId, targetConfig.BundleId, ct)
                    ?? throw new InvalidOperationException("Config bundle vanished while locked.");
                if (bundle.GroupId is not null)
                    throw new ApiException(409, "already_in_group", "The target follows a group; remove it from the group first.");
                if (bundle.OpenConflict)
                    throw new ApiException(409, "conflict_open", "Resolve the target's open config conflict first.");
                var head = bundle.HeadVersionId is Guid headId ? await db.ConfigVersions.SingleAsync(v => v.Id == headId, ct) : null;
                changed = head is null || head.ManifestHash != source.Version.ManifestHash;
                if (changed)
                {
                    var copy = await NewVersionAsync(bundle.Id, head?.Id, null, ConfigSource.Copy, source.Version.ManifestJson,
                        source.Version.ManifestHash, source.Version.TotalSize, ct);
                    bundle.HeadVersionId = copy.Id;
                }
                response = new ApplyConfigResponse(bundle.Id, bundle.HeadVersionId!.Value, null);
            }

            await db.SaveChangesAsync(ct);
            if (tx is not null) await tx.CommitAsync(ct);
        }

        if (changed)
        {
            await events.Raise(new ConfigsChanged(userId), ct);
            var device = await DeviceNameAsync(targetId, ct);
            var how = request.Mode == ApplyMode.Linked ? "linked to" : "copied from";
            await activity.WriteAsync(userId, "config_applied", $"{device}'s {Games.EmulatorNames.Display(emulatorId)} config was {how} another config", targetId,
                emulatorId, data: new { sourceVersionId = request.SourceVersionId, mode = request.Mode.ToString(), response.BundleId });
        }
        return response;
    }

    /// <summary>Creates a group whose bundle starts as a copy of the seed device's head; the seed follows it.
    /// Caller holds the user lock and saves.</summary>
    private async Task<(SyncGroup Group, ConfigBundle Bundle)> CreateGroupCoreAsync(Guid userId, string emulatorId, string name,
        Guid seedDeviceId, CancellationToken ct)
    {
        var config = await db.DeviceConfigs.FirstOrDefaultAsync(c => c.DeviceId == seedDeviceId && c.EmulatorId == emulatorId, ct)
            ?? throw new ApiException(400, "no_config", "The seed device has not uploaded its config yet.");
        var seedBundle = await LoadBundleForUpdateAsync(userId, config.BundleId, ct)
            ?? throw new InvalidOperationException("Config bundle vanished while locked.");
        if (seedBundle.GroupId is not null)
            throw new ApiException(409, "already_in_group", "The seed device is already in a group for this emulator.");
        if (seedBundle.OpenConflict)
            throw new ApiException(409, "conflict_open", "Resolve the seed device's open config conflict first.");
        var head = seedBundle.HeadVersionId is Guid headId ? await db.ConfigVersions.SingleAsync(v => v.Id == headId, ct) : null;
        if (head is null)
            throw new ApiException(400, "no_config", "The seed device has not uploaded its config yet.");

        var group = new SyncGroup { Id = Guid.NewGuid(), UserId = userId, EmulatorId = emulatorId, Name = name, BundleId = Guid.NewGuid() };
        var bundle = new ConfigBundle { Id = group.BundleId, UserId = userId, EmulatorId = emulatorId, GroupId = group.Id };
        db.SyncGroups.Add(group);
        db.ConfigBundles.Add(bundle);
        var first = await NewVersionAsync(bundle.Id, null, seedDeviceId, ConfigSource.Join, head.ManifestJson, head.ManifestHash,
            head.TotalSize, ct);
        bundle.HeadVersionId = first.Id;
        // Same content as the seed's head: if the seed holds that head, it needs no delivery.
        if (config.AppliedVersionId == head.Id) config.AppliedVersionId = first.Id;
        config.BundleId = bundle.Id;
        return (group, bundle);
    }

    /// <summary>Points the device at the group bundle. Its solo bundle stays as history, and its applied version is
    /// unchanged, so it is sent the group head. Returns false when it already follows the group.</summary>
    private async Task<bool> JoinCoreAsync(Guid userId, ConfigBundle groupBundle, Guid deviceId, CancellationToken ct)
    {
        var config = await db.DeviceConfigs.FirstOrDefaultAsync(c => c.DeviceId == deviceId && c.EmulatorId == groupBundle.EmulatorId, ct)
            ?? throw new ApiException(400, "no_config", "The device has not uploaded its config yet, so it is not backed up.");
        if (config.BundleId == groupBundle.Id) return false;
        var current = await LoadBundleForUpdateAsync(userId, config.BundleId, ct)
            ?? throw new InvalidOperationException("Config bundle vanished while locked.");
        if (current.GroupId is not null)
            throw new ApiException(409, "already_in_group", "The device is already in another group for this emulator.");
        if (current.OpenConflict)
            throw new ApiException(409, "conflict_open", "Resolve the device's open config conflict first.");
        config.BundleId = groupBundle.Id;
        return true;
    }

    private ConfigBundle NewSoloBundle(Guid userId, string emulatorId, Guid deviceId)
    {
        var bundle = new ConfigBundle { Id = Guid.NewGuid(), UserId = userId, EmulatorId = emulatorId, OwnerDeviceId = deviceId };
        db.ConfigBundles.Add(bundle);
        return bundle;
    }

    private async Task<ConfigVersion> NewVersionAsync(Guid bundleId, Guid? baseId, Guid? deviceId, ConfigSource source,
        string manifestJson, string manifestHash, long totalSize, CancellationToken ct)
    {
        // Versions added earlier in this unit of work are not in the database yet.
        var pending = db.ConfigVersions.Local.Where(v => v.BundleId == bundleId).Select(v => (int?)v.Seq).Max();
        var seq = Math.Max(await db.ConfigVersions.Where(v => v.BundleId == bundleId).MaxAsync(v => (int?)v.Seq, ct) ?? 0, pending ?? 0) + 1;
        var version = new ConfigVersion
        {
            Id = Guid.NewGuid(), BundleId = bundleId, Seq = seq, BaseVersionId = baseId, DeviceId = deviceId, Source = source,
            ReceivedUtc = time.GetUtcNow(), ManifestJson = manifestJson, ManifestHash = manifestHash, TotalSize = totalSize,
        };
        db.ConfigVersions.Add(version);
        return version;
    }

    /// <summary>Keeps only the device's latest raw upload for the emulator (the source of its local values).</summary>
    private async Task<Guid> RecordRawAsync(Guid deviceId, string emulatorId, List<ManifestEntry> raw, CancellationToken ct)
    {
        db.RawConfigUploads.RemoveRange(await db.RawConfigUploads
            .Where(r => r.DeviceId == deviceId && r.EmulatorId == emulatorId).ToListAsync(ct));
        var row = new RawConfigUpload
        {
            Id = Guid.NewGuid(), DeviceId = deviceId, EmulatorId = emulatorId, ManifestJson = Manifests.ToJson(raw),
            ReceivedUtc = time.GetUtcNow(),
        };
        db.RawConfigUploads.Add(row);
        return row.Id;
    }

    private async Task<SyncGroupDto> GroupDtoAsync(SyncGroup group, CancellationToken ct)
    {
        var members = await db.DeviceConfigs.AsNoTracking()
            .Where(c => c.BundleId == group.BundleId && db.Devices.Any(d => d.Id == c.DeviceId && d.RevokedUtc == null))
            .Select(c => c.DeviceId).ToListAsync(ct);
        var head = await db.ConfigBundles.AsNoTracking().Where(b => b.Id == group.BundleId).Select(b => b.HeadVersionId)
            .FirstOrDefaultAsync(ct);
        return new SyncGroupDto(group.Id, group.EmulatorId, group.Name, group.BundleId, head, members.Order().ToList());
    }

    private async Task<SyncGroup> FindGroupAsync(Guid userId, Guid groupId, CancellationToken ct) =>
        await db.SyncGroups.FirstOrDefaultAsync(g => g.Id == groupId && g.UserId == userId, ct)
        ?? throw new ApiException(404, "group_not_found", "Group not found.");

    private async Task RequireActiveDeviceAsync(Guid userId, Guid deviceId, CancellationToken ct)
    {
        if (!await db.Devices.AnyAsync(d => d.Id == deviceId && d.UserId == userId && d.RevokedUtc == null, ct))
            throw new ApiException(404, "device_not_found", "Device not found.");
    }

    private static void RequireEmulator(string? emulatorId)
    {
        if (emulatorId is null || !EmulatorIds.IsKnown(emulatorId))
            throw new ApiException(400, "unknown_emulator", $"Unknown emulator '{emulatorId}'.");
    }

    private async Task<string> DeviceNameAsync(Guid deviceId, CancellationToken ct) =>
        await db.Devices.AsNoTracking().Where(d => d.Id == deviceId).Select(d => d.Name).FirstOrDefaultAsync(ct) ?? "A device";

    private bool IsPostgres => db is PostgresTandemDbContext;

    private async Task<IDbContextTransaction?> BeginAsync(CancellationToken ct) =>
        IsPostgres ? await db.Database.BeginTransactionAsync(ct) : null;

    /// <summary>Loads the user's bundle tracked; on Postgres also takes its row lock for the transaction.</summary>
    private async Task<ConfigBundle?> LoadBundleForUpdateAsync(Guid userId, Guid bundleId, CancellationToken ct)
    {
        if (!IsPostgres)
            return await db.ConfigBundles.FirstOrDefaultAsync(b => b.Id == bundleId && b.UserId == userId, ct);
        var rows = await db.ConfigBundles
            .FromSql($"""SELECT * FROM "ConfigBundles" WHERE "Id" = {bundleId} AND "UserId" = {userId} FOR UPDATE""")
            .ToListAsync(ct);
        return rows.SingleOrDefault();
    }
}

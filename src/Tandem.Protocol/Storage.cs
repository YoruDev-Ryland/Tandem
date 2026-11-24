namespace Tandem.Protocol;

/// <summary>One rule of the move-target check. <see cref="Problem"/> is the text to show when <see cref="Ok"/> is false.</summary>
public sealed record MoveCheckItem(string Name, bool Ok, string? Problem);

/// <summary>The answer to "can the stored files move to this folder": every rule's result and the bytes a move would copy.
/// <see cref="MountLine"/> is the compose line to add when the folder is not a mount inside a container.</summary>
public sealed record MoveCheckResult(string Path, bool Ok, long BytesToCopy, IReadOnlyList<MoveCheckItem> Items, string? MountLine);

/// <summary>A folder to check or move the stored files to.</summary>
public sealed record MovePathRequest(string Path);

/// <summary>Where a move of the stored files is: copying while uploads go on, holding uploads for the last changes,
/// done (the files are in <see cref="MoveStatus.To"/>), or failed (nothing was changed).</summary>
public enum MovePhase { Copying, Holding, Done, Failed }

/// <summary>The last (or running) move of the stored files. <see cref="OldLocation"/> and <see cref="OldBytes"/> name the
/// old copy a finished move left behind, until it is removed on request; null and 0 otherwise.</summary>
public sealed record MoveStatus(MovePhase Phase, string From, string To, int FilesDone, int FilesTotal, long BytesDone,
    long BytesTotal, string? Error, string? OldLocation, long OldBytes, DateTimeOffset StartedUtc);

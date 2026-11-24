namespace Tandem.Protocol;

/// <summary>One rule of the move-target check. <see cref="Problem"/> is the text to show when <see cref="Ok"/> is false.</summary>
public sealed record MoveCheckItem(string Name, bool Ok, string? Problem);

/// <summary>The answer to "can the stored files move to this folder": every rule's result and the bytes a move would copy.
/// <see cref="MountLine"/> is the compose line to add when the folder is not a mount inside a container.</summary>
public sealed record MoveCheckResult(string Path, bool Ok, long BytesToCopy, IReadOnlyList<MoveCheckItem> Items, string? MountLine);

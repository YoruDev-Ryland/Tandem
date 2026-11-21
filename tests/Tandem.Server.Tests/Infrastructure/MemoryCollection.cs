namespace Tandem.Server.Tests;

/// <summary>Tests that watch process-wide GC memory run alone: nothing else executes in parallel with this collection.</summary>
[CollectionDefinition("Memory", DisableParallelization = true)]
public class MemoryCollection;

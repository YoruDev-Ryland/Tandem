using EmuSync.Server.Backup;
using EmuSync.Server.Data;

namespace EmuSync.Server.Cli;

/// <summary>
/// Admin commands, run as <c>EmuSync.Server admin &lt;command&gt; [args]</c> (in Docker:
/// <c>docker exec -it emusync ./EmuSync.Server admin …</c>, or a one-off container on the same volume/env). They use
/// the server's own configuration (DATA_DIR, DATABASE_PROVIDER, DATABASE_CONNECTION, …, from the environment), apply
/// migrations, run the command, and exit without starting the web server or background services.
/// <para>To add a command, add one entry to <see cref="Commands"/>.</para>
/// Exit codes: 0 success, 1 the command failed, 2 usage error.
/// </summary>
public static class AdminCli
{
    public const string Verb = "admin";
    public const string PassphraseEnvironment = "EMUSYNC_BACKUP_PASSPHRASE";

    /// <param name="Run">Gets the arguments after the command name.</param>
    public sealed record Command(string Name, string Usage, string Summary,
        Func<string[], IServiceProvider, TextWriter, CancellationToken, Task<int>> Run);

    public static readonly IReadOnlyList<Command> Commands =
    [
        new("restore-backup", "restore-backup <file> [--passphrase X]",
            $"Restore a backup file into an empty server (no users, empty blob directory). An encrypted backup needs its " +
            $"passphrase (or set {PassphraseEnvironment}).",
            RestoreBackupAsync),
    ];

    public static bool IsAdminInvocation(string[] args) => args.Length > 0 && args[0] == Verb;

    /// <summary>The process entry point for <c>admin …</c>: <paramref name="args"/> excludes the verb.</summary>
    public static async Task<int> MainAsync(string[] args)
    {
        // Command arguments are not configuration: build the host from the environment only.
        var builder = WebApplication.CreateBuilder();
        builder.Logging.SetMinimumLevel(LogLevel.Warning);
        ServerSetup.Configure(builder);
        await using var app = builder.Build();
        using var cancel = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; cancel.Cancel(); };
        if (FindCommand(args) is null) return Usage(Console.Error, args.Length == 0 ? null : args[0]);
        app.Services.MigrateDatabase();
        return await RunAsync(args, app.Services, Console.Out, Console.Error, cancel.Token);
    }

    /// <summary>Runs one command against <paramref name="services"/> (migrations already applied).</summary>
    public static async Task<int> RunAsync(string[] args, IServiceProvider services, TextWriter output, TextWriter error, CancellationToken ct)
    {
        var command = FindCommand(args);
        if (command is null) return Usage(error, args.Length == 0 ? null : args[0]);
        try
        {
            return await command.Run(args[1..], services, output, ct) switch
            {
                UsageError => Usage(error, command),
                var code => code,
            };
        }
        catch (BackupException ex)
        {
            await error.WriteLineAsync($"{command.Name}: {ex.Message}");
            return 1;
        }
    }

    private const int UsageError = -2;

    private static Command? FindCommand(string[] args) =>
        args.Length == 0 ? null : Commands.FirstOrDefault(c => c.Name == args[0]);

    private static int Usage(TextWriter error, string? unknown)
    {
        if (unknown is not null) error.WriteLine($"Unknown admin command '{unknown}'.");
        error.WriteLine("Usage: EmuSync.Server admin <command> [args]");
        foreach (var c in Commands)
            error.WriteLine($"  {c.Usage}\n      {c.Summary}");
        return 2;
    }

    private static int Usage(TextWriter error, Command command)
    {
        error.WriteLine($"Usage: EmuSync.Server admin {command.Usage}");
        return 2;
    }

    /// <summary>Splits <c>--name value</c> options from positional arguments. Null when an option lacks its value or is unknown.</summary>
    private static (List<string> Positional, Dictionary<string, string> Options)? Parse(string[] args, params string[] known)
    {
        var positional = new List<string>();
        var options = new Dictionary<string, string>(StringComparer.Ordinal);
        for (var i = 0; i < args.Length; i++)
        {
            if (args[i].StartsWith("--", StringComparison.Ordinal))
            {
                if (!known.Contains(args[i]) || i + 1 >= args.Length) return null;
                options[args[i]] = args[++i];
            }
            else positional.Add(args[i]);
        }
        return (positional, options);
    }

    private static async Task<int> RestoreBackupAsync(string[] args, IServiceProvider services, TextWriter output, CancellationToken ct)
    {
        if (Parse(args, "--passphrase") is not ({ Count: 1 } positional, var options)) return UsageError;
        var path = positional[0];
        if (!File.Exists(path)) throw new BackupException($"No such file: {path}");
        var passphrase = options.GetValueOrDefault("--passphrase") ?? Environment.GetEnvironmentVariable(PassphraseEnvironment);

        await using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 20,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        var result = await BackupRestore.RestoreAsync(services, file, passphrase, ct);
        await output.WriteLineAsync($"Restored {Path.GetFileName(path)}: {result.Users} users, {result.Rows} rows, {result.Blobs} blobs.");
        return 0;
    }
}

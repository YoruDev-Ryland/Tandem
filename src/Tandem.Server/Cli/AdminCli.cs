using Tandem.Server.Accounts;
using Tandem.Server.Backup;
using Tandem.Server.Data;
using Tandem.Server.Devices;
using Microsoft.EntityFrameworkCore;

namespace Tandem.Server.Cli;

/// <summary>
/// Admin commands, run as <c>Tandem.Server admin &lt;command&gt; [args]</c> or with the bare command name
/// (<c>Tandem.Server create-user …</c>). In Docker each command name is a symlink to a script, so
/// <c>docker exec tandem create-user me@x --admin</c> works; a one-off container on the same volume/env also does. They use
/// the server's own configuration (DATA_DIR, DATABASE_PROVIDER, DATABASE_CONNECTION, …, from the environment), apply
/// migrations, run the command, and exit without starting the web server or background services.
/// <para>To add a command, add one entry to <see cref="Commands"/>.</para>
/// Exit codes: 0 success, 1 anything else (the command failed, unknown command, bad arguments), with a message on stderr.
/// </summary>
public static class AdminCli
{
    public const string Verb = "admin";
    public const string PassphraseEnvironment = "TANDEM_BACKUP_PASSPHRASE";

    /// <param name="Run">Gets the arguments after the command name.</param>
    public sealed record Command(string Name, string Usage, string Summary,
        Func<string[], IServiceProvider, TextWriter, CancellationToken, Task<int>> Run);

    public static readonly IReadOnlyList<Command> Commands =
    [
        new("list-users", "list-users", "List the accounts (email, role, active devices).", ListUsersAsync),
        new("create-user", "create-user <email> [--admin]", "Create an account with a generated password, which is printed.",
            CreateUserAsync),
        new("reset-password", "reset-password <email>", "Set a new generated password for an account, print it, and sign out its web sessions (devices stay paired; " +
            "use revoke-device for those).",
            ResetPasswordAsync),
        new("revoke-device", "revoke-device <deviceId>", "Revoke a device: its key stops working.", RevokeDeviceAsync),
        new("restore-backup", "restore-backup <file> [--passphrase X]",
            $"Restore a backup file into an empty server (no users, empty blob directory). An encrypted backup needs its " +
            $"passphrase (or set {PassphraseEnvironment}).",
            RestoreBackupAsync),
    ];

    /// <summary>The command line (verb removed) when <paramref name="args"/> is <c>admin …</c> or starts with a known command
    /// name; null otherwise, so any other first argument (<c>--urls</c>, …) goes to the normal server.</summary>
    public static string[]? CommandArguments(string[] args)
    {
        if (args.Length == 0) return null;
        if (args[0] == Verb) return args[1..];
        return Commands.Any(c => c.Name == args[0]) ? args : null;
    }

    /// <summary>The process entry point for the commands: <paramref name="args"/> starts with the command name.</summary>
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
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            // The message only, never a stack trace: this is for an operator at a terminal.
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
        error.WriteLine("Usage: Tandem.Server admin <command> [args]");
        foreach (var c in Commands)
            error.WriteLine($"  {c.Usage}\n      {c.Summary}");
        return 1;
    }

    private static int Usage(TextWriter error, Command command)
    {
        error.WriteLine($"Usage: Tandem.Server admin {command.Usage}");
        return 1;
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

    private static async Task<int> ListUsersAsync(string[] args, IServiceProvider services, TextWriter output, CancellationToken ct)
    {
        if (args.Length != 0) return UsageError;
        using var scope = services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        var users = await db.Users.AsNoTracking().OrderBy(u => u.CreatedUtc).ThenBy(u => u.Email)
            .Select(u => new { u.Email, u.IsAdmin, Devices = db.Devices.Count(d => d.UserId == u.Id && d.RevokedUtc == null) })
            .ToListAsync(ct);
        foreach (var u in users)
            await output.WriteLineAsync($"{u.Email}\t{(u.IsAdmin ? "admin" : "user")}\t{u.Devices} device(s)");
        return 0;
    }

    private static async Task<int> CreateUserAsync(string[] args, IServiceProvider services, TextWriter output, CancellationToken ct)
    {
        var admin = args.Contains("--admin");
        var rest = args.Where(a => a != "--admin").ToArray();
        if (rest.Length != 1 || rest[0].StartsWith("--", StringComparison.Ordinal)) return UsageError;
        using var scope = services.CreateScope();
        var accounts = scope.ServiceProvider.GetRequiredService<AccountService>();
        var email = AccountService.NormalizeEmail(rest[0]);
        var password = AccountService.GeneratePassword();
        await accounts.CreateUser(email, password, admin, ct);
        await output.WriteLineAsync($"Created {(admin ? "admin " : "")}account {email}");
        await output.WriteLineAsync($"Password: {password}");
        return 0;
    }

    private static async Task<int> ResetPasswordAsync(string[] args, IServiceProvider services, TextWriter output, CancellationToken ct)
    {
        if (args.Length != 1 || args[0].StartsWith("--", StringComparison.Ordinal)) return UsageError;
        using var scope = services.CreateScope();
        var accounts = scope.ServiceProvider.GetRequiredService<AccountService>();
        var email = AccountService.NormalizeEmail(args[0]);
        var password = AccountService.GeneratePassword();
        await accounts.ResetPassword(email, password, ct);
        await output.WriteLineAsync($"New password for {email}");
        await output.WriteLineAsync($"Password: {password}");
        return 0;
    }

    private static async Task<int> RevokeDeviceAsync(string[] args, IServiceProvider services, TextWriter output, CancellationToken ct)
    {
        if (args.Length != 1) return UsageError;
        if (!Guid.TryParse(args[0], out var id)) throw new InvalidOperationException($"'{args[0]}' is not a device id.");
        using var scope = services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        var userId = await db.Devices.AsNoTracking().Where(d => d.Id == id).Select(d => (Guid?)d.UserId).FirstOrDefaultAsync(ct)
            ?? throw new InvalidOperationException($"No device {id}.");
        await scope.ServiceProvider.GetRequiredService<DeviceService>().Revoke(userId, id, ct);
        await output.WriteLineAsync($"Revoked device {id}");
        return 0;
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

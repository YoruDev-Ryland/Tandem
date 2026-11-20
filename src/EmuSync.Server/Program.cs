using EmuSync.Server;
using EmuSync.Server.Cli;

// `EmuSync.Server admin <command> ...` runs an admin command and exits (see AdminCli).
if (AdminCli.IsAdminInvocation(args)) return await AdminCli.MainAsync(args[1..]);

var builder = WebApplication.CreateBuilder(args);
ServerSetup.Configure(builder);

var app = builder.Build();
ServerSetup.Map(app);
app.Run();
return 0;

public partial class Program;

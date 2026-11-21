using Tandem.Server;
using Tandem.Server.Cli;

// `Tandem.Server admin <command> ...` or `Tandem.Server <command> ...` runs an admin command and exits (see AdminCli).
// Any other first argument is for the normal server.
if (AdminCli.CommandArguments(args) is { } command) return await AdminCli.MainAsync(command);

var builder = WebApplication.CreateBuilder(args);
ServerSetup.Configure(builder);

var app = builder.Build();
ServerSetup.Map(app);
app.Run();
return 0;

public partial class Program;

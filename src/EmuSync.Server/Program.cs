using EmuSync.Server;

var builder = WebApplication.CreateBuilder(args);
ServerSetup.Configure(builder);

var app = builder.Build();
ServerSetup.Map(app);
app.Run();

public partial class Program;

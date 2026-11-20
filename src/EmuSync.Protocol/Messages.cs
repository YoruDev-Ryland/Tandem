namespace EmuSync.Protocol;

public sealed record ErrorResponse(string Code, string Message);

public sealed record ServerInfo(string Name, int Protocol, bool RegistrationOpen);

namespace EmuSync.Protocol;

public enum ItemKind { Save, State }

public enum ConflictReason { Concurrent, FirstSync }

public enum DeliveryKind { Save, Config, Vault }

public enum DeliveryResult { Applied, SkippedLocalChanged, Failed }

public enum ConfigSource { Device, WebEdit, Copy, Join }

public enum NotifyKind { Ntfy, Discord, Webhook }

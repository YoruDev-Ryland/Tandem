// TypeScript mirrors of the C# records in src/Tandem.Protocol (camelCase on the wire, enums as strings).
// Guid and DateTimeOffset are strings (UUID / ISO 8601). Nullable C# members are `T | null` on responses; they are
// optional on requests where the server accepts omission.

export type Guid = string;
export type IsoDate = string;

export type ItemKind = "Save" | "State";
export type ConflictReason = "Concurrent" | "FirstSync";
export type DeliveryKind = "Save" | "Config" | "Vault";
export type ConfigSource = "Device" | "WebEdit" | "Copy" | "Join";
export type NotifyKind = "Ntfy" | "Discord" | "Webhook";
export type BackupTargetKind = "Folder" | "S3";
export type SaveUploadStatus = "Accepted" | "Unchanged" | "Conflict" | "Superseded";
export type ResolveChoice = "KeepVersion" | "KeepBoth";
export type FirstSyncChoice = "UseDevice" | "UseServer";
export type ApplyMode = "Linked" | "Copy";
export type FileDiffStatus = "Added" | "Removed" | "Changed" | "Same";
export type DeviceGameState = "Newest" | "HasIt" | "Pending" | "Waiting" | "Behind" | "NotInstalled" | "Differs";
export type SetupStepKind =
  | "InstallEmulator"
  | "InstallCores"
  | "GrantFolders"
  | "PlaceVault"
  | "RestoreSaves"
  | "ChooseConfig";

export type EmulatorId = "eden" | "retroarch" | "azahar" | "ppsspp" | "vita3k" | "dolphin" | "melonds";
/** Module order: the app's ModuleRegistry order, which the shelf's strip and the gamepad's Up/Down follow. */
export const EMULATOR_IDS: readonly EmulatorId[] = ["eden", "dolphin", "retroarch", "melonds", "azahar", "ppsspp", "vita3k"];

// ---- Errors, server, accounts ----
export interface ErrorResponse { code: string; message: string }
/** version: the server build (InformationalVersion without the +hash). */
export interface ServerInfo { name: string; protocol: number; registrationOpen: boolean; version: string }
export interface WebSessionRequest { email?: string | null; password?: string | null; pairingCode?: string | null; deviceName?: string | null }
export interface PairingCodeResponse { code: string; qrPayload: string; expiresUtc: IsoDate }
export interface ChangeEmailRequest { newEmail: string; password: string }
export interface AccountInfo { id: Guid; email: string; isAdmin: boolean }
export interface ChangePasswordRequest { current: string; new: string }
export interface UserInfo { id: Guid; email: string; isAdmin: boolean; createdUtc: IsoDate; deviceCount: number }
export interface CreateUserRequest { email: string; isAdmin: boolean }
export interface CreatedUserResponse { email: string; password: string }
export interface ResetPasswordResponse { password: string }
export interface DeleteAccountRequest { password: string }
export interface AdminSettings { allowRegistration: boolean }
export interface DeviceSummary {
  id: Guid;
  name: string;
  platform: string;
  lastSeenUtc: IsoDate | null;
  shizukuOk: boolean | null;
  isCurrent: boolean;
  runningEmulators: string[];
}
export interface RenameDeviceRequest { name: string }

// ---- Manifests and saves ----
export interface ManifestEntry { path: string; hash: string; size: number }
export interface ResolveConflictRequest { choice: ResolveChoice; keepVersionId?: Guid | null; variantName?: string | null }
export interface BulkFirstSyncRequest { deviceId: Guid; choice: FirstSyncChoice; itemIds?: Guid[] | null }
export interface RestoreRequest { deviceId: Guid }
export interface RestoreResponse { itemId: Guid; versionId: Guid }
export interface SaveUploadResponse { status: SaveUploadStatus; itemId: Guid; versionId: Guid; headVersionId: Guid }

// ---- Activity ----
export interface ActivityItem {
  id: number;
  utc: IsoDate;
  kind: string;
  message: string;
  deviceId: Guid | null;
  deviceName: string | null;
  emulatorId: string | null;
  gameKey: string | null;
}
export interface ActivityPage { items: ActivityItem[]; nextBefore: number | null }
export interface ActivityFilter { device?: Guid; emulator?: string; game?: string; before?: number; limit?: number }

// ---- Configs ----
export interface CreateGroupRequest { emulatorId: string; name: string; seedDeviceId: Guid }
export interface JoinGroupRequest { deviceId: Guid }
export interface SyncGroupDto { id: Guid; emulatorId: string; name: string; bundleId: Guid; headVersionId: Guid | null; memberDeviceIds: Guid[] }
export interface SyncGroupList { items: SyncGroupDto[] }
export interface ApplyConfigRequest { targetDeviceId: Guid; emulatorId: string; sourceVersionId: Guid; mode: ApplyMode }
export interface ApplyConfigResponse { bundleId: Guid; headVersionId: Guid; groupId: Guid | null }
export interface ConfigUploadResponse { status: SaveUploadStatus; bundleId: Guid; versionId: Guid }
export interface ConfigVersionSummary {
  id: Guid;
  seq: number;
  source: ConfigSource;
  deviceName: string | null;
  receivedUtc: IsoDate;
  totalSize: number;
  pinned: boolean;
  conflictReason: ConflictReason | null;
  resolved: boolean;
}
export interface DiffLine { op: " " | "+" | "-"; text: string }
export interface DiffHunk { oldStart: number; newStart: number; lines: DiffLine[] }
export interface FileDiff { path: string; status: FileDiffStatus; hunks: DiffHunk[] }
export interface ConfigDiffResponse { files: FileDiff[] }
export interface EditConfigFileRequest { baseVersionId: Guid; text: string }

// ---- Vault ----
export interface VaultHolder { deviceId: Guid; deviceName: string; current: boolean }
export interface VaultItemDto {
  id: Guid;
  category: string;
  emulatorId: string | null;
  name: string;
  headVersionId: Guid;
  totalSize: number;
  holders: VaultHolder[];
}
export interface SendVaultRequest { deviceId: Guid }
export interface SendVaultResponse { deliveryId: Guid; versionId: Guid }

// ---- Settings ----
export interface RetentionSettingsDto { saveVersions: number; configVersions: number; vaultVersions: number; stateMaxAgeDays?: number }
export interface StorageRow { emulatorId: string; gameKey: string | null; displayName: string | null; bytes: number }
/** One game's stored bytes: unique blobs across its versions, split into the heads' (current) and only older versions'. */
export interface StorageGame {
  emulatorId: string;
  gameKey: string;
  displayName: string | null;
  bytes: number;
  currentBytes: number;
  olderBytes: number;
  versions: number;
  /** "Save" and/or "State". */
  kinds: string[];
}
/** Unique bytes each kind references; a blob shared between kinds counts in each. */
export interface StorageKinds { saves: number; states: number; configs: number; vault: number; lostAndFound: number }
export type MovePhase = "Copying" | "Holding" | "Done" | "Failed";
export interface MoveStatus {
  phase: MovePhase;
  from: string;
  to: string;
  filesDone: number;
  filesTotal: number;
  bytesDone: number;
  bytesTotal: number;
  error: string | null;
  /** The old copy a finished move left behind, until removed; null and 0 otherwise. */
  oldLocation: string | null;
  oldBytes: number;
  startedUtc: string;
  /** Files of the old copy a removal left because the new folder does not hold them. */
  oldKept: number;
}
export interface MoveCheckItem { name: string; ok: boolean; problem: string | null }
export interface MoveCheckResult { path: string; ok: boolean; bytesToCopy: number; items: MoveCheckItem[]; mountLine: string | null }
/** Server-wide figures, admins only. databaseBytes is null for Postgres. */
export interface StorageServer {
  filesBytes: number;
  diskTotalBytes: number | null;
  diskFreeBytes: number | null;
  minFreeBytes: number;
  artBytes: number;
  databaseBytes: number | null;
  filesLocation: string;
  defaultLocation: boolean;
  move: MoveStatus | null;
  lowDisk: string | null;
}
export type StorageView = "map" | "bars" | "blocks";
export interface StorageUsage {
  totalBytes: number;
  byEmulator: StorageRow[];
  topGames: StorageRow[];
  games: StorageGame[];
  byKind: StorageKinds;
  olderBytes: number;
  server: StorageServer | null;
}
/**
 * token is write-only (PUT); tokenSet is returned by GET. GET returns `url` masked as "scheme://host/…"; a PUT that sends
 * the masked value back unchanged keeps the stored address.
 */
export interface NotifyTarget { kind: NotifyKind; url: string; token?: string | null; tokenSet?: boolean }
export interface NotifyEvents { conflicts: boolean; failedSyncs: boolean; shizukuOff: boolean; backupFailed: boolean; silentDevices: boolean; storageLow?: boolean }
export interface NotifySettingsDto { targets: NotifyTarget[]; events: NotifyEvents }
export interface NotifyTestResult { kind: NotifyKind; ok: boolean; status?: number | null; error?: string | null }

// ---- Backup (admin) ----
/** secretKey is write-only: null in responses; null on PUT keeps the stored one. */
export interface S3Target {
  endpoint: string | null;
  bucket: string;
  prefix: string | null;
  accessKey: string;
  secretKey: string | null;
  region: string | null;
}
/** passphrase is write-only: null keeps the stored one, "" clears it. */
export interface BackupSettingsDto {
  enabled: boolean;
  kind: BackupTargetKind;
  folderPath: string | null;
  s3: S3Target | null;
  hourUtc: number;
  keep: number;
  encrypt: boolean;
  passphrase: string | null;
}
export interface BackupSettingsResponse {
  enabled: boolean;
  kind: BackupTargetKind;
  folderPath: string | null;
  s3: S3Target | null;
  hourUtc: number;
  keep: number;
  encrypt: boolean;
  passphraseSet: boolean;
  running: boolean;
}
export interface BackupRunInfo { utc: IsoDate; ok: boolean; bytes: number; error: string | null; partial?: boolean }

// ---- Web read models (/v1/web/*) ----
export interface ContinueItem {
  itemId: Guid;
  emulatorId: string;
  gameKey: string;
  displayName: string | null;
  newestDeviceName: string;
  newestUtc: IsoDate;
}
export interface Attention {
  openConflicts: number;
  firstSyncReviews: number;
  shizukuOffDevices: Guid[];
  failedDeliveries24h: number;
  configConflicts: number;
  /** Free space in the server's data folder is low; uploads are refused with 507 storage_full. */
  diskLow: boolean;
  /** Admins only: no backup target is set. */
  backupNotConfigured: boolean;
  /** Active devices not seen for the server's SilentDeviceHours (default 72). */
  silentDevices: Guid[];
  /** Admins only: the stored files folder is missing (an unmounted disk); nothing is stored until it is back. */
  filesMissing: boolean;
  /** Admins only, while filesMissing: the folder that is missing (null when unknown). */
  filesMissingPath: string | null;
}
export interface HomeResponse { continue: ContinueItem[]; devices: DeviceSummary[]; attention: Attention; recent: ActivityItem[] }
export interface GameDeviceState { deviceId: Guid; deviceName: string; state: DeviceGameState }
export interface GameRow {
  emulatorId: string;
  gameKey: string;
  displayName: string | null;
  installedOn: Guid[];
  saveItemId: Guid | null;
  newestOnDeviceId: Guid | null;
  newestUtc: IsoDate | null;
  stateCount: number;
  openConflict: boolean;
  devices: GameDeviceState[];
  newestOnDeviceName: string | null;
  /** The RetroArch core of the game's save item, or null. */
  core: string | null;
  /** The head version of the game's save item (what Y downloads), or null. */
  headVersionId: Guid | null;
  /** The game's system label as the server settled it (GBA, GC, …); absent on older servers. */
  system?: string | null;
  /** True when the server can give a real case spine at /v1/web/spine. */
  spine?: boolean;
}
export interface VersionSummary {
  id: Guid;
  seq: number;
  deviceName: string | null;
  receivedUtc: IsoDate;
  totalSize: number;
  core: string;
  variant: string | null;
  pinned: boolean;
  conflictReason: ConflictReason | null;
  isHead: boolean;
  resolved: boolean;
  manifestHash: string;
  fileCount: number;
  /** The uploading device's version of the emulator at upload time, when it reported one. */
  emulatorVersion: string | null;
}
export interface ConflictSide {
  versionId: Guid;
  deviceId: Guid | null;
  deviceName: string | null;
  receivedUtc: IsoDate;
  totalSize: number;
  fileCount: number;
  manifestHash: string;
  isHead: boolean;
}
export interface ConflictRow {
  itemId: Guid;
  emulatorId: string;
  gameKey: string;
  displayName: string | null;
  kind: ItemKind;
  slot: string | null;
  core: string | null;
  reason: ConflictReason;
  sides: ConflictSide[];
}
export interface SaveItemDetail {
  itemId: Guid;
  kind: ItemKind;
  slot: string;
  core: string;
  headVersionId: Guid | null;
  openConflict: boolean;
  versions: VersionSummary[];
}
export interface GameDetail { row: GameRow; items: SaveItemDetail[] }
export interface EmulatorRow { emulatorId: string; package: string; version: string | null; cores: string[] | null }
export interface DeviceConfigRow {
  emulatorId: string;
  bundleId: Guid;
  groupId: Guid | null;
  groupName: string | null;
  appliedVersionId: Guid | null;
  headVersionId: Guid | null;
  upToDate: boolean;
  openConflict: boolean;
}
export interface InstalledCount { emulatorId: string; games: number }
export interface DeviceDetail {
  summary: DeviceSummary;
  emulators: EmulatorRow[];
  roots: Record<string, string>;
  configs: DeviceConfigRow[];
  pendingDeliveries: number;
  storageBytes: number;
  installs: InstalledCount[];
}
export interface DeviceRef { id: Guid; name: string }
export interface GroupRow { id: Guid; name: string; members: DeviceRef[]; headVersionId: Guid | null; openConflict: boolean }
export interface SoloRow { device: DeviceRef; bundleId: Guid; headVersionId: Guid | null; openConflict: boolean }
export interface EmulatorConfigRow { emulatorId: string; groups: GroupRow[]; solo: SoloRow[] }
export interface ConfigOverview { emulators: EmulatorConfigRow[] }
export interface SetupStep { kind: SetupStepKind; emulatorId: string; label: string; done: boolean; detail: string | null }
export interface SetupChecklist { steps: SetupStep[] }

/** A save or state in Lost & found (the newest copy of an item whose game was deleted). Slot and core are "" when empty. */
export interface LostItemRow {
  id: Guid;
  emulatorId: string;
  kind: ItemKind;
  gameKey: string;
  slot: string;
  core: string;
  displayName: string | null;
  totalSize: number;
  /** "game_deleted" for now. */
  reason: string;
  lostUtc: string;
}

// ---- SSE ----
export type Topic = "activity" | "saves" | "conflicts" | "configs" | "vault" | "devices" | "lost" | "storage";
export const TOPICS: readonly Topic[] = ["activity", "saves", "conflicts", "configs", "vault", "devices", "lost", "storage"];

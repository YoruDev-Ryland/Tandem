import { api, apiBytes, apiText } from "./http";
import { encodeKey } from "./paths";
import type * as T from "./types";

const enc = encodeURIComponent;

function query(params: Record<string, string | number | undefined | null>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== "") p.set(k, String(v));
  const s = p.toString();
  return s ? `?${s}` : "";
}

// ---- Session and account ----
export const getServerInfo = () => api<T.ServerInfo>("GET", "/v1/server-info");
export const signIn = (req: T.WebSessionRequest) => api<T.AccountInfo>("POST", "/v1/web/session", req);
export const signOut = () => api<void>("DELETE", "/v1/web/session");
export const getAccount = () => api<T.AccountInfo>("GET", "/v1/account");
export const changePassword = (req: T.ChangePasswordRequest) => api<void>("POST", "/v1/account/password", req);
/** 204 and the session cookie cleared; 403 bad_password; 409 last_admin. */
export const deleteAccount = (password: string) => api<void>("DELETE", "/v1/account", { password } satisfies T.DeleteAccountRequest);

// ---- Home, games, conflicts ----
export const getHome = () => api<T.HomeResponse>("GET", "/v1/web/home");
export const getGames = (q?: string, emulator?: string) =>
  api<T.GameRow[]>("GET", `/v1/web/games${query({ q, emulator })}`);
export const getGame = (emu: string, key: string) =>
  api<T.GameDetail>("GET", `/v1/web/games/${enc(emu)}/${encodeKey(key)}`);
/** Deletes the game's saves and states from the server (404 not_found when there are none). */
export const deleteGame = (emu: string, key: string) => api<void>("DELETE", `/v1/web/games/${enc(emu)}/${encodeKey(key)}`);
export type ArtResult = { kind: "image"; url: string } | { kind: "pending" } | { kind: "none" };

const ART_HEADERS = { "Tandem-Protocol": "1", Accept: "image/png, image/jpeg" };
const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/**
 * The cover for a game's shelf card. 200 gives an object URL (the caller revokes it when focus leaves); 202 is asked
 * once more after 3 s, then "pending"; a 404, an error or a body that isn't an image gives "none". Aborting the signal
 * (focus moved on) gives "none" and stops the retry. The browser's HTTP cache keeps images and known misses.
 */
export async function fetchArt(
  emu: string,
  key: string,
  opts: { signal?: AbortSignal; wait?: (ms: number) => Promise<void> } = {},
): Promise<ArtResult> {
  const { signal, wait = sleep } = opts;
  const once = async (): Promise<ArtResult> => {
    if (signal?.aborted) return { kind: "none" };
    try {
      const res = await fetch(`/v1/web/art/${enc(emu)}/${encodeKey(key)}`, { headers: ART_HEADERS, credentials: "same-origin", signal });
      if (res.status === 202) return { kind: "pending" };
      if (!res.ok || !/^image\//.test(res.headers.get("Content-Type") ?? "")) return { kind: "none" };
      const blob = await res.blob();
      if (signal?.aborted) return { kind: "none" };
      return { kind: "image", url: URL.createObjectURL(blob) };
    } catch {
      return { kind: "none" };
    }
  };
  const first = await once();
  if (first.kind !== "pending") return first;
  await wait(3000);
  if (signal?.aborted) return { kind: "none" };
  return once();
}

export const getConflicts =() => api<T.ConflictRow[]>("GET", "/v1/web/conflicts");
export const resolveConflict = (itemId: string, req: T.ResolveConflictRequest) =>
  api<void>("POST", `/v1/conflicts/${enc(itemId)}/resolve`, req);
export const bulkFirstSync = (deviceId: string, choice: T.FirstSyncChoice, itemIds?: string[]) =>
  api<void>("POST", "/v1/conflicts/first-sync/resolve", { deviceId, choice, itemIds: itemIds ?? null } satisfies T.BulkFirstSyncRequest);
export const restore = (itemId: string, versionId: string, deviceId: string) =>
  api<T.RestoreResponse>("POST", `/v1/saves/${enc(itemId)}/versions/${enc(versionId)}/restore`, { deviceId } satisfies T.RestoreRequest);
export const pin = (itemId: string, versionId: string) =>
  api<void>("POST", `/v1/saves/${enc(itemId)}/pin/${enc(versionId)}`);
export const unpin = (itemId: string, versionId: string) =>
  api<void>("DELETE", `/v1/saves/${enc(itemId)}/pin/${enc(versionId)}`);
/** Multipart: fields emulatorId, kind, gameKey, slot, core, plus one or more files. */
export const uploadSave = (form: FormData) => api<T.SaveUploadResponse>("POST", "/v1/web/saves/upload", form);

// ---- Configs ----
export const getConfigs = () => api<T.ConfigOverview>("GET", "/v1/web/configs");
export const getConfigVersions = (bundleId: string) =>
  api<T.ConfigVersionSummary[]>("GET", `/v1/configs/bundles/${enc(bundleId)}/versions`);
export const getConfigFile = (versionId: string, path: string, device?: string) =>
  apiText("GET", `/v1/configs/versions/${enc(versionId)}/files/${encodeKey(path)}${query({ device })}`);
/** The file's exact bytes, for editing and downloading. */
export const getConfigFileBytes = (versionId: string, path: string, device?: string) =>
  apiBytes("GET", `/v1/configs/versions/${enc(versionId)}/files/${encodeKey(path)}${query({ device })}`);
export const getDiff = (a: string, b: string, device?: string) =>
  api<T.ConfigDiffResponse>("GET", `/v1/configs/diff${query({ a, b, device })}`);
export const editConfigFile = (bundleId: string, path: string, req: T.EditConfigFileRequest, device?: string) =>
  api<T.ConfigUploadResponse>("PUT", `/v1/configs/bundles/${enc(bundleId)}/files/${encodeKey(path)}${query({ device })}`, req);
export const resolveConfigConflict = (bundleId: string, req: T.ResolveConflictRequest) =>
  api<void>("POST", `/v1/configs/bundles/${enc(bundleId)}/conflicts/resolve`, req);
export const applyConfig = (req: T.ApplyConfigRequest) => api<T.ApplyConfigResponse>("POST", "/v1/configs/apply", req);
export const getGroups = () => api<T.SyncGroupList>("GET", "/v1/configs/groups");
export const createGroup = (req: T.CreateGroupRequest) => api<T.SyncGroupDto>("POST", "/v1/configs/groups", req);
export const joinGroup = (groupId: string, deviceId: string) =>
  api<T.SyncGroupDto>("POST", `/v1/configs/groups/${enc(groupId)}/members`, { deviceId } satisfies T.JoinGroupRequest);
export const leaveGroup = (groupId: string, deviceId: string) =>
  api<void>("DELETE", `/v1/configs/groups/${enc(groupId)}/members/${enc(deviceId)}`);
export const deleteGroup = (groupId: string) => api<void>("DELETE", `/v1/configs/groups/${enc(groupId)}`);

// ---- Devices ----
export const getDevices = () => api<T.DeviceSummary[]>("GET", "/v1/devices");
export const getDevice = (id: string) => api<T.DeviceDetail>("GET", `/v1/web/devices/${enc(id)}`);
export const getSetup = (id: string) => api<T.SetupChecklist>("GET", `/v1/web/devices/${enc(id)}/setup`);
export const keepOwn = (id: string, emulatorId: string) =>
  api<void>("POST", `/v1/web/devices/${enc(id)}/setup/keep-own/${enc(emulatorId)}`);
export const renameDevice = (id: string, name: string) =>
  api<T.DeviceSummary>("PATCH", `/v1/devices/${enc(id)}`, { name } satisfies T.RenameDeviceRequest);
export const revokeDevice = (id: string) => api<void>("DELETE", `/v1/devices/${enc(id)}`);
export const createPairingCode = () => api<T.PairingCodeResponse>("POST", "/v1/pairing/codes");

// ---- Vault ----
export const getVault = () => api<T.VaultItemDto[]>("GET", "/v1/vault");
/** Registers an item from blobs already uploaded to the server. */
export const vaultUpload = (req: { category: string; emulatorId?: string | null; name: string; manifest: T.ManifestEntry[] }) =>
  api<{ itemId: string; versionId: string; unchanged: boolean }>("POST", "/v1/vault/upload", req);
export const sendVault = (itemId: string, deviceId: string) =>
  api<T.SendVaultResponse>("POST", `/v1/vault/${enc(itemId)}/send`, { deviceId } satisfies T.SendVaultRequest);

// ---- Activity ----
export const getActivity = (filter: T.ActivityFilter = {}) =>
  api<T.ActivityPage>("GET", `/v1/activity${query({ ...filter })}`);

// ---- Settings ----
export const getRetention = () => api<T.RetentionSettingsDto>("GET", "/v1/settings/retention");
export const putRetention = (req: T.RetentionSettingsDto) => api<T.RetentionSettingsDto>("PUT", "/v1/settings/retention", req);
export const getStorage = () => api<T.StorageUsage>("GET", "/v1/settings/storage");
export const getNotifications = () => api<T.NotifySettingsDto>("GET", "/v1/settings/notifications");
export const putNotifications = (req: T.NotifySettingsDto) => api<T.NotifySettingsDto>("PUT", "/v1/settings/notifications", req);
export const testNotifications = () => api<T.NotifyTestResult[]>("POST", "/v1/settings/notifications/test");

// ---- Admin ----
export const getUsers = () => api<T.UserInfo[]>("GET", "/v1/admin/users");
export const createUser = (req: T.CreateUserRequest) => api<T.CreatedUserResponse>("POST", "/v1/admin/users", req);
export const deleteUser = (id: string) => api<void>("DELETE", `/v1/admin/users/${enc(id)}`);
/** A new generated password, shown once; the user's web sessions are signed out. */
export const resetUserPassword = (id: string) => api<T.ResetPasswordResponse>("POST", `/v1/admin/users/${enc(id)}/reset-password`);
export const getAdminSettings = () => api<T.AdminSettings>("GET", "/v1/admin/settings");
export const putAdminSettings = (req: T.AdminSettings) => api<T.AdminSettings>("PUT", "/v1/admin/settings", req);
export const getBackup = () => api<T.BackupSettingsResponse>("GET", "/v1/admin/backup");
export const putBackup = (req: T.BackupSettingsDto) => api<T.BackupSettingsResponse>("PUT", "/v1/admin/backup", req);
export const runBackup = () => api<void>("POST", "/v1/admin/backup/run");
export const getBackupHistory = () => api<T.BackupRunInfo[]>("GET", "/v1/admin/backup/history");

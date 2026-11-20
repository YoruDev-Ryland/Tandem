import { uploadSave } from "./api/endpoints";
import type { ItemKind, SaveUploadResponse } from "./api/types";

/** Sends files for one save item as multipart to the server. */
export function uploadFiles(
  emulatorId: string,
  gameKey: string,
  kind: ItemKind,
  slot: string,
  core: string,
  files: File[],
): Promise<SaveUploadResponse> {
  const form = new FormData();
  form.set("emulatorId", emulatorId);
  form.set("kind", kind);
  form.set("gameKey", gameKey);
  form.set("slot", slot);
  form.set("core", core);
  for (const f of files) form.append("files", f, f.name);
  return uploadSave(form);
}

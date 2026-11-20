import { uploadSave } from "./api/endpoints";
import type { ItemKind, SaveUploadResponse } from "./api/types";

/** A file and the path it takes inside the save, relative to the save's folder ("data.dat", "sub/x.bin"). */
export interface UploadFile {
  file: File;
  path: string;
}

/** Thrown for input the browser would not let us read, with copy the page can show as is. */
export class UnreadableUpload extends Error {}

const UNREADABLE = "Couldn't read that folder. Drop the files inside it instead, or use Upload a folder.";

/** Sends files for one save item as multipart; each part is named by its relative path. */
export function uploadFiles(
  emulatorId: string,
  gameKey: string,
  kind: ItemKind,
  slot: string,
  core: string,
  files: UploadFile[],
): Promise<SaveUploadResponse> {
  const form = new FormData();
  form.set("emulatorId", emulatorId);
  form.set("kind", kind);
  form.set("gameKey", gameKey);
  form.set("slot", slot);
  form.set("core", core);
  for (const f of files) form.append("files", f.file, f.path);
  return uploadSave(form);
}

/** Files from an input: a folder pick keeps paths below the chosen folder, which is the save's root. */
export function fromPicker(list: FileList | File[]): UploadFile[] {
  return [...list].map((file) => {
    const rel = (file as File & { webkitRelativePath?: string }).webkitRelativePath;
    return { file, path: rel ? rel.split("/").slice(1).join("/") || file.name : file.name };
  });
}

/** What a drop carries, captured synchronously: the browser invalidates the items after the event. */
export interface Dropped {
  entries: FileSystemEntry[];
  files: File[];
}

export function captureDrop(dt: DataTransfer): Dropped {
  const entries: FileSystemEntry[] = [];
  for (const item of [...(dt.items ?? [])]) {
    const e = item.kind === "file" ? item.webkitGetAsEntry?.() : null;
    if (e) entries.push(e);
  }
  return { entries, files: [...dt.files] };
}

const readFile = (e: FileSystemFileEntry) => new Promise<File>((ok, no) => e.file(ok, no));

async function readAll(dir: FileSystemDirectoryEntry): Promise<FileSystemEntry[]> {
  const reader = dir.createReader();
  const out: FileSystemEntry[] = [];
  for (;;) {
    const batch = await new Promise<FileSystemEntry[]>((ok, no) => reader.readEntries(ok, no));
    if (batch.length === 0) return out;
    out.push(...batch);
  }
}

async function walk(e: FileSystemEntry, prefix: string, out: UploadFile[]): Promise<void> {
  if (e.isFile) {
    out.push({ file: await readFile(e as FileSystemFileEntry), path: prefix + e.name });
    return;
  }
  for (const child of await readAll(e as FileSystemDirectoryEntry)) await walk(child, prefix + e.name + "/", out);
}

/** Walks dropped folders; a folder's own name is dropped so its contents sit at the save's root. */
export async function collectDrop(d: Dropped): Promise<UploadFile[]> {
  if (d.entries.length === 0) return fromPicker(d.files);
  const out: UploadFile[] = [];
  try {
    for (const e of d.entries) {
      if (e.isFile) await walk(e, "", out);
      else for (const child of await readAll(e as FileSystemDirectoryEntry)) await walk(child, "", out);
    }
  } catch {
    throw new UnreadableUpload(UNREADABLE);
  }
  return out;
}

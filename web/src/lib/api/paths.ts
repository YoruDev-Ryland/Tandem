const seg = (s: string) => encodeURIComponent(s);

/** Percent-encode a game key segment by segment: '/' stays a separator, everything else is escaped. */
export function encodeKey(key: string): string {
  return key.split("/").map(seg).join("/");
}

/** The web app route for a game. */
export function gamePath(emu: string, key: string): string {
  return `/games/${seg(emu)}/${encodeKey(key)}`;
}

export function parseGamePath(path: string): { emu: string; key: string } | null {
  const m = /^\/games\/([^/]+)\/(.+)$/.exec(path);
  if (!m) return null;
  try {
    return { emu: decodeURIComponent(m[1]), key: m[2].split("/").map(decodeURIComponent).join("/") };
  } catch {
    return null;
  }
}

export function downloadUrl(versionId: string): string {
  return `/v1/saves/versions/${seg(versionId)}/download`;
}

export function lostDownloadUrl(id: string): string {
  return `/v1/web/lost/${seg(id)}/download`;
}

export function exportZipUrl(emu?: string): string {
  return emu ? `/v1/export.zip?emulator=${seg(emu)}` : "/v1/export.zip";
}

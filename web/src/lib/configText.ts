/** A config file as the editor shows it, plus what the textarea would lose: the BOM and CRLF line endings. */
export interface EditableText {
  text: string;
  bom: boolean;
  crlf: boolean;
}

/** Decodes UTF-8 without dropping a BOM; the textarea gets LF line endings. */
export function decodeConfig(bytes: ArrayBuffer): EditableText {
  let raw = new TextDecoder("utf-8", { ignoreBOM: true }).decode(bytes);
  const bom = raw.startsWith("\uFEFF");
  if (bom) raw = raw.slice(1);
  const crlf = raw.includes("\r\n");
  return { text: crlf ? raw.replace(/\r\n/g, "\n") : raw, bom, crlf };
}

/** The text to save: the edited lines with the original file's BOM and line endings put back. */
export function encodeConfig(text: string, original: Pick<EditableText, "bom" | "crlf">): string {
  const lf = text.replace(/\r\n/g, "\n");
  return (original.bom ? "\uFEFF" : "") + (original.crlf ? lf.replace(/\n/g, "\r\n") : lf);
}

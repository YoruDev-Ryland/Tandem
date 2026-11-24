import type { MoveStatus } from "../api/types";
import { formatSize } from "../format";

/** The server's failure messages usually end with this already. */
const NOTHING = /Nothing was changed\.\s*$/;

export interface MoveLine {
  tone: "wait" | "fault" | "ink";
  text: string;
}

/** The Files block's one line about the last or running move. */
export function moveLine(s: MoveStatus): MoveLine {
  switch (s.phase) {
    case "Copying":
      return { tone: "wait", text: `Copying ${s.filesDone} of ${s.filesTotal} files, ${formatSize(s.bytesDone)} of ${formatSize(s.bytesTotal)}` };
    case "Holding":
      return { tone: "wait", text: "Holding uploads, finishing…" };
    case "Done":
      return {
        tone: "ink",
        text: s.oldLocation ? `Files are now in ${s.to}. The old copy (${formatSize(s.oldBytes)}) is still in ${s.oldLocation}.` : `Files are now in ${s.to}.`,
      };
    case "Failed":
      return { tone: "fault", text: NOTHING.test(s.error ?? "") ? s.error! : `${s.error ?? "The move failed."} Nothing was changed.` };
  }
}

const ITEM_LABEL: Record<string, string> = {
  absolute: "Absolute path",
  outside: "Outside the current folder",
  writable: "Writable",
  space: "Free space",
  mount: "A real mount",
  empty: "Empty",
};

/** A check item's id as the dialog words it; an id this page doesn't know shows as it is. */
export function checkLabel(id: string): string {
  return ITEM_LABEL[id] ?? id;
}

import type { MoveStatus } from "../api/types";
import { formatSize } from "../format";

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
      return { tone: "fault", text: `${s.error ?? "The move failed."} Nothing was changed.` };
  }
}

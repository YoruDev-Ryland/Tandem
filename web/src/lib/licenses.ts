/**
 * The open-source licences shipped with the web UI, as static files under web/public/licenses/ (served at
 * /licenses/). One entry per bundled font; add a line here when a new licence file is added.
 */
export const LICENSES: readonly { name: string; licence: string; file: string }[] = [
  { name: "Barlow", licence: "SIL Open Font License 1.1", file: "/licenses/Barlow-OFL.txt" },
  { name: "Barlow Semi Condensed", licence: "SIL Open Font License 1.1", file: "/licenses/BarlowSemiCondensed-OFL.txt" },
  { name: "JetBrains Mono", licence: "SIL Open Font License 1.1", file: "/licenses/JetBrainsMono-OFL.txt" },
];

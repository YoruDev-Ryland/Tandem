// Dev and test only: one real server's storage (one account, 116 MB on a 195 GB disk), for the UI kit and the tests.
import type { StorageGame, StorageUsage } from "../lib/api/types";

const MB = 1024 * 1024;
const GB = 1024 * MB;
const mb = (n: number) => Math.round(n * MB);

const game = (emulatorId: string, gameKey: string, displayName: string | null, size: number, over: Partial<StorageGame> = {}): StorageGame => ({
  emulatorId, gameKey, displayName, bytes: mb(size), currentBytes: mb(size), olderBytes: 0, versions: 1, kinds: ["Save"], ...over,
});

export const storageGames: StorageGame[] = [
  game("melonds", "platinum", "Pokémon Platinum", 20.3, { versions: 2, kinds: ["Save", "State"] }),
  game("eden", "0100F2C0115B6000", "The Legend of Zelda: Tears of the Kingdom", 17.8),
  game("eden", "0100A7A013C42000", "Trails in the Sky 1st Chapter", 17.1, { currentBytes: mb(4.3), olderBytes: mb(12.8), versions: 4 }),
  game("eden", "01008D300C50C000", "13 Sentinels: Aegis Rim", 7.3),
  game("ppsspp", "ULUS10530", "Crimson Gem Saga", 5.5, { currentBytes: mb(5.1), olderBytes: mb(0.4), versions: 3 }),
  game("eden", "010074F013262000", "Xenoblade Chronicles 3", 2.1),
  game("eden", "0100000011D90000", "Pokémon Brilliant Diamond", 1.8),
  game("eden", "0100ABF008968000", "Pokémon Sword", 1.9, { currentBytes: mb(0.9), olderBytes: mb(1.0), versions: 3 }),
  game("eden", "010093801237C000", "Metroid Dread", 1.2),
  game("eden", "010055D009F78000", "Fire Emblem: Three Houses", 0.9),
  game("eden", "0100E5600D7B2000", "Octopath Traveler", 0.6),
  game("azahar", "0004000000175E00", "Pokémon Moon", 0.8),
  game("azahar", "00040000000A0500", "Fire Emblem Awakening", 0.5),
  game("dolphin", "GZLE01", "The Legend of Zelda: The Wind Waker", 0.4),
  game("dolphin", "GM8E01", "Metroid Prime", 0.2),
  game("retroarch", "retroarch/Pokemon - Emerald Version (USA)", "Pokémon Emerald", 0.25, { currentBytes: mb(0.15), olderBytes: mb(0.1), versions: 2 }),
  game("retroarch", "retroarch/Pokemon - Crystal Version (USA)", null, 0.15),
];

const row = (emulatorId: string, size: number) => ({ emulatorId, gameKey: null, displayName: null, bytes: mb(size) });

export const storageUsage: StorageUsage = {
  totalBytes: mb(78.7),
  byEmulator: [row("eden", 51.4), row("melonds", 20.5), row("ppsspp", 5.6), row("azahar", 1.4), row("dolphin", 0.6), row("retroarch", 0.5)],
  topGames: [],
  games: storageGames,
  byKind: { saves: mb(52.1), states: mb(25.0), configs: mb(1.3), vault: 0, lostAndFound: mb(0.6) },
  olderBytes: mb(14.3),
  server: {
    filesBytes: mb(80.2),
    diskTotalBytes: 195 * GB,
    diskFreeBytes: 120 * GB,
    minFreeBytes: GB,
    artBytes: mb(32),
    databaseBytes: mb(5.3),
    filesLocation: "/data/files",
    defaultLocation: true,
    move: null,
    lowDisk: null,
  },
};

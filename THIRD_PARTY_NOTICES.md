# Third-party notices

Tandem uses the following. Each project keeps its own licence.

## Fonts (SIL Open Font License 1.1)

Shipped with the web UI and the Android app. The licence texts are in `web/public/licenses/` (served at `/licenses/`)
and `android/app/src/main/assets/licenses/`.

- Barlow
- Barlow Semi Condensed
- JetBrains Mono

## Title name lists (fetched at runtime)

Not part of this repository or the image. The server downloads them into your own data directory so saves keyed by title
id show game names. Set `TITLE_NAMES=false` to turn all downloading off; games then show their ids.

- [titledb](https://github.com/blawar/titledb) (Switch), MIT licence.
- [3dsdb](https://github.com/hax0kartik/3dsdb) (3DS). The repository states no licence. It is fetched at runtime only and
  never redistributed by Tandem.
- GameTDB (`wiitdb.txt`, GameCube and Wii titles): fetched at runtime and cached only in your own data directory, not redistributed. See https://www.gametdb.com for its terms. Turn it off with `TITLE_NAMES=false`.

## Cover art (fetched at runtime)

Not part of this repository or the image. Cover art is copyrighted by its publishers. The server fetches a cover only
when a client shows that game, from one of the sources below, and caches it only in your own data directory
(`art/`, at most 500 MB). Nothing is bundled or redistributed by Tandem. Set `ART=false` to turn all art fetching off;
every game then shows the no-art card.

- [libretro-thumbnails](https://github.com/libretro-thumbnails) box art (`Named_Boxarts`), by game name. The server also
  reads each system's file listing through the GitHub API, once a week at most (once an hour while the API is failing).
- GameTDB covers (https://art.gametdb.com), by GameCube/Wii game id. See https://www.gametdb.com for its terms.

## Server (.NET 10)

- ASP.NET Core and Entity Framework Core (including `Microsoft.EntityFrameworkCore.Sqlite`, `Microsoft.AspNetCore.OpenApi`): MIT
- Npgsql and `Npgsql.EntityFrameworkCore.PostgreSQL`: PostgreSQL licence (permissive; see each package)
- AWSSDK.S3: Apache-2.0
- SkiaSharp and `SkiaSharp.NativeAssets.Linux.NoDependencies` (case spines): MIT; the bundled Skia library: BSD-3-Clause

## Web UI (npm)

- Svelte: MIT
- `qrcode`: MIT
- `@fontsource/barlow`, `@fontsource/barlow-semi-condensed`, `@fontsource/jetbrains-mono`: OFL-1.1 (font files), MIT (package)

Build and test tools (Vite, Vitest, TypeScript, svelte-check, jsdom, Testing Library, Playwright core) are not shipped.

## Android app

- Shizuku API and provider (`dev.rikka.shizuku`): Apache-2.0
- OkHttp and okhttp-sse: Apache-2.0
- kotlinx.serialization and kotlinx.coroutines: Apache-2.0
- AndroidX Compose (ui, foundation, material3), Activity, Lifecycle: Apache-2.0

# Tandem

A self-hosted server that syncs emulator saves and states between your Android handhelds, and backs up and syncs
emulator configs. One container, one data volume. Clients (the Android app and the web UI) talk to it over HTTP; the API
is described by `GET /v1/openapi.json` (send the `Tandem-Protocol: 1` header with that request, as with every `/v1/` route
except `/v1/server-info`).

## Run

```sh
docker run -d --name tandem -p 8080:8080 \
  -e PUBLIC_URL=https://tandem.example.com \
  -v tandem-data:/data \
  ghcr.io/<owner>/tandem:latest
```

or with Docker Compose (see `docker-compose.yml`):

```sh
docker compose up -d
```

`<owner>` is the owner of the GitHub repo you install from: the image is `ghcr.io/<owner>/tandem`, published by that repo's release workflow. To build it yourself instead, run `docker build -t tandem .`
(or uncomment `build: .` in `docker-compose.yml`).

Run **one replica only**. Locks that keep uploads consistent live inside the server process, so two servers on the same
data (even with Postgres) can corrupt each other's decisions.

**Security: the first account to register becomes the admin.** Until it exists, anyone who can reach the server can
register it, so create your account right after the first start (open the web UI, or run
`docker exec tandem create-user you@example.com --admin`) before exposing the server to the internet.

**Only give accounts to people you trust.** Notification targets (ntfy, Discord, webhooks) can point anywhere the server
can reach, including internal addresses on your network.

## Settings

Environment variables. The ASP.NET form (`Tandem__DataDirectory`, `Tandem__PublicUrl`, ...) is also accepted and takes
precedence over the plain names.

| Variable | Default | Meaning |
|---|---|---|
| `PUBLIC_URL` | none | The address devices and browsers use to reach the server (pairing links and QR codes). |
| `ALLOW_REGISTRATION` | `false` | Let anyone register. The first account can always register and becomes admin. Admins can change this at runtime. |
| `DATA_DIR` | `/data` in Docker, `data` otherwise | Database (SQLite), blobs, temp files. |
| `PUID` / `PGID` | `1654` | The user and group the server runs as; the entrypoint chowns `/data` to them. |
| `DATABASE_PROVIDER` | `sqlite` | `sqlite` or `postgres`. |
| `DATABASE_CONNECTION` | none | Npgsql connection string for `postgres`; for `sqlite` an optional database file path. |
| `TRUST_FORWARDED_HEADERS` | `false` | Trust `X-Forwarded-For`/`-Proto`/`-Host` from a reverse proxy (set it when behind one): rate limits are per client, the session cookie is `Secure` over https, and pairing QR codes use the public host. Every proxy address is trusted, so only turn it on when the server is reachable through the proxy alone. |
| `KNOWN_PROXIES` | none | Comma-separated proxy addresses or networks whose forwarded headers are trusted. Prefer this to `TRUST_FORWARDED_HEADERS`, which trusts every address. |
| `ADMIN_EMAIL` | none | When set, the first account ever created must use this email, so nobody else can claim the admin account of a freshly started server. |
| `SILENT_DEVICE_HOURS` | `72` | A device not seen for this long is flagged as silent in the web UI and in notifications. |
| `MIN_FREE_BYTES` | 1 GiB | Free space to keep in the data directory. Below it, uploads fail with 507 `storage_full`, `/healthz` reports `degraded` and admins are notified. |
| `ALLOW_ROOT` | `false` | The entrypoint refuses `PUID=0` or `PGID=0` unless this is `true`. |
| `SERVER_NAME` | `Tandem` | Shown to clients (max 60 characters). |
| `TITLE_NAMES` | `true` | Downloads the public Switch (titledb) and 3DS (3dsdb) title lists weekly so saves keyed by title id show game names. `false` keeps the server offline; those games then show their ids. |
| `ART` | `true` | Fetches cover art for the game a client is showing, from libretro-thumbnails and GameTDB, and caches it in the data directory (up to 500 MB). `false` sends no art requests; games show the no-art card. |
| `WEB_ROOT` | `wwwroot` beside the app | The built web UI. |

SQLite runs in WAL mode, so a running backup does not block uploads.

### Limits

Set these with the ASP.NET form (`Tandem__<Name>`).

| Setting | Default | Meaning |
|---|---|---|
| `Tandem__MaxBlobBytes` | 8 GiB | The largest single file a device can upload. |
| `Tandem__MaxUploadBytes` | 8 GiB | The total size of one web upload. |
| `Tandem__AuthRequestsPerMinute` | `10` | Login and pairing attempts per client per minute. |
| `Tandem__PruneIntervalMinutes` | `60` | How often old versions are pruned. Retention is by count: 30 save, 30 config and 5 vault versions per item. |
| `Tandem__TitleNamesRefreshDays` | `7` | How often the title lists are refreshed. |

States are large (5 to 100 MB each), so size the volume for many versions of them.

## Choosing how devices reach the server

Devices send a key with every request, and the web UI a password. Over plain http on an untrusted network anyone can read
them, and a device key is full access to the account (every save and config secret). Pick one of these.

1. **Tailscale.** Put the server and every device on your tailnet. Plain `http://100.x.y.z:8080` is encrypted end to end
   by WireGuard, so it is safe enough. For a normal https address instead, run `tailscale serve --bg 8080` (or
   `tailscale cert` and a proxy) and use the `https://<machine>.<tailnet>.ts.net` name as `PUBLIC_URL`.
2. **A domain behind Caddy or Traefik.** Use Let's Encrypt. For a LAN-only server, use a DNS challenge so no port needs to
   be open to the internet. Or let Caddy issue its own certificates with `tls internal`; the Android app trusts CAs you
   install on the device (Settings, Security, install a CA certificate). Set `PUBLIC_URL` to the https address and publish
   the container as `127.0.0.1:8080:8080` so the plain port is reachable through the proxy alone.
3. **LAN, plain http.** It works and it is the easiest, but anyone on that network can sniff the device key and the web
   password. Use it only on a network you fully control, never on shared or public Wi-Fi.

## Android app

1. Download `tandem-<version>.apk` from the Releases page of the repo you installed from and install it. Allow installs
   from your browser or file manager when asked.
2. Install [Shizuku](https://shizuku.rikka.app) and start it. On a rooted device it can start at boot. Otherwise start it
   with wireless debugging, and **start it again after every reboot**: until you do, Tandem cannot reach emulator files and
   shows that Shizuku is off. Grant Tandem access when Shizuku asks.
3. In the web UI open Devices, Pair a new device, then scan the QR code or type the code into the app.
4. Allow notifications, and exempt Tandem from battery optimisation (Settings, Apps, Tandem, Battery, Unrestricted).
   Otherwise Android may stop it in the background and saves will not sync until you open it.
5. Turn on auto-apply in the app so incoming saves are written without asking, once you trust the setup.

Tandem never writes into an emulator's files while that emulator is running, and it backs up what it overwrites.

## Update

Back up first (see Backup below), then:

```sh
docker compose pull && docker compose up -d
```

Database migrations run at startup, and the server snapshots the SQLite database before it applies any. Downgrading is not
supported: after a migration an older server may not read the data. To go back, restore the pre-upgrade backup or the
snapshot in `/data`. Keep to one replica.

## Develop the web UI

The web UI is a Svelte app in `web/`. Run a server (on port 5100, for example `docker run -p 5100:8080 ...`) and start the dev server, which
proxies `/v1` and `/healthz` to it:

```sh
npm --prefix web install
npm --prefix web run dev          # http://localhost:5173, proxies to http://localhost:5100
npm --prefix web test && npm --prefix web run check && npm --prefix web run build
```

`web/scripts/screens.mjs` captures every page at 1440x900 and 390x844 in both themes into `design/screens/web/`:

```sh
TANDEM_EMAIL=you@example.com TANDEM_PASSWORD=... node web/scripts/screens.mjs http://localhost:5100
```

It uses `playwright-core` with a local Chromium (set `CHROMIUM` to its path if it is not the default Playwright cache).

## Admin commands

The image has these commands on its path; run them with `docker exec`:

```sh
docker exec tandem list-users
docker exec tandem create-user me@example.com --admin   # prints the generated password
docker exec tandem reset-password me@example.com        # prints the new password
docker exec tandem revoke-device <deviceId>
```

Exit code 0 means success; 1 means a failure, with the reason on stderr. Without Docker:
`dotnet Tandem.Server.dll admin <command> ...` (the `admin` word is optional). The commands run as `PUID:PGID`, never as
root, so files they create under `/data` have the right owner.

## HTTPS and reverse proxy

The server speaks plain HTTP on 8080 (see "Choosing how devices reach the server"). Put a reverse proxy (Caddy, nginx, Traefik) in front for HTTPS, set
`PUBLIC_URL` to the public `https://` address and `KNOWN_PROXIES` to the proxy's address (or `TRUST_FORWARDED_HEADERS=true` if it can be the only way in). Uploads can be large: raise the proxy's
body-size and timeout limits (nginx: `client_max_body_size 0;`). Live updates use Server-Sent Events
(`/v1/events`): turn off response buffering for that path (nginx: `proxy_buffering off;`). Caddy:

```
tandem.example.com {
    reverse_proxy tandem:8080
}
```

## Backup

In the web UI (admin), set a target and a schedule: a mounted folder (a NAS) or an S3 bucket, daily at an hour (UTC), and
how many backups to keep. A backup holds the database and all blobs. "Run now" starts one immediately. You can encrypt
backups with a passphrase.

- **Secrets are stored on the server in plain form** and are part of a backup: emulator config secrets synced with the
  configs (RetroArch cheevos and netplay credentials, the Eden web token) and notification tokens and Discord webhook
  URLs. Only an **encrypted** backup protects them; turn encryption on if the target is not private.
- **Losing the passphrase means the backup cannot be restored.** There is no recovery. Store it somewhere other than the
  server.
- Backup target settings (secrets, schedule) are not restored; set the target up again on the restored server.

### Restore

Restore into an empty server (no accounts, no blobs) with **the server stopped**, as `PUID:PGID` (never as root, or the
restored files would belong to root), using a one-off container on the same volume. Pass the passphrase of an encrypted
backup through `TANDEM_BACKUP_PASSPHRASE` rather than on the command line (which shows up in process lists and shell
history):

```sh
docker stop tandem
docker run --rm -v tandem-data:/data --user 1654:1654 \
  -e TANDEM_BACKUP_PASSPHRASE \
  ghcr.io/<owner>/tandem:latest restore-backup /data/tandem-backup-20261120-030000.tar.gz.enc
docker start tandem
```

Put the backup file on the volume (or mount its folder with `-v /path/to/backups:/backups:ro` and use `/backups/<file>`).
`--user` must match your `PUID:PGID` (default `1654:1654`). The same with Compose, from the directory of your
`docker-compose.yml`:

```sh
docker compose stop tandem
docker compose run --rm --user 1654:1654 -e TANDEM_BACKUP_PASSPHRASE tandem restore-backup /data/<file>
docker compose start tandem
```

Restore refuses to run on a server that already has accounts or blobs.

## Licence

No licence has been chosen for Tandem yet, so by default all rights are reserved. Third-party credits are in
`THIRD_PARTY_NOTICES.md`.

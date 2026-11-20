# EmuSync

A self-hosted server that syncs emulator saves and states between your Android handhelds, and backs up and syncs
emulator configs. One container, one data volume. Clients (the Android app and the web UI) talk to it over HTTP; the API
is described by `GET /v1/openapi.json`.

## Run

```sh
docker run -d --name emusync -p 8080:8080 \
  -e PUBLIC_URL=https://emusync.example.com \
  -v emusync-data:/data \
  ghcr.io/<owner>/emusync:latest
```

or with Docker Compose (see `docker-compose.yml`):

```sh
docker compose up -d
```

`<owner>` stands for the account that publishes the image. To build it yourself instead, run `docker build -t emusync .`
(or uncomment `build: .` in `docker-compose.yml`).

**Security: the first account to register becomes the admin.** Until it exists, anyone who can reach the server can
register it, so create your account right after the first start (open the web UI, or run
`docker exec emusync create-user you@example.com --admin`) before exposing the server to the internet.

**Only give accounts to people you trust.** Notification targets (ntfy, Discord, webhooks) can point anywhere the server
can reach, including internal addresses on your network.

## Settings

Environment variables. The ASP.NET form (`EmuSync__DataDirectory`, `EmuSync__PublicUrl`, ...) is also accepted and takes
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
| `SERVER_NAME` | `EmuSync` | Shown to clients (max 60 characters). |
| `WEB_ROOT` | `wwwroot` beside the app | The built web UI. |

SQLite runs in WAL mode, so a running backup does not block uploads.

## Update

```sh
docker compose pull && docker compose up -d
```

Database migrations run at startup. Back up before a major upgrade.

## Admin commands

The image has these commands on its path; run them with `docker exec`:

```sh
docker exec emusync list-users
docker exec emusync create-user me@example.com --admin   # prints the generated password
docker exec emusync reset-password me@example.com        # prints the new password
docker exec emusync revoke-device <deviceId>
```

Exit code 0 means success; 1 means a failure, with the reason on stderr. Without Docker:
`dotnet EmuSync.Server.dll admin <command> ...` (the `admin` word is optional). The commands run as `PUID:PGID`, never as
root, so files they create under `/data` have the right owner.

## HTTPS and reverse proxy

The server speaks plain HTTP on 8080. Put a reverse proxy (Caddy, nginx, Traefik) in front for HTTPS, set
`PUBLIC_URL` to the public `https://` address and `TRUST_FORWARDED_HEADERS=true`. Uploads can be large: raise the proxy's
body-size and timeout limits (nginx: `client_max_body_size 0;`). Live updates use Server-Sent Events
(`/v1/events`): turn off response buffering for that path (nginx: `proxy_buffering off;`). Caddy:

```
emusync.example.com {
    reverse_proxy emusync:8080
}
```

## Backup

In the web UI (admin), set a target and a schedule: a mounted folder (a NAS) or an S3 bucket, daily at an hour (UTC), and
how many backups to keep. A backup holds the database and all blobs. "Run now" starts one immediately. You can encrypt
backups with a passphrase.

- **Notification tokens and Discord webhook URLs are stored on the server** and are part of a backup. Only an
  **encrypted** backup protects them; turn encryption on if the target is not private.
- **Losing the passphrase means the backup cannot be restored.** There is no recovery. Store it somewhere other than the
  server.
- Backup target settings (secrets, schedule) are not restored; set the target up again on the restored server.

### Restore

Restore into an empty server (no accounts, no blobs) with **the server stopped**, as `PUID:PGID` (never as root, or the
restored files would belong to root), using a one-off container on the same volume. Pass the passphrase of an encrypted
backup through `EMUSYNC_BACKUP_PASSPHRASE` rather than on the command line (which shows up in process lists and shell
history):

```sh
docker stop emusync
docker run --rm -v emusync-data:/data --user 1654:1654 \
  -e EMUSYNC_BACKUP_PASSPHRASE \
  ghcr.io/<owner>/emusync:latest restore-backup /data/emusync-backup-20261120-030000.tar.gz.enc
docker start emusync
```

Put the backup file on the volume (or mount its folder with `-v /path/to/backups:/backups:ro` and use `/backups/<file>`).
`--user` must match your `PUID:PGID` (default `1654:1654`). The same with Compose, from the directory of your
`docker-compose.yml`:

```sh
docker compose stop emusync
docker compose run --rm --user 1654:1654 -e EMUSYNC_BACKUP_PASSPHRASE emusync restore-backup /data/<file>
docker compose start emusync
```

Restore refuses to run on a server that already has accounts or blobs.

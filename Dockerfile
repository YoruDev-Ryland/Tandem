# syntax=docker/dockerfile:1

# BuildKit sets BUILDPLATFORM/TARGETARCH itself; these defaults only let the legacy `docker build` (no buildx) work.
ARG BUILDPLATFORM=linux/amd64

# Build natively on the build machine and cross-publish for the target architecture (multi-arch without emulation).
FROM --platform=$BUILDPLATFORM mcr.microsoft.com/dotnet/sdk:10.0 AS build
ARG TARGETARCH
WORKDIR /src
COPY Directory.Build.props Tandem.slnx ./
COPY src/Tandem.Protocol/Tandem.Protocol.csproj src/Tandem.Protocol/
COPY src/Tandem.Server/Tandem.Server.csproj src/Tandem.Server/
RUN dotnet restore src/Tandem.Server/Tandem.Server.csproj -a "${TARGETARCH:-amd64}"
COPY src/ src/
RUN dotnet publish src/Tandem.Server/Tandem.Server.csproj -c Release -a "${TARGETARCH:-amd64}" --no-restore -o /out

FROM --platform=$BUILDPLATFORM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS runtime
LABEL org.opencontainers.image.title="Tandem" \
      org.opencontainers.image.description="Self-hosted server that syncs emulator saves, states and configs between Android handhelds." \
      org.opencontainers.image.licenses="NOASSERTION"
WORKDIR /app
COPY --from=build /out/ ./
# The server serves WEB_ROOT, which defaults to wwwroot beside the app.
COPY --from=web /web/dist/ ./wwwroot/
COPY docker/entrypoint.sh docker/admin-command.sh /app/docker/
# curl for the healthcheck: the base image has none and the server has no healthcheck subcommand yet; setpriv, used to drop privileges, ships with util-linux.
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && chmod +x /app/docker/*.sh \
    && for c in list-users create-user reset-password revoke-device restore-backup; do \
         ln -s /app/docker/admin-command.sh "/usr/local/bin/$c"; \
       done \
    && mkdir -p /data

ENV ASPNETCORE_URLS=http://+:8080 \
    DATA_DIR=/data \
    PUID=1654 \
    PGID=1654 \
    DOTNET_gcServer=0
EXPOSE 8080
VOLUME /data

# /healthz answers "ok" without the protocol header.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://localhost:8080/healthz || exit 1

# Starts as root only to chown /data and drop to PUID:PGID (see docker/entrypoint.sh).
ENTRYPOINT ["/app/docker/entrypoint.sh"]

#!/usr/bin/env bash
# Creates the release signing key OUTSIDE the repository and writes android/keystore.properties (gitignored),
# which the release build reads. Back the keystore and its passwords up: Android refuses an update signed with
# another key, so losing it means every user has to uninstall (and lose the app's sync state) to update.
#
# Usage: scripts/make-release-key.sh [keystore path]   (default: ~/.config/tandem/release.jks)
# Needs keytool (any JDK). Asks for the passwords; nothing is passed on the command line.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
store="${1:-$HOME/.config/tandem/release.jks}"
alias="tandem"
props="$here/keystore.properties"

store="$(realpath -m "$store")"
case "$store" in
  "$(realpath -m "$here/..")"/*) echo "Refusing to put the keystore inside the repository: $store" >&2; exit 1 ;;
esac
if [ -e "$store" ]; then echo "$store already exists; not overwriting it." >&2; exit 1; fi
if [ -e "$props" ]; then echo "$props already exists; remove it first to point it at a new key." >&2; exit 1; fi

read -rsp "Keystore password (6+ characters): " storepass; echo
read -rsp "Same again: " again; echo
[ "$storepass" = "$again" ] || { echo "The passwords differ." >&2; exit 1; }
[ "${#storepass}" -ge 6 ] || { echo "Use at least 6 characters." >&2; exit 1; }

mkdir -p "$(dirname "$store")"
chmod 700 "$(dirname "$store")"
# PKCS12 keeps one password for the store and the key.
TANDEM_STOREPASS="$storepass" keytool -genkeypair -v -storetype PKCS12 -keystore "$store" -alias "$alias" \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Tandem" \
  -storepass:env TANDEM_STOREPASS -keypass:env TANDEM_STOREPASS
chmod 600 "$store"

umask 077
{
  echo "storeFile=$store"
  echo "storePassword=$storepass"
  echo "keyAlias=$alias"
  echo "keyPassword=$storepass"
} > "$props"

echo "Keystore: $store"
echo "Wrote $props (gitignored). Back up the keystore and its password somewhere safe."

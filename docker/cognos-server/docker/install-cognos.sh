#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

# cognos-install: installs IBM Cognos Analytics from a user-supplied repository zip.
#
# Runs as `cognos-server install [SOURCE]`, and automatically from the entrypoint
# when there's no Cognos install yet but a zip is available.
#
#   cognos-install [SOURCE] [--target DIR]    install (no-op if DIR already has this zip)
#   cognos-install [SOURCE] --print-key       print the zip's install key and exit
#
# SOURCE (first match wins when omitted):
#   -                         a zip piped in on stdin
#   https://... http://...    a URL, e.g. a pre-signed S3/R2/GCS link (file:// works too)
#   /absolute/path            a zip file
#   name                      a zip file in $COGNOS_ZIP_DIR (default /ibm)
#   (omitted)                 $COGNOS_ZIP_URL, else $COGNOS_ZIP, else the newest
#                             casrv-*-linuxi38664h.zip in $COGNOS_ZIP_DIR
#
# The zip is IBM's Cognos Analytics repository, casrv-<ver>-linuxi38664h.zip (the
# file IBM's installer, ca_instl, reads). Set COGNOS_ZIP_SHA256 to verify it first.
#
# Instead of running IBM's installer, the manifest in the zip is followed and each
# part is unpacked (see unpack-cognos.py), then IBM's own post-install steps run.
# That's what the installer does, without its separate Java or a GUI.
set -euo pipefail

SRC_DIR=${COGNOS_ZIP_DIR:-/ibm}
INSTALLABLES=${COGNOS_INSTALLABLES:-CASRVR_DataTier CASRVR_AppTier}
LIB=/usr/local/lib/cognos-server

log() { echo "[cognos-install] $*" >&2; }
die() { echo "[cognos-install] ERROR: $*" >&2; exit 2; }

# ---- arguments --------------------------------------------------------------
SOURCE=; TARGET=${COGNOS_HOME:-/opt/ibm/cognos/analytics}; PRINT_KEY=F
while [ $# -gt 0 ]; do
  case "$1" in
    --target) TARGET=$2; shift 2 ;;
    --print-key) PRINT_KEY=T; shift ;;
    -h|--help) sed -n '2,23p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) [ -z "$SOURCE" ] || die "unexpected argument: $1"; SOURCE=$1; shift ;;
  esac
done

newest_in_dir() {
  shopt -s nullglob
  local files=("$SRC_DIR"/casrv-*-linuxi38664h.zip)
  [ ${#files[@]} -gt 0 ] && printf '%s\n' "${files[@]}" | sort -V | tail -1
  return 0
}

if [ -z "$SOURCE" ]; then
  if [ -n "${COGNOS_ZIP_URL:-}" ]; then SOURCE=$COGNOS_ZIP_URL
  elif [ -n "${COGNOS_ZIP:-}" ]; then SOURCE=$COGNOS_ZIP
  else SOURCE=$(newest_in_dir)
  fi
fi
[ -n "$SOURCE" ] || die "no Cognos repository zip found (casrv-*-linuxi38664h.zip). Mount the folder holding it at $SRC_DIR (-v /path/to/downloads:$SRC_DIR:ro), set COGNOS_ZIP_URL, or pipe it in: ... | docker run -i ... install -"

# Classify the source, and show URLs without query strings or credentials:
# pre-signed URLs carry their credentials in the query string.
case "$SOURCE" in
  -) KIND="stdin"; SHOWN="stdin" ;;
  http://*|https://*|file://*)
    KIND="url"
    SHOWN=$(printf '%s' "$SOURCE" | sed -E 's#\?.*$##; s#^([a-z]+://)[^/@]*@#\1#') ;;
  /*) KIND="file"; SHOWN=$SOURCE ;;
  *) KIND="file"; SOURCE="$SRC_DIR/$SOURCE"; SHOWN=$SOURCE ;;
esac
if [ "$KIND" = file ]; then [ -f "$SOURCE" ] || die "zip not found: $SOURCE"; fi

# Install key: identifies a zip cheaply, without reading all 7 GB of it.
#   file: name, size and modification time
#   url:  COGNOS_ZIP_SHA256 if set, else the URL without its query string
#   stdin: no key, so it's never recognized as already installed
case "$KIND" in
  file) KEY=$(printf 'file:%s:%s' "$(basename "$SOURCE")" "$(stat -c '%s:%Y' "$SOURCE")" | sha256sum | cut -c1-16) ;;
  url)  KEY=$(printf 'url:%s' "${COGNOS_ZIP_SHA256:-$SHOWN}" | sha256sum | cut -c1-16) ;;
  stdin) KEY= ;;
esac
if [ "$PRINT_KEY" = T ]; then
  [ -n "$KEY" ] || die "--print-key needs a file or URL source"
  echo "$KEY"; exit 0
fi

# ---- install ------------------------------------------------------------------
STATE="$TARGET/.cognos-server"   # this image's own bookkeeping inside the install
mkdir -p "$TARGET" 2>/dev/null || die "can't create $TARGET"
[ -w "$TARGET" ] || die "$TARGET is read-only; Cognos needs a writable install"
# Serialize installs when several containers share one install volume.
exec 9>"$TARGET/.install.lock"
flock 9

# An install is complete once install-key is written and the installing marker is
# gone (the marker is removed last). A fresh install only starts in an empty
# directory, so wiping an unfinished one can only remove what it unpacked.
if [ -f "$STATE/install-key" ] && [ ! -f "$STATE/installing" ]; then
  if [ -n "$KEY" ] && [ "$(cat "$STATE/install-key")" = "$KEY" ]; then
    log "Cognos already installed in $TARGET from this zip ($SHOWN)"
    exit 0
  fi
  die "$TARGET already has Cognos installed from another zip; refusing to overwrite it. Use an empty volume (or remove this one) to install another version."
fi
if [ -f "$STATE/installing" ]; then
  log "A previous install into $TARGET didn't finish; removing it and starting over"
  find "$TARGET" -mindepth 1 -maxdepth 1 ! -name .install.lock -exec rm -rf {} +
elif [ -n "$(find "$TARGET" -mindepth 1 -maxdepth 1 ! -name .install.lock ! -name lost+found -print -quit)" ]; then
  die "$TARGET isn't empty; Cognos installs only into an empty directory or volume"
fi
mkdir -p "$STATE"
: > "$STATE/installing"

# Fetch remote and piped zips to a file first: a zip can't be read until it has
# all arrived. It goes next to the install (same volume), and is removed afterwards.
case "$KIND" in
  file) zip=$SOURCE ;;
  url)
    log "Downloading $SHOWN (about 7 GB)"
    zip="$STATE/download.zip"
    curl -fsSL --retry 3 --retry-delay 2 -o "$zip" "$SOURCE" || die "download failed: $SHOWN"
    ;;
  stdin)
    [ -t 0 ] && die "install - reads a zip from stdin; pipe one in and use docker run -i"
    log "Reading the zip from stdin"
    zip="$STATE/download.zip"
    cat > "$zip"
    KEY=$(printf 'stdin:%s' "$(stat -c '%s' "$zip")" | sha256sum | cut -c1-16)
    ;;
esac
if [ -n "${COGNOS_ZIP_SHA256:-}" ]; then
  log "Verifying SHA-256"
  echo "$COGNOS_ZIP_SHA256  $zip" | sha256sum -c --quiet - || die "SHA-256 mismatch for $SHOWN"
fi

log "Installing $INSTALLABLES from $SHOWN into $TARGET"
start=$(date +%s)
# shellcheck disable=SC2086  # INSTALLABLES is a space-separated list
python3 "$LIB/unpack-cognos.py" "$zip" "$TARGET" $INSTALLABLES || die "unpacking failed"

# IBM's post-install actions (the manifest's postInstallForNew), with the variables
# IBM's installer would set for them.
log "Running IBM's post-install steps"
(
  cd "$TARGET"
  export USER_INSTALL_DIR="$TARGET"
  export INSTALL_SCRIPTS_LOG="$TARGET/uninstall/logs/install_scripts.log"
  MANIFEST=$(cat "$STATE/manifest-name"); export MANIFEST
  SELECTED_INSTALLABLES=$(echo "$INSTALLABLES" | tr -s ' ' ','); export SELECTED_INSTALLABLES
  export INSTALLER_LOCALE=en
  EXTRACTOR_EXECUTABLE=$(basename "$SHOWN"); export EXTRACTOR_EXECUTABLE
  export USER_SHORTCUTS="$TARGET"
  sh configuration/action_scripts/unixPostInstall.sh
) || { tail -20 "$TARGET/uninstall/logs/install_scripts.log" >&2 || true; die "IBM's post-install steps failed (log above)"; }

# The template the entrypoint renders cogstartup.xml from on every start.
python3 "$LIB/configure-cognos.py" template "$TARGET/configuration/cogstartup.xml" "$STATE/cogstartup.xml" >&2 \
  || die "couldn't make the cogstartup.xml template"
# Cognos ships no PostgreSQL JDBC driver; this image carries one.
cp "$LIB"/drivers/*.jar "$TARGET/drivers/"

[ "$KIND" = file ] || rm -f "$zip"
[ -n "$KEY" ] && echo "$KEY" > "$STATE/install-key"
rm -f "$STATE/installing"
log "Installed in $(( $(date +%s) - start ))s ($(du -sh "$TARGET" | cut -f1))"

#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

# tm1-engine-install: installs TM1 v12 from a user-supplied IBM package.
#
# Runs as `tm1-engine install [SOURCE]`, and automatically from the entrypoint when
# no TM1 install is mounted but a package is available.
#
#   tm1-engine-install [SOURCE] [--target DIR]   install (no-op if DIR already has this package)
#   tm1-engine-install [SOURCE] --print-key      print the package's cache key and exit
#
# SOURCE (first match wins when omitted):
#   -                         a package piped in on stdin
#   https://... http://...    a URL, e.g. a pre-signed S3/R2/GCS link (file:// works too)
#   /absolute/path            a package file
#   name                      a package file in $TM1_ENGINE_BUNDLE_DIR (default /ibm)
#   (omitted)                 $TM1_ENGINE_BUNDLE_URL, else $TM1_ENGINE_BUNDLE, else the newest
#                             tm1-v*-standalone-linux.tgz in $TM1_ENGINE_BUNDLE_DIR
#
# A package is IBM's tm1-v<ver>-standalone-linux.tgz, or the installer inside it
# (tm1-v<ver>-standalone-installer-linux.run). The type is detected from the file
# contents, so URLs don't need a file extension. Set TM1_ENGINE_BUNDLE_SHA256 to verify
# the package before installing.
#
# IBM's installer isn't run: it asks questions, registers a systemd service and downloads
# MongoDB. The installer is a self-extracting archive, so its files are unpacked and
# laid out as the installer would: bin/ and copyright/, with its deployment/ folder
# (the configuration a new deployment starts from) kept as deployment-template/.
set -euo pipefail

SRC_DIR=${TM1_ENGINE_BUNDLE_DIR:-/ibm}

log() { echo "[tm1-engine-install] $*" >&2; }
die() { echo "[tm1-engine-install] ERROR: $*" >&2; exit 2; }

# ---- arguments --------------------------------------------------------------
SOURCE=; TARGET=${TM1_ENGINE_HOME:-/opt/ibm/tm1-engine}; PRINT_KEY=F
while [ $# -gt 0 ]; do
  case "$1" in
    --target) TARGET=$2; shift 2 ;;
    --print-key) PRINT_KEY=T; shift ;;
    -h|--help) sed -n '4,28p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) [ -z "$SOURCE" ] || die "unexpected argument: $1"; SOURCE=$1; shift ;;
  esac
done

newest_in_dir() {
  shopt -s nullglob
  local files=("$SRC_DIR"/tm1-v*-standalone-linux.tgz)
  [ ${#files[@]} -gt 0 ] && printf '%s\n' "${files[@]}" | sort -V | tail -1
  return 0
}

if [ -z "$SOURCE" ]; then
  if [ -n "${TM1_ENGINE_BUNDLE_URL:-}" ]; then SOURCE=$TM1_ENGINE_BUNDLE_URL
  elif [ -n "${TM1_ENGINE_BUNDLE:-}" ]; then SOURCE=$TM1_ENGINE_BUNDLE
  else SOURCE=$(newest_in_dir)
  fi
fi
[ -n "$SOURCE" ] || die "no package found (tm1-v*-standalone-linux.tgz). Mount your IBM downloads at $SRC_DIR (-v /path/to/downloads:$SRC_DIR:ro), set TM1_ENGINE_BUNDLE_URL, or pipe one in: ... | docker run -i ... install -"

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
if [ "$KIND" = file ]; then [ -f "$SOURCE" ] || die "package not found: $SOURCE"; fi

# Cache key: identifies a package cheaply, without reading all of it.
#   file: name, size and modification time
#   url:  TM1_ENGINE_BUNDLE_SHA256 if set, else the URL without its query string
#   stdin: no key until it has been read (always installs)
case "$KIND" in
  file) KEY=$(printf 'file:%s:%s' "$(basename "$SOURCE")" "$(stat -c '%s:%Y' "$SOURCE")" | sha256sum | cut -c1-16) ;;
  url)  KEY=$(printf 'url:%s' "${TM1_ENGINE_BUNDLE_SHA256:-$SHOWN}" | sha256sum | cut -c1-16) ;;
  stdin) KEY= ;;
esac
if [ "$PRINT_KEY" = T ]; then
  [ -n "$KEY" ] || die "--print-key needs a file or URL source"
  echo "$KEY"; exit 0
fi

# ---- install ------------------------------------------------------------------
MARKER="$TARGET/.tm1-engine-install"
mkdir -p "$TARGET" 2>/dev/null || die "can't create $TARGET"
[ -w "$TARGET" ] || die "$TARGET is read-only; mount it read-write to install into it"
# Serialize installs when several containers share one install volume.
exec 9>"$TARGET/.install.lock"
flock 9

# An install is whole once its marker is written and INSTALLING, made first and removed
# last, is gone. One that was cut short is done again.
INSTALLING="$TARGET/.tm1-engine-installing"
if [ ! -e "$INSTALLING" ] && [ -n "$KEY" ] && [ -f "$MARKER" ] && [ "$(cat "$MARKER")" = "$KEY" ] && [ -x "$TARGET/bin/tm1sd.exe" ]; then
  log "TM1 already installed in $TARGET from this package ($SHOWN)"
  exit 0
fi
# Installing empties the target, so it has to be empty or an install of this script's
if [ ! -f "$MARKER" ] && [ ! -e "$INSTALLING" ] \
    && [ -n "$(find "$TARGET" -mindepth 1 -maxdepth 1 ! -name .install.lock ! -name lost+found -print -quit)" ]; then
  die "$TARGET isn't empty, and what's in it wasn't installed by tm1-engine-install; refusing to replace it. Install into an empty folder or volume."
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Fetch remote and piped packages to a local file first.
case "$KIND" in
  file) bundle=$SOURCE ;;
  url)
    log "Downloading $SHOWN"
    bundle="$work/bundle"
    curl -fsSL --retry 3 --retry-delay 2 -o "$bundle" "$SOURCE" || die "download failed: $SHOWN"
    ;;
  stdin)
    [ -t 0 ] && die "install - reads a package from stdin; pipe one in and use docker run -i"
    log "Reading the package from stdin"
    bundle="$work/bundle"
    cat > "$bundle"
    [ -s "$bundle" ] || die "nothing was received on stdin"
    KEY=$(sha256sum "$bundle" | cut -c1-16)
    ;;
esac

if [ -n "${TM1_ENGINE_BUNDLE_SHA256:-}" ]; then
  actual=$(sha256sum "$bundle" | cut -d' ' -f1)
  [ "$actual" = "${TM1_ENGINE_BUNDLE_SHA256,,}" ] || die "checksum mismatch for $SHOWN: expected $TM1_ENGINE_BUNDLE_SHA256, got $actual"
  log "Checksum verified"
fi

log "Installing TM1 from $SHOWN into $TARGET (one-time)"

# Detect the type from the contents: gzip (1f8b) = IBM's package, "#!" (2321) = the
# installer inside it.
case "$(head -c2 "$bundle" | od -An -tx1 | tr -d ' \n')" in
  1f8b)
    python3 - "$bundle" "$work" <<'PY' || die "no *-installer-linux.run inside the package"
import os, sys, tarfile
with tarfile.open(sys.argv[1], "r:gz") as tar:
    for member in tar:
        if member.isfile() and member.name.endswith("-installer-linux.run"):
            member.name = os.path.basename(member.name)
            tar.extract(member, sys.argv[2], filter="tar")
            break
    else:
        sys.exit(1)
PY
    run=$(find "$work" -maxdepth 1 -name '*-installer-linux.run' | head -1) ;;
  2321) run=$bundle ;;
  *) die "$SHOWN is neither a TM1 v12 package (.tgz) nor its installer (.run)" ;;
esac
[ -n "$run" ] || die "no *-installer-linux.run inside the package"

# Unpacked rather than run: nothing of IBM's installer executes (see unpack-tm1-engine.py).
python3 /usr/local/lib/tm1-engine/unpack-tm1-engine.py "$run" "$work/inst" >&2 \
  || die "couldn't unpack the installer in $SHOWN"
[ -f "$work/inst/bin/tm1sd.exe" ] && [ -d "$work/inst/deployment" ] \
  || die "$SHOWN doesn't look like a TM1 v12 installer (no bin/tm1sd.exe or deployment/ in it)"

# Only now, with a package known to be good, is what was in the target replaced
: > "$INSTALLING"
find "$TARGET" -mindepth 1 -maxdepth 1 ! -name .install.lock ! -name .tm1-engine-installing -exec rm -rf {} +
cp -a "$work/inst/bin" "$TARGET/bin"
cp -a "$work/inst/deployment" "$TARGET/deployment-template"
[ -d "$work/inst/copyright" ] && cp -a "$work/inst/copyright" "$TARGET/copyright"
# What the installer would have told us: TM1's version, and the MongoDB version it downloads.
# (Another TM1 version may not have these files: the versions are then unknown, not an error.)
{ sed -n 's/^VERSION="\(.*\)"$/\1/p' "$work/inst/installer.sh" 2>/dev/null || true; } | head -1 > "$TARGET/version.txt"
{ sed -n 's/^DS_VERSION="\(.*\)"$/\1/p' "$work/inst/scripts/datastore.sh" 2>/dev/null || true; } | head -1 > "$TARGET/datastore-version.txt"

chmod -R u+rwX,go+rX "$TARGET"
chmod -R a+x "$TARGET/bin"
echo "$KEY" > "$MARKER"
rm -f "$INSTALLING"
version=$(cat "$TARGET/version.txt" 2>/dev/null || true)
log "Installed: TM1 ${version:-(unknown version)}"

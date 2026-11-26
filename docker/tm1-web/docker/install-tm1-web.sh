#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

# tm1-web-install: installs TM1 Web from a user-supplied IBM bundle.
#
# Runs as `tm1-web install [SOURCE]`, and automatically from the entrypoint when
# no TM1 Web install is mounted but a bundle is available.
#
#   tm1-web-install [SOURCE] [--target DIR]     install (no-op if DIR already has this bundle)
#   tm1-web-install [SOURCE] --print-key        print the bundle's cache key and exit
#
# SOURCE (first match wins when omitted):
#   -                         a bundle piped in on stdin
#   https://... http://...    a URL, e.g. a pre-signed S3/R2/GCS link (file:// works too)
#   /absolute/path            a bundle file
#   name                      a bundle file in $TM1_WEB_BUNDLE_DIR (default /ibm)
#   (omitted)                 $TM1_WEB_BUNDLE_URL, else $TM1_WEB_BUNDLE, else the newest
#                             tm1web-*_bundle.tar.gz or tm1web-*-linuxi38664h.zip
#                             in $TM1_WEB_BUNDLE_DIR
#
# A bundle is IBM's tm1web-<ver>-<pa ver>-linuxi38664h_bundle.tar.gz, or the
# repository zip inside it. The type is detected from the file contents, so URLs
# don't need a file extension. Set TM1_WEB_BUNDLE_SHA256 to verify the bundle first.
#
# Instead of running IBM's installer, the bundle's manifest is followed and each
# component (Java, Liberty, the tm1web and evalservice apps) is unpacked (see
# unpack-tm1-web.py). That is what the installer does, and it also works where the
# installer can't run.
set -euo pipefail

SRC_DIR=${TM1_WEB_BUNDLE_DIR:-/ibm}
INSTALLABLES=${TM1_WEB_INSTALLABLES:-TM1_WebTier}

log() { echo "[tm1-web-install] $*" >&2; }
die() { echo "[tm1-web-install] ERROR: $*" >&2; exit 2; }

# ---- arguments --------------------------------------------------------------
SOURCE=; TARGET=${TM1_WEB_HOME:-/opt/ibm/cognos/tm1web}; PRINT_KEY=F
while [ $# -gt 0 ]; do
  case "$1" in
    --target) TARGET=$2; shift 2 ;;
    --print-key) PRINT_KEY=T; shift ;;
    -h|--help) sed -n '2,27p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) [ -z "$SOURCE" ] || die "unexpected argument: $1"; SOURCE=$1; shift ;;
  esac
done

newest_in_dir() {
  shopt -s nullglob
  local pattern files
  for pattern in "tm1web-*_bundle.tar.gz" "tm1web-*-linuxi38664h.zip"; do
    # shellcheck disable=SC2206  # the glob in $pattern is meant to expand
    files=("$SRC_DIR"/$pattern)
    if [ ${#files[@]} -gt 0 ]; then printf '%s\n' "${files[@]}" | sort -V | tail -1; return; fi
  done
}

if [ -z "$SOURCE" ]; then
  if [ -n "${TM1_WEB_BUNDLE_URL:-}" ]; then SOURCE=$TM1_WEB_BUNDLE_URL
  elif [ -n "${TM1_WEB_BUNDLE:-}" ]; then SOURCE=$TM1_WEB_BUNDLE
  else SOURCE=$(newest_in_dir)
  fi
fi
[ -n "$SOURCE" ] || die "no bundle found. Mount your IBM downloads at $SRC_DIR (-v /path/to/downloads:$SRC_DIR:ro), set TM1_WEB_BUNDLE_URL, or pipe one in: ... | docker run -i ... install -"

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
if [ "$KIND" = file ]; then [ -f "$SOURCE" ] || die "bundle not found: $SOURCE"; fi

# Cache key: identifies a bundle cheaply, without reading all of it.
#   file: name, size and modification time
#   url:  TM1_WEB_BUNDLE_SHA256 if set, else the URL without its query string
#   stdin: no key until it has been read (always installs)
case "$KIND" in
  file) KEY=$(printf 'file:%s:%s' "$(basename "$SOURCE")" "$(stat -c '%s:%Y' "$SOURCE")" | sha256sum | cut -c1-16) ;;
  url)  KEY=$(printf 'url:%s' "${TM1_WEB_BUNDLE_SHA256:-$SHOWN}" | sha256sum | cut -c1-16) ;;
  stdin) KEY= ;;
esac
if [ "$PRINT_KEY" = T ]; then
  [ -n "$KEY" ] || die "--print-key needs a file or URL source"
  echo "$KEY"; exit 0
fi

# ---- install ------------------------------------------------------------------
MARKER="$TARGET/.tm1-web-install"
mkdir -p "$TARGET" 2>/dev/null || die "can't create $TARGET"
[ -w "$TARGET" ] || die "$TARGET is read-only; mount it read-write to install into it"
# Serialize installs when several containers share one install volume.
exec 9>"$TARGET/.install.lock"
flock 9

if [ -n "$KEY" ] && [ -f "$MARKER" ] && [ "$(cat "$MARKER")" = "$KEY" ] && [ -x "$TARGET/wlp/bin/server" ]; then
  log "TM1 Web already installed in $TARGET from this bundle ($SHOWN)"
  exit 0
fi
if [ -e "$TARGET/wlp" ] && [ ! -f "$MARKER" ]; then
  die "$TARGET already contains a TM1 Web install not created by tm1-web-install; refusing to overwrite it"
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Fetch remote and piped bundles to a local file first: the bundle is a tar.gz
# containing a zip, and a zip can't be unpacked until it's complete.
case "$KIND" in
  file) bundle=$SOURCE ;;
  url)
    log "Downloading $SHOWN"
    bundle="$work/bundle"
    curl -fsSL --retry 3 --retry-delay 2 -o "$bundle" "$SOURCE" || die "download failed: $SHOWN"
    ;;
  stdin)
    [ -t 0 ] && die "install - reads a bundle from stdin; pipe one in and use docker run -i"
    log "Reading bundle from stdin"
    bundle="$work/bundle"
    cat > "$bundle"
    [ -s "$bundle" ] || die "nothing was received on stdin"
    KEY=$(sha256sum "$bundle" | cut -c1-16)
    ;;
esac

if [ -n "${TM1_WEB_BUNDLE_SHA256:-}" ]; then
  actual=$(sha256sum "$bundle" | cut -d' ' -f1)
  [ "$actual" = "${TM1_WEB_BUNDLE_SHA256,,}" ] || die "checksum mismatch for $SHOWN: expected $TM1_WEB_BUNDLE_SHA256, got $actual"
  log "Checksum verified"
fi

log "Installing TM1 Web from $SHOWN into $TARGET (one-time)"
find "$TARGET" -mindepth 1 -maxdepth 1 ! -name .install.lock -exec rm -rf {} +

# Detect the type from the contents: gzip (1f8b) = the IBM bundle, zip (504b) = the
# repository zip, and a plain tar (ustar at byte 257) = the IBM bundle too: Planning
# Analytics 2.1.2 to 2.1.16's TM1 Web bundles are uncompressed whatever their name says.
# The bundle also holds IBM's installer, which isn't needed.
kind=$(head -c2 "$bundle" | od -An -tx1 | tr -d ' \n')
if [ "$kind" != 1f8b ] && [ "$kind" != 504b ] && [ "$(dd if="$bundle" bs=1 skip=257 count=5 2>/dev/null)" = ustar ]; then
  kind=tar
fi
case "$kind" in
  1f8b|tar)
    tar xf "$bundle" -C "$work" --wildcards 'tm1web-*.zip' || die "no tm1web-*.zip inside the bundle"
    zip=$(ls -1 "$work"/tm1web-*.zip | head -1) ;;
  504b) zip=$bundle ;;
  *) die "$SHOWN is neither a tm1web bundle (.tar.gz) nor its repository zip" ;;
esac

# shellcheck disable=SC2086
python3 /usr/local/lib/tm1-web/unpack-tm1-web.py "$zip" "$TARGET" $INSTALLABLES >&2

# What IBM's unixPostInstall.sh does
chmod -R 755 "$TARGET"
echo "$KEY" > "$MARKER"
log "Installed: TM1 Web $(sed -n 's/^VersionNum=//p' "$TARGET"/tm1javaweb-*.txt 2>/dev/null | head -1) (Planning Analytics $(sed -n 's/^PA_VersionNum=//p' "$TARGET"/tm1javaweb-*.txt 2>/dev/null | head -1))"

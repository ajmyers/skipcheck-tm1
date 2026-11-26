#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

# tm1-install: installs TM1 Server from a user-supplied IBM bundle.
#
# Runs as `tm1-server install [SOURCE]`, and automatically from the entrypoint when
# no TM1 install is mounted but a bundle is available.
#
#   tm1-install [SOURCE] [--target DIR]     install (no-op if DIR already has this bundle)
#   tm1-install [SOURCE] --print-key        print the bundle's cache key and exit
#
# SOURCE (first match wins when omitted):
#   -                         a bundle piped in on stdin
#   https://... http://...    a URL, e.g. a pre-signed S3/R2/GCS link (file:// works too)
#   /absolute/path            a bundle file
#   name                      a bundle file in $TM1_BUNDLE_DIR (default /ibm)
#   (omitted)                 $TM1_BUNDLE_URL, else $TM1_BUNDLE, else the newest
#                             tm1server-*_bundle.tar.gz, tm1server-*-linuxi38664h.zip or
#                             tm1_linuxi38664h_*_ml.tar.gz in $TM1_BUNDLE_DIR
#
# A bundle is IBM's tm1server-<ver>-linuxi38664h_bundle.tar.gz, the repository zip
# inside it, or the older full installer tm1_linuxi38664h_<ver>_ml.tar.gz (the only
# download for Planning Analytics 2.1.2 and 2.1.7; see unpack-tm1-issetup.py). The
# type is detected from the file contents, so URLs don't need a file extension. Set
# TM1_BUNDLE_SHA256 to verify the bundle before installing.
#
# Instead of running IBM's installer, the bundle's manifest is followed and each
# component tarball is unpacked (see unpack-tm1.py). That is what the installer
# does, and it also works where the installer can't run.
set -euo pipefail

SRC_DIR=${TM1_BUNDLE_DIR:-/ibm}
# What to install from a bundle's manifest; a trailing ? makes one optional (see unpack-tm1.py):
# 2.1.8 to 2.1.10 have no software tags
INSTALLABLES=${TM1_INSTALLABLES:-TM1_Server TM1_Admin_Server TM1_Tools TM1_Samples TM1_NON-PROD_SWTAG?}

log() { echo "[tm1-install] $*" >&2; }
die() { echo "[tm1-install] ERROR: $*" >&2; exit 2; }

# ---- arguments --------------------------------------------------------------
SOURCE=; TARGET=${TM1_HOME:-/opt/ibm/cognos/tm1_64}; PRINT_KEY=F
while [ $# -gt 0 ]; do
  case "$1" in
    --target) TARGET=$2; shift 2 ;;
    --print-key) PRINT_KEY=T; shift ;;
    -h|--help) sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) [ -z "$SOURCE" ] || die "unexpected argument: $1"; SOURCE=$1; shift ;;
  esac
done

newest_in_dir() {
  shopt -s nullglob
  local pattern files
  for pattern in "tm1server-*_bundle.tar.gz" "tm1server-*-linuxi38664h.zip" "tm1_linuxi38664h_*_ml.tar.gz"; do
    # shellcheck disable=SC2206  # the glob in $pattern is meant to expand
    files=("$SRC_DIR"/$pattern)
    if [ ${#files[@]} -gt 0 ]; then printf '%s\n' "${files[@]}" | sort -V | tail -1; return; fi
  done
}

if [ -z "$SOURCE" ]; then
  if [ -n "${TM1_BUNDLE_URL:-}" ]; then SOURCE=$TM1_BUNDLE_URL
  elif [ -n "${TM1_BUNDLE:-}" ]; then SOURCE=$TM1_BUNDLE
  else SOURCE=$(newest_in_dir)
  fi
fi
[ -n "$SOURCE" ] || die "no bundle found. Mount your IBM downloads at $SRC_DIR (-v /path/to/downloads:$SRC_DIR:ro), set TM1_BUNDLE_URL, or pipe one in: ... | docker run -i ... install -"

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
#   url:  TM1_BUNDLE_SHA256 if set, else the URL without its query string
#   stdin: no key until it has been read (always installs)
case "$KIND" in
  file) KEY=$(printf 'file:%s:%s' "$(basename "$SOURCE")" "$(stat -c '%s:%Y' "$SOURCE")" | sha256sum | cut -c1-16) ;;
  url)  KEY=$(printf 'url:%s' "${TM1_BUNDLE_SHA256:-$SHOWN}" | sha256sum | cut -c1-16) ;;
  stdin) KEY= ;;
esac
if [ "$PRINT_KEY" = T ]; then
  [ -n "$KEY" ] || die "--print-key needs a file or URL source"
  echo "$KEY"; exit 0
fi

# ---- install ------------------------------------------------------------------
MARKER="$TARGET/.tm1-server-install"
mkdir -p "$TARGET" 2>/dev/null || die "can't create $TARGET"
[ -w "$TARGET" ] || die "$TARGET is read-only; mount it read-write to install into it"
# Serialize installs when several containers share one install volume.
exec 9>"$TARGET/.install.lock"
flock 9

if [ -n "$KEY" ] && [ -f "$MARKER" ] && [ "$(cat "$MARKER")" = "$KEY" ] && [ -x "$TARGET/bin64/tm1s.exe" ]; then
  log "TM1 already installed in $TARGET from this bundle ($SHOWN)"
  exit 0
fi
if [ -e "$TARGET/bin64" ] && [ ! -f "$MARKER" ]; then
  die "$TARGET already contains a TM1 install not created by tm1-install; refusing to overwrite it"
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

if [ -n "${TM1_BUNDLE_SHA256:-}" ]; then
  actual=$(sha256sum "$bundle" | cut -d' ' -f1)
  [ "$actual" = "${TM1_BUNDLE_SHA256,,}" ] || die "checksum mismatch for $SHOWN: expected $TM1_BUNDLE_SHA256, got $actual"
  log "Checksum verified"
fi

# Split the list into words (across lines too), without globbing: a ? would match file names
read -r -d '' -a installables <<<"$INSTALLABLES" || true

# Detect the type from the contents: gzip (1f8b) = the IBM bundle or the older full installer,
# told apart by its first entries; zip (504b) = the repository zip. A repository is unpacked
# and checked against the installables before anything in $TARGET is removed: a bundle
# without one of them leaves an earlier install as it was.
issetup=F; zip=
case "$(head -c2 "$bundle" | od -An -tx1 | tr -d ' \n')" in
  1f8b)
    if python3 /usr/local/lib/tm1-server/unpack-tm1-issetup.py detect "$bundle"; then
      issetup=T
    else
      tar xzf "$bundle" -C "$work" --wildcards 'tm1server-*.zip' || die "no tm1server-*.zip inside the bundle"
      zip=$(ls -1 "$work"/tm1server-*.zip | head -1)
    fi ;;
  504b) zip=$bundle ;;
  *) die "$SHOWN is neither a tm1server bundle (.tar.gz), its repository zip, nor IBM's full installer (tm1_linuxi38664h_*_ml.tar.gz)" ;;
esac
if [ -n "$zip" ]; then
  unzip -q "$zip" -d "$work/repo"
  python3 /usr/local/lib/tm1-server/unpack-tm1.py --check "$work/repo" "${installables[@]}" >&2 \
    || die "can't install $SHOWN as asked (TM1_INSTALLABLES)"
fi

log "Installing TM1 Server from $SHOWN into $TARGET (one-time)"
find "$TARGET" -mindepth 1 -maxdepth 1 ! -name .install.lock -exec rm -rf {} +
# Until it's done: an install that fails partway (a full disk) leaves this, so the next run
# installs again rather than refusing a folder it didn't make, or starting half a TM1
echo incomplete > "$MARKER"

if [ "$issetup" = T ]; then
  [ -z "${TM1_INSTALLABLES:-}" ] || log "TM1_INSTALLABLES doesn't apply to IBM's full installer: every component but the language packs is installed"
  TMPDIR=$work python3 /usr/local/lib/tm1-server/unpack-tm1-issetup.py install "$bundle" "$TARGET" >&2 \
    || die "couldn't install from the full installer $SHOWN"
else
  python3 /usr/local/lib/tm1-server/unpack-tm1.py "$work/repo" "$TARGET" "${installables[@]}" >&2 \
    || die "couldn't install from $SHOWN"
fi

chmod -R u+rwX,go+rX "$TARGET"
find "$TARGET/bin64" -maxdepth 1 \( -name '*.exe' -o -name '*.sh' -o -name 'gsk*' -o -name '*.so*' \) -exec chmod 755 {} +
echo "$KEY" > "$MARKER"
log "Installed: $(head -1 "$TARGET/tm1server_version.txt" 2>/dev/null || echo unknown version)"

#!/bin/bash
# One-off helper (`tm1-server install`): unpacks TM1 Server into $TM1_HOME from a
# user-supplied IBM bundle mounted at $TM1_BUNDLE_DIR. Not run automatically - the
# normal workflow is to mount an existing tm1_64 directory at $TM1_HOME.
#
# Accepted inputs (first match wins):
#   $TM1_BUNDLE                          explicit file name inside $TM1_BUNDLE_DIR
#   tm1server-*_bundle.tar.gz            the download from IBM (Fix Central / Passport Advantage)
#   tm1server-*-linuxi38664h.zip         the repository zip inside that bundle
#
# Skipped if $TM1_HOME already holds an install of the same bundle.
set -euo pipefail

TM1_HOME=${TM1_HOME:-/opt/ibm/cognos/tm1_64}
SRC_DIR=${TM1_BUNDLE_DIR:-/ibm}
MARKER="$TM1_HOME/.tm1-server-install"
INSTALLABLES=${TM1_INSTALLABLES:-TM1_Server TM1_Admin_Server TM1_Tools TM1_Samples TM1_NON-PROD_SWTAG}

log() { echo "[tm1-install] $*"; }

mkdir -p "$TM1_HOME"
# Serialize installs when several containers share one install volume.
exec 9>"$TM1_HOME/.install.lock"
flock 9

find_bundle() {
  if [ -n "${TM1_BUNDLE:-}" ]; then echo "$SRC_DIR/$TM1_BUNDLE"; return; fi
  shopt -s nullglob
  local pattern files
  for pattern in "tm1server-*_bundle.tar.gz" "tm1server-*-linuxi38664h.zip"; do
    # shellcheck disable=SC2206  # the glob in $pattern is meant to expand
    files=("$SRC_DIR"/$pattern)
    if [ ${#files[@]} -gt 0 ]; then printf '%s\n' "${files[@]}" | sort -V | tail -1; return; fi
  done
}

bundle=$(find_bundle)

if [ -z "$bundle" ] || [ ! -f "$bundle" ]; then
  echo "[tm1-install] ERROR: no tm1server-*_bundle.tar.gz (or tm1server-*-linuxi38664h.zip) in $SRC_DIR" >&2
  echo "[tm1-install] Mount your IBM downloads folder there: -v /path/to/ibm-downloads:$SRC_DIR:ro" >&2
  exit 2
fi
if [ -e "$TM1_HOME/bin64" ] && [ ! -f "$MARKER" ]; then
  echo "[tm1-install] ERROR: $TM1_HOME already contains a TM1 install not created by this tool; refusing to overwrite." >&2
  exit 2
fi

id="$(basename "$bundle") $(stat -c %s "$bundle")"
if [ -f "$MARKER" ] && [ "$(cat "$MARKER")" = "$id" ] && [ -x "$TM1_HOME/bin64/tm1s.exe" ]; then
  log "TM1 already installed from $(basename "$bundle")"
  exit 0
fi

log "Installing TM1 Server from $(basename "$bundle") into $TM1_HOME (one-time, ~1 min)"
find "$TM1_HOME" -mindepth 1 -maxdepth 1 ! -name .install.lock -exec rm -rf {} +
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

case "$bundle" in
  *.tar.gz|*.tgz)
    tar xzf "$bundle" -C "$work" --wildcards 'tm1server-*.zip'
    zip=$(ls -1 "$work"/tm1server-*.zip | head -1) ;;
  *.zip) zip=$bundle ;;
  *) log "Unsupported bundle type: $bundle"; exit 2 ;;
esac

unzip -q "$zip" -d "$work/repo"
# shellcheck disable=SC2086
python3 /usr/local/lib/tm1-server/unpack-tm1.py "$work/repo" "$TM1_HOME" $INSTALLABLES

chmod -R u+rwX,go+rX "$TM1_HOME"
find "$TM1_HOME/bin64" -maxdepth 1 \( -name '*.exe' -o -name '*.sh' -o -name 'gsk*' -o -name '*.so*' \) -exec chmod 755 {} +
echo "$id" > "$MARKER"
log "Installed: $(head -1 "$TM1_HOME/tm1server_version.txt" 2>/dev/null || echo unknown version)"

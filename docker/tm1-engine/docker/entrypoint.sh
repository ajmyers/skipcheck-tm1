#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

# tm1-engine entrypoint. The container is a thin runtime shell around your own TM1 v12.
#
# Where TM1 comes from (first match wins):
#   1. a TM1 install at /opt/ibm/tm1-engine: a mounted folder, a named volume already
#      holding an install, or a private image with TM1 built in
#   2. a package, installed on first start: TM1_ENGINE_BUNDLE_URL (download),
#      TM1_ENGINE_BUNDLE, or the newest tm1-v*-standalone-linux.tgz mounted at /ibm. An
#      empty volume mounted at /opt/ibm/tm1-engine is installed into directly; otherwise
#      the install goes to a cache in /tm1/cache (mount a volume there to keep it
#      between containers), one folder per package.
#   3. `install -` with a package piped in on stdin (docker run -i)
#
# TM1 keeps its metadata in MongoDB (its "datastore"):
#   TM1_DATASTORE_URL        a MongoDB for it to use, e.g. mongodb://root:pw@mongo:27017
#                            (a mongo:8.0 container). The usual way.
#   (not set)                TM1 runs its own: MongoDB is downloaded into the install on
#                            first start, with its cache held to TM1_DATASTORE_CACHE_GB
#                            (default 0.5)
#
# Optional mounts (unmounted ones live inside the container and are removed with it):
#   /tm1/deployment          configuration, databases and logs
#   /tm1/credentials         the root and application credentials (see tm1-engine credentials)
#
# The port inside the container is fixed; map it to any host port with -p:
#   4444   TM1's gateway: the REST API, logins and the management API
#
# Commands:
#   (none) | start   run TM1, and create what TM1_DATABASES, TM1_APPLICATIONS and
#                    TM1_USERS ask for (see tm1-engine --help)
#   install [SOURCE] install TM1 from a package into /opt/ibm/tm1-engine and exit;
#                    SOURCE is a URL, a file, or - for stdin (see tm1-engine-install --help)
#   <anything else>  exec'd as-is (e.g. bash, or tm1-engine --help)
#
# Stopping: on SIGTERM each database is stopped cleanly first (TM1 commits and shuts it
# down), then TM1. Allow it time: docker stop -t 120.
set -euo pipefail

banner() { cat /usr/local/lib/tm1-engine/banner.txt 2>/dev/null || true; }

case "${1:-start}" in
  start) banner ;;
  install) shift; banner; exec /usr/local/bin/tm1-engine-install "$@" ;;
  *) exec "$@" ;;
esac

TM1_ENGINE_HOME=${TM1_ENGINE_HOME:-/opt/ibm/tm1-engine}
BIN="$TM1_ENGINE_HOME/bin"
DEPLOYMENT=${TM1_ENGINE_DEPLOYMENT:-/tm1/deployment}
CREDENTIALS=${TM1_ENGINE_CREDENTIALS:-/tm1/credentials}
LIB=/usr/local/lib/tm1-engine

log() { echo "[tm1-engine-entrypoint] $*"; }
die() { echo "[tm1-engine-entrypoint] ERROR: $*" >&2; exit 2; }

# ---- find or install TM1 ----------------------------------------------------
have_bundle() {
  [ -n "${TM1_ENGINE_BUNDLE_URL:-}" ] || [ -n "${TM1_ENGINE_BUNDLE:-}" ] && return 0
  compgen -G "${TM1_ENGINE_BUNDLE_DIR:-/ibm}/tm1-v*-standalone-linux.tgz" >/dev/null
}
if [ ! -x "$BIN/tm1sd.exe" ] && have_bundle; then
  if mountpoint -q "$TM1_ENGINE_HOME"; then
    # A volume (or folder) is mounted there without an install: install into it, so
    # it's filled once and reused.
    log "No TM1 install in the volume at $TM1_ENGINE_HOME - installing from the package"
    /usr/local/bin/tm1-engine-install --target "$TM1_ENGINE_HOME" || exit $?
  else
    CACHE=${TM1_ENGINE_CACHE:-/tm1/cache}
    key=$(/usr/local/bin/tm1-engine-install --print-key) || exit $?
    /usr/local/bin/tm1-engine-install --target "$CACHE/$key" || exit $?
    rm -rf "$TM1_ENGINE_HOME"
    mkdir -p "$(dirname "$TM1_ENGINE_HOME")"
    ln -s "$CACHE/$key" "$TM1_ENGINE_HOME"
    log "Using TM1 from $CACHE/$key"
  fi
fi

if [ ! -x "$BIN/tm1sd.exe" ]; then
  cat >&2 <<MSG
[tm1-engine-entrypoint] ERROR: $BIN/tm1sd.exe not found.

  tm1-engine does not include IBM software. Give it your own licensed TM1 v12
  (tm1-v<version>-standalone-linux.tgz) in one of these ways:

    Mount the folder with your IBM download (installed on first start):
      -v /path/to/ibm-downloads:/ibm:ro
    Download the package (e.g. a pre-signed S3 link; installed on first start):
      -e TM1_ENGINE_BUNDLE_URL=https://...
    Pipe a package into a volume, then mount that volume:
      cat tm1-v12...-standalone-linux.tgz | docker run -i --rm -v tm1-engine:$TM1_ENGINE_HOME tm1-engine install -
    Mount an install made that way (the folder with bin/tm1sd.exe):
      -v /path/to/tm1-engine:$TM1_ENGINE_HOME

  To keep a package install between containers, also mount a volume at /tm1/cache.
MSG
  exit 2
fi

# ---- the deployment: configuration, databases, logs ---------------------------
mkdir -p "$DEPLOYMENT" "$CREDENTIALS"
if [ ! -f "$DEPLOYMENT/config.internal.json" ]; then
  [ -d "$TM1_ENGINE_HOME/deployment-template" ] \
    || die "$TM1_ENGINE_HOME has no deployment-template/ (it wasn't installed by this image), and $DEPLOYMENT has no configuration. Mount that install's deployment folder at $DEPLOYMENT."
  cp -r "$TM1_ENGINE_HOME/deployment-template/." "$DEPLOYMENT/"
  log "New deployment in $DEPLOYMENT"
elif [ -d "$TM1_ENGINE_HOME/deployment-template/tls" ] \
    && ! cmp -s "$TM1_ENGINE_HOME/.tm1-engine-install" "$DEPLOYMENT/.tm1-engine-install" 2>/dev/null; then
  # Another TM1 than the one that made this deployment: take its certificates, as
  # IBM's installer does on an upgrade. They secure TM1's own services to each other.
  cp -r "$TM1_ENGINE_HOME/deployment-template/tls/." "$DEPLOYMENT/tls/"
  log "Deployment in $DEPLOYMENT was made by another TM1 install - updated its internal certificates"
fi
cp "$TM1_ENGINE_HOME/.tm1-engine-install" "$DEPLOYMENT/.tm1-engine-install" 2>/dev/null || true

datastore=$(/usr/local/bin/tm1-engine configure) || exit $?

# ---- TM1's own MongoDB, when it isn't given one --------------------------------
if [ "$datastore" = embedded ]; then
  ds="$TM1_ENGINE_HOME/third_party/datastore"
  if [ ! -x "$ds/mongod" ]; then
    [ -w "$TM1_ENGINE_HOME/" ] || die "TM1 needs MongoDB. Set TM1_DATASTORE_URL to one (e.g. a mongo:8.0 container: mongodb://user:password@mongo:27017), or mount $TM1_ENGINE_HOME read-write so MongoDB can be downloaded into it."
    version=$(cat "$TM1_ENGINE_HOME/datastore-version.txt" 2>/dev/null || true); version=${version:-8.0.32}
    url=${TM1_DATASTORE_DOWNLOAD_URL:-https://fastdl.mongodb.org/linux/mongodb-linux-x86_64-ubuntu2404-$version.tgz}
    (
      flock 9
      [ -x "$ds/mongod" ] && exit 0   # another container sharing this install got there first
      log "TM1_DATASTORE_URL isn't set, so TM1 runs its own MongoDB: downloading MongoDB $version (one-time)"
      tmp=$(mktemp -d)
      curl -fsSL --retry 3 --retry-delay 2 -o "$tmp/mongodb.tgz" "$url" || die "download failed: $url. Set TM1_DATASTORE_URL to a MongoDB instead."
      if sum=$(curl -fsSL --retry 3 "$url.sha256" 2>/dev/null); then
        echo "${sum%% *}  $tmp/mongodb.tgz" | sha256sum -c --quiet - || die "checksum mismatch for $url"
      else
        log "WARNING: no checksum at $url.sha256 - not verified"
      fi
      # Python, not tar: GNU tar 1.35 needs openat2, which emulators such as Rosetta lack
      python3 -c 'import sys, tarfile; tarfile.open(sys.argv[1]).extractall(sys.argv[2], filter="tar")' "$tmp/mongodb.tgz" "$tmp" \
        || die "couldn't unpack $url"
      real=$(find "$tmp" -type f -name mongod | head -1)
      [ -n "$real" ] || die "no mongod in $url"
      mkdir -p "$ds"
      find "$tmp" -type f \( -name 'LICENSE*' -o -name 'MPL-2' -o -name 'THIRD-PARTY-NOTICES' -o -name README \) -exec mv {} "$ds/" \;
      mv "$real" "$ds/mongod.bin"
      # TM1 starts mongod with a 16 GB cache; the wrapper holds it to TM1_DATASTORE_CACHE_GB
      install -m 755 "$LIB/mongod-wrapper.sh" "$ds/mongod"
      rm -rf "$tmp"
    ) 9>"$TM1_ENGINE_HOME/.datastore.lock" || exit $?
  elif [ ! -e "$ds/mongod.bin" ]; then
    log "WARNING: this install brings its own MongoDB, which TM1 starts with a 16 GB cache. Set TM1_DATASTORE_URL to use a MongoDB of your own instead."
  fi
fi

# ---- TM1 ------------------------------------------------------------------------
cd "$DEPLOYMENT"
mkdir -p logs
touch logs/tm1sd.jsonl
tail -n 0 -F logs/tm1sd.jsonl 2>/dev/null &
TAIL_PID=$!

log "Starting TM1 $(cat "$TM1_ENGINE_HOME/version.txt" 2>/dev/null || true) (gateway on 4444, datastore: $datastore)"
"$BIN/tm1sd.exe" -z "$DEPLOYMENT" &
TM1_PID=$!

running() { kill -0 "$TM1_PID" 2>/dev/null; }

stop_tm1() {
  # tm1sd doesn't stop its databases (tm1s.exe): left alone, they'd be killed with the
  # container. So they're stopped through TM1 first, which commits each one.
  if running; then
    /usr/local/bin/tm1-engine stop-databases "${TM1_ENGINE_STOP_TIMEOUT:-60}" || true
    kill -TERM "$TM1_PID" 2>/dev/null || true
    for _ in $(seq 1 30); do running || break; sleep 1; done
  fi
  # Anything of TM1's still here would be killed with the container anyway. Its
  # processes run from where the install really is (the cache, behind a link).
  pkill -KILL -f "^$(readlink -f "$TM1_ENGINE_HOME")/" 2>/dev/null || true
  kill "$TAIL_PID" 2>/dev/null || true
}

shutdown() {
  log "Stopping TM1 (each database first, so it's saved)"
  stop_tm1
  log "Stopped."
  exit 0
}
trap shutdown INT TERM

# Create what was asked for once TM1 answers. A failure stops the container: half a
# setup isn't what was asked for.
rm -f /tmp/tm1-engine-ready.json /tmp/tm1-engine-setup-failed
( /usr/local/bin/tm1-engine setup || touch /tmp/tm1-engine-setup-failed ) &

# Sleep in the background + wait, so the trap fires immediately on a signal.
while running; do
  if [ -e /tmp/tm1-engine-setup-failed ]; then
    log "Setting TM1 up failed (see above) - stopping"
    stop_tm1
    exit 1
  fi
  sleep 2 & wait $! || true
done
log "TM1 exited unexpectedly - last lines of its log:"
tail -30 logs/tm1sd.jsonl || true
stop_tm1
exit 1

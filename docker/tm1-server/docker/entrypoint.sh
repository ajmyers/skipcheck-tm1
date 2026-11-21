#!/bin/bash
# tm1-server entrypoint. The container is a thin runtime shell: you mount
#   /opt/ibm/cognos/tm1_64   your TM1 Server install (must contain bin64/tm1s.exe)
#   /tm1/config              tm1s.cfg + tm1s-log.properties (created with defaults if absent)
#   /tm1/data                the TM1 database (cubes, dimensions, processes, ...)
#   /tm1/logs                TM1 logs (LoggingDirectory)
#
# tm1s.exe is started with -z/tm1/config, so the tm1s.cfg in that folder is the one
# that runs (and the one TM1 itself updates, e.g. via the REST StaticConfiguration API).
#
# Commands:
#   (none) | start   start the Admin Server (optional) and one TM1 server instance
#   install          one-off helper: unpack an IBM bundle mounted at /ibm into
#                    /opt/ibm/cognos/tm1_64 (see README) and exit
#   <anything else>  exec'd as-is (e.g. bash)
#
# Environment (all optional). The named TM1_* settings seed a newly created tm1s.cfg;
# for an existing tm1s.cfg they are applied only if explicitly set.
#   TM1_SERVER_NAME     ServerName                             (default: tm1)
#   TM1_HTTP_PORT       HTTPPortNumber (REST API)              (default: 8010)
#   TM1_PORT            PortNumber (legacy API)                (default: 12345)
#   TM1_USE_SSL         UseSSL, T/F                            (default: T)
#   TM1_CFG_<Param>     Any tm1s.cfg parameter, e.g. TM1_CFG_EnableTIDebugging=T
#   TM1_SEED            empty | sdata | 24retail - objects to put in an EMPTY /tm1/data
#   TM1_ADMIN_SERVER    T/F - run tm1admsrv in this container  (default: T)
set -euo pipefail

case "${1:-start}" in
  start) ;;
  install) shift; exec /usr/local/bin/tm1-install "$@" ;;
  *) exec "$@" ;;
esac

TM1_HOME=${TM1_HOME:-/opt/ibm/cognos/tm1_64}
BIN="$TM1_HOME/bin64"
CONF=${TM1_CONFIG:-/tm1/config}
DATA=${TM1_DATA:-/tm1/data}
LOGS=${TM1_LOGS:-/tm1/logs}
CFG="$CONF/tm1s.cfg"
DEFAULTS=/usr/local/lib/tm1-server

log() { echo "[tm1-entrypoint] $*"; }
die() { echo "[tm1-entrypoint] ERROR: $*" >&2; exit 2; }

if [ ! -x "$BIN/tm1s.exe" ]; then
  cat >&2 <<MSG
[tm1-entrypoint] ERROR: $BIN/tm1s.exe not found.

  tm1-server does not include IBM software. Mount your own licensed TM1 Server
  install directory (the one containing bin64/) at $TM1_HOME, e.g.

      -v /path/to/tm1_64:$TM1_HOME

  No Linux install yet? Unpack one from your IBM bundle with:

      docker run --rm -v /path/to/ibm-downloads:/ibm:ro \\
                      -v /path/to/tm1_64:$TM1_HOME tm1-server install
MSG
  exit 2
fi
mkdir -p "$CONF" "$DATA" "$LOGS"

# ---- seed objects into an empty database -----------------------------------
if [ -z "$(ls -A "$DATA")" ]; then
  seed=${TM1_SEED:-empty}
  case "$seed" in
    empty)    log "Empty database directory - TM1 will create a new database" ;;
    sdata|24retail)
      src="$TM1_HOME/samples/tm1/$([ "$seed" = sdata ] && echo SData || echo 24Retail)"
      [ -d "$src" ] || die "TM1_SEED=$seed but $src is not in your TM1 install"
      log "Empty database directory - seeding with sample '$seed' from your install"
      cp -a "$src/." "$DATA/"
      rm -f "$DATA/tm1s.cfg" "$DATA/tm1s-log.properties" ;;
    *) die "Unknown TM1_SEED '$seed' (use empty, sdata or 24retail)" ;;
  esac
fi

# ---- tm1s.cfg ---------------------------------------------------------------
WORK=$(mktemp)
get_cfg() {  # get_cfg Key -> value of Key in $WORK (empty if absent)
  grep -iE "^[[:space:]]*$1[[:space:]]*=" "$WORK" | tail -1 | cut -d= -f2- | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' || true
}
set_cfg() {  # set_cfg Key Value [reason] - replace the existing line or append one
  local key=$1 val=$2 why=${3:-}
  local cur; cur=$(get_cfg "$key")
  [ "$cur" = "$val" ] && grep -qiE "^[[:space:]]*${key}[[:space:]]*=" "$WORK" && return 0
  if grep -qiE "^[[:space:]]*${key}[[:space:]]*=" "$WORK"; then
    # [^\r]* keeps a trailing CR, so CRLF files stay CRLF
    sed -i -E "s|^[[:space:]]*${key}[[:space:]]*=[^\r]*|${key}=${val}|I" "$WORK"
  else
    printf '%s=%s%s\n' "$key" "$val" "$EOL" >> "$WORK"
  fi
  log "  ${key}=${val}${cur:+  (was: $cur)}${why:+  [$why]}"
}

if [ -f "$CFG" ]; then
  log "Using tm1s.cfg from $CONF"
  cp "$CFG" "$WORK"
  explicit=1
else
  log "No tm1s.cfg in $CONF - creating one from the default template"
  cp "$DEFAULTS/tm1s.cfg.default" "$WORK"
  explicit=0
fi

EOL=; grep -q $'\r$' "$WORK" && EOL=$'\r'
# make sure appended lines start on a new line
[ -n "$(tail -c1 "$WORK")" ] && printf '%s\n' "$EOL" >> "$WORK"
log "Applying settings:"
# Named settings: always for a new file, only when explicitly set for an existing one.
apply_named() {  # apply_named Key EnvVar Default
  local val=${!2:-}
  if [ -n "$val" ]; then set_cfg "$1" "$val" "$2"
  elif [ "$explicit" = 0 ]; then set_cfg "$1" "$3" "default"
  fi
}
apply_named ServerName     TM1_SERVER_NAME tm1
apply_named HTTPPortNumber TM1_HTTP_PORT   8010
apply_named PortNumber     TM1_PORT        12345
apply_named UseSSL         TM1_USE_SSL     T
# Generic passthrough: TM1_CFG_Foo=bar -> Foo=bar
while IFS='=' read -r name value; do
  [[ $name == TM1_CFG_* ]] || continue
  set_cfg "${name#TM1_CFG_}" "$value" "$name"
done < <(env)
# Required for this container to work.
set_cfg DataBaseDirectory   "$DATA"  "container path"
set_cfg LoggingDirectory    "$LOGS"  "container path"
set_cfg RunningInBackground "T"      "required in a container"
[ "${TM1_ADMIN_SERVER:-T}" = "T" ] && set_cfg AdminHost "localhost" "admin server runs in this container"

if [ ! -f "$CFG" ] || ! cmp -s "$WORK" "$CFG"; then
  if [ -f "$CFG" ] && [ ! -w "$CFG" ]; then
    die "$CFG needs the changes listed above but is read-only. Apply them to your file or mount $CONF read-write."
  fi
  cat "$WORK" > "$CFG"   # write in place (keeps single-file bind mounts working)
  log "Wrote $CFG"
else
  log "  (no changes - $CFG used as-is)"
fi
SERVER_NAME=$(get_cfg ServerName); HTTP_PORT=$(get_cfg HTTPPortNumber); USE_SSL=$(get_cfg UseSSL)
rm -f "$WORK"

if [ ! -f "$CONF/tm1s-log.properties" ]; then
  log "No tm1s-log.properties in $CONF - creating the default"
  cp "$DEFAULTS/tm1s-log.properties" "$CONF/"
fi

cd "$BIN"

# ---- admin server -----------------------------------------------------------
ADMIN_PID=
if [ "${TM1_ADMIN_SERVER:-T}" = "T" ]; then
  log "Starting TM1 Admin Server"
  # Writes tm1admsrv.log into bin64/ when the install is writable (skipped if mounted :ro).
  ./tm1admsrv.exe &
  ADMIN_PID=$!
fi

# ---- tm1 server -------------------------------------------------------------
# Stream the server message log (written by shared_memory_reader.exe) to stdout.
touch "$LOGS/tm1server.log"
tail -n 0 -F "$LOGS/tm1server.log" 2>/dev/null &
TAIL_PID=$!

log "Starting TM1 Server '$SERVER_NAME' (REST on ${HTTP_PORT:-none}, SSL=${USE_SSL:-T})"
# tm1s.exe daemonizes: the launcher exits 0 and the real server keeps running.
./tm1s.exe -w -z"$CONF"
TM1_PID=
for _ in $(seq 1 30); do
  TM1_PID=$(pgrep -f "^\./tm1s\.exe -w -z$CONF\$" | head -1 || true)
  [ -n "$TM1_PID" ] && break
  sleep 1
done
if [ -z "$TM1_PID" ]; then
  log "TM1 Server did not start - last lines of tm1server.log:"
  tail -50 "$LOGS/tm1server.log" || true
  exit 1
fi
log "TM1 Server running as PID $TM1_PID"

running() { kill -0 "$TM1_PID" 2>/dev/null; }

cleanup() {
  [ -n "$ADMIN_PID" ] && kill -TERM "$ADMIN_PID" 2>/dev/null
  sleep 1; kill "$TAIL_PID" 2>/dev/null
  true
}

shutdown() {
  log "Stopping TM1 Server (SIGINT -> save data and exit)"
  kill -INT "$TM1_PID" 2>/dev/null || true
  while running; do sleep 1; done
  cleanup
  log "Stopped."
  exit 0
}
trap shutdown INT TERM

# Sleep in the background + wait, so the trap fires immediately on a signal.
while running; do sleep 2 & wait $! || true; done
log "TM1 Server exited unexpectedly - last lines of tm1server.log:"
tail -30 "$LOGS/tm1server.log" || true
cleanup
exit 1

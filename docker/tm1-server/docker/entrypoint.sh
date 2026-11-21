#!/bin/bash
# tm1-server entrypoint. The container is a thin runtime shell: you mount
#   /opt/ibm/cognos/tm1_64   your TM1 Server install (must contain bin64/tm1s.exe)
#   /tm1/config              tm1s.cfg + tm1s-log.properties (created with defaults if absent)
#   /tm1/data                the TM1 database (cubes, dimensions, processes, ...)
#   /tm1/logs                TM1 logs (LoggingDirectory)
#
# tm1s.exe is started with -z/tm1/config, so the tm1s.cfg in that folder is the one
# that runs (and the one TM1 itself updates, e.g. via the REST StaticConfiguration API).
# If /tm1/config is read-only and needs changes, TM1 runs from a corrected copy instead.
#
# Ports inside the container are fixed; map them to any host port with -p:
#   8010 REST API (HTTPPortNumber), 12345 TM1 API (PortNumber),
#   12346 client messages (ClientMessagePortNumber), 5895/5898/5495/5498 Admin Server
#
# Commands:
#   (none) | start   run one TM1 server (plus an embedded Admin Server unless
#                    TM1_ADMIN_HOST points at a separate one)
#   admin            run only the TM1 Admin Server, for TM1 servers in other
#                    containers to register with (they set TM1_ADMIN_HOST)
#   install          one-off helper: unpack an IBM bundle mounted at /ibm into
#                    /opt/ibm/cognos/tm1_64 (see README) and exit
#   <anything else>  exec'd as-is (e.g. bash)
#
# Environment (all optional). The named TM1_* settings seed a newly created tm1s.cfg;
# for an existing tm1s.cfg they are applied only if explicitly set.
#   TM1_SERVER_NAME     ServerName                             (default: tm1)
#   TM1_USE_SSL         UseSSL, T/F                            (default: F, plain HTTP)
#   TM1_CFG_<Param>     Any tm1s.cfg parameter, e.g. TM1_CFG_EnableTIDebugging=T
#   TM1_SEED            empty | sdata | 24retail - objects to put in an EMPTY /tm1/data
#   TM1_ADMIN_HOST      host of a separate Admin Server (e.g. a container running
#                       `admin`); sets AdminHost and turns the embedded one off
#   TM1_ADMIN_SERVER    T/F - run an embedded Admin Server  (default: T, or F when
#                       TM1_ADMIN_HOST is set)
set -euo pipefail

case "${1:-start}" in
  start|server) ROLE="server" ;;
  admin) ROLE="admin" ;;
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

# tm1s.exe and tm1admsrv.exe both daemonize: the launcher exits 0 and the real
# process keeps running. Wait for the daemon and print its PID.
daemon_pid() {  # daemon_pid <pgrep -f pattern>
  local pid
  for _ in $(seq 1 30); do
    pid=$(pgrep -f "$1" | head -1 || true)
    if [ -n "$pid" ]; then echo "$pid"; return 0; fi
    sleep 1
  done
  return 1
}

EXE=tm1s.exe; [ "$ROLE" = admin ] && EXE=tm1admsrv.exe
if [ ! -x "$BIN/$EXE" ]; then
  cat >&2 <<MSG
[tm1-entrypoint] ERROR: $BIN/$EXE not found.

  tm1-server does not include IBM software. Mount your own licensed TM1 Server
  install directory (the one containing bin64/) at $TM1_HOME, e.g.

      -v /path/to/tm1_64:$TM1_HOME

  No Linux install yet? Unpack one from your IBM bundle with:

      docker run --rm -v /path/to/ibm-downloads:/ibm:ro \\
                      -v /path/to/tm1_64:$TM1_HOME tm1-server install
MSG
  exit 2
fi
echo "$ROLE" > /tmp/tm1-role   # read by the healthcheck

# ---- admin role: only the Admin Server -------------------------------------
if [ "$ROLE" = admin ]; then
  mkdir -p "$LOGS"
  cd "$BIN"   # config.tm1admsrv.json and ./ssl are resolved relative to bin64
  log "Starting TM1 Admin Server (REST on 5895 http / 5898 https)"
  ./tm1admsrv.exe
  ADMIN_PID=$(daemon_pid '^\./tm1admsrv\.exe$') || { log "TM1 Admin Server did not start"; exit 1; }
  log "TM1 Admin Server running as PID $ADMIN_PID"
  stop_admin() {
    log "Stopping TM1 Admin Server"
    kill -TERM "$ADMIN_PID" 2>/dev/null || true
    while kill -0 "$ADMIN_PID" 2>/dev/null; do sleep 1; done
    log "Stopped."
    exit 0
  }
  trap stop_admin INT TERM
  # Sleep in the background + wait, so the trap fires immediately on a signal.
  while kill -0 "$ADMIN_PID" 2>/dev/null; do sleep 2 & wait $! || true; done
  log "TM1 Admin Server exited unexpectedly"
  exit 1
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
# Settings fall in three groups:
#   enforced    - the container depends on them; always set (each change is logged)
#   warned      - likely to break in a container; logged as warnings, left as they are
#   passthrough - everything else, used as-is
HTTP_PORT=8010; TM1_API_PORT=12345; CLIENT_MSG_PORT=12346

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
unset_cfg() {  # unset_cfg Key reason - comment out any active line for Key
  local key=$1 why=$2 cur
  cur=$(get_cfg "$key")
  grep -qiE "^[[:space:]]*${key}[[:space:]]*=" "$WORK" || return 0
  sed -i -E "s|^([[:space:]]*${key}[[:space:]]*=)|#\1|I" "$WORK"
  log "  ${key} removed  (was: ${cur})  [$why]"
}
warn() { log "  WARNING: $*"; WARNINGS=$((WARNINGS + 1)); }

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
apply_named UseSSL         TM1_USE_SSL     F
# Generic passthrough: TM1_CFG_Foo=bar -> Foo=bar
while IFS='=' read -r name value; do
  [[ $name == TM1_CFG_* ]] || continue
  set_cfg "${name#TM1_CFG_}" "$value" "$name"
done < <(env)

# Enforced: paths, ports and process settings the container depends on.
set_cfg DataBaseDirectory       "$DATA"            "container path"
set_cfg LoggingDirectory        "$LOGS"            "container path"
set_cfg RunningInBackground     "T"                "required in a container"
set_cfg HTTPPortNumber          "$HTTP_PORT"       "fixed container port; map it with -p <host>:$HTTP_PORT"
set_cfg PortNumber              "$TM1_API_PORT"    "fixed container port"
set_cfg ClientMessagePortNumber "$CLIENT_MSG_PORT" "fixed container port"
unset_cfg IPAddress   "would bind TM1 to an address that doesn't exist in the container"
unset_cfg IPAddressV4 "would bind TM1 to an address that doesn't exist in the container"
unset_cfg IPAddressV6 "would bind TM1 to an address that doesn't exist in the container"
# Admin Server: a separate one (TM1_ADMIN_HOST), or embedded in this container.
EMBED_ADMIN=${TM1_ADMIN_SERVER:-$([ -n "${TM1_ADMIN_HOST:-}" ] && echo F || echo T)}
if [ -n "${TM1_ADMIN_HOST:-}" ]; then
  set_cfg AdminHost "$TM1_ADMIN_HOST" "TM1_ADMIN_HOST"
elif [ "$EMBED_ADMIN" = "T" ]; then
  set_cfg AdminHost "localhost" "admin server runs in this container"
fi

# Warned: settings that probably won't work in a container. Left as they are.
WARNINGS=0
case "$(get_cfg IntegratedSecurityMode)" in
  4|5) warn "IntegratedSecurityMode=$(get_cfg IntegratedSecurityMode) uses IBM Cognos (CAM) security, which this container doesn't provide. Logins will fail unless a CAM server is reachable. Use 1 for native TM1 users." ;;
esac
for key in ServerCAMURI ClientCAMURI; do
  v=$(get_cfg "$key"); [ -n "$v" ] && warn "$key=$v must be reachable from inside the container."
done
for key in IndexStoreDirectory ODBCLibraryPath 'FileRetry\.FileSpec'; do
  v=$(get_cfg "$key")
  case "$v" in /*) [ -e "$v" ] || warn "${key//\\/}=$v doesn't exist in the container. Mount it, or point it under /tm1/data or /tm1/logs." ;; esac
done
v=$(get_cfg StartupChores); [ -n "$v" ] && warn "StartupChores=$v runs at startup; those chores must exist in the database."

# ---- write the config, or run from a corrected copy if it's read-only --------
CFG_CHANGED=F; { [ ! -f "$CFG" ] || ! cmp -s "$WORK" "$CFG"; } && CFG_CHANGED=T
LOG_MISSING=F; [ -f "$CONF/tm1s-log.properties" ] || LOG_MISSING=T
CAN_WRITE=T
if [ "$CFG_CHANGED" = T ]; then
  if [ -f "$CFG" ]; then [ -w "$CFG" ] || CAN_WRITE=F; else [ -w "$CONF" ] || CAN_WRITE=F; fi
fi
[ "$LOG_MISSING" = T ] && { [ -w "$CONF" ] || CAN_WRITE=F; }

RUN_CONF="$CONF"
if [ "$CAN_WRITE" = T ]; then
  if [ "$CFG_CHANGED" = T ]; then
    cat "$WORK" > "$CFG"   # write in place (keeps single-file bind mounts working)
    log "Wrote $CFG"
  else
    log "  (no changes - $CFG used as-is)"
  fi
  if [ "$LOG_MISSING" = T ]; then
    log "No tm1s-log.properties in $CONF - creating the default"
    cp "$DEFAULTS/tm1s-log.properties" "$CONF/"
  fi
else
  RUN_CONF=/tmp/tm1-config
  mkdir -p "$RUN_CONF"
  cp "$WORK" "$RUN_CONF/tm1s.cfg"
  if [ "$LOG_MISSING" = T ]; then cp "$DEFAULTS/tm1s-log.properties" "$RUN_CONF/"; else cp "$CONF/tm1s-log.properties" "$RUN_CONF/"; fi
  warn "$CONF is read-only, so TM1 runs from a corrected copy in $RUN_CONF. Your files are unchanged; settings changed through the REST API (StaticConfiguration) won't be saved."
fi
echo "$RUN_CONF" > /tmp/tm1-config-dir   # read by the healthcheck
SERVER_NAME=$(get_cfg ServerName); USE_SSL=$(get_cfg UseSSL)
rm -f "$WORK"
[ "$WARNINGS" -gt 0 ] && log "$WARNINGS warning(s) about tm1s.cfg; see above."

cd "$BIN"

# ---- admin server -----------------------------------------------------------
ADMIN_PID=
if [ "$EMBED_ADMIN" = "T" ]; then
  log "Starting TM1 Admin Server"
  # Writes tm1admsrv.log into bin64/ when the install is writable (skipped if mounted :ro).
  ./tm1admsrv.exe
  ADMIN_PID=$(daemon_pid '^\./tm1admsrv\.exe$') || log "WARNING: embedded Admin Server did not start"
fi

# ---- tm1 server -------------------------------------------------------------
# Stream the server message log (written by shared_memory_reader.exe) to stdout.
touch "$LOGS/tm1server.log"
tail -n 0 -F "$LOGS/tm1server.log" 2>/dev/null &
TAIL_PID=$!

log "Starting TM1 Server '$SERVER_NAME' (REST on ${HTTP_PORT:-none}, SSL=${USE_SSL:-F})"
# tm1s.exe daemonizes: the launcher exits 0 and the real server keeps running.
./tm1s.exe -w -z"$RUN_CONF"
TM1_PID=$(daemon_pid "^\./tm1s\.exe -w -z$RUN_CONF\$" || true)
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

#!/bin/bash
# cognos-server entrypoint. The container is a thin runtime shell around your own
# IBM Cognos Analytics.
#
# Where Cognos comes from (first match wins):
#   1. an install at /opt/ibm/cognos/analytics: a private image with Cognos built in,
#      or a volume already holding an install made by `install`
#   2. a repository zip, installed on first start: COGNOS_ZIP_URL (download),
#      COGNOS_ZIP, or the newest casrv-*-linuxi38664h.zip mounted at /ibm. It's
#      installed into /opt/ibm/cognos/analytics: mount a volume there to keep it.
#   3. `install -` with a zip piped in on stdin (docker run -i)
#
# Cognos writes into its install while it runs (configuration, data, temp, logs
# and Liberty's work area), so one install can't be shared read-only between
# containers. Use a private image for parallel containers.
#
# Content store (PostgreSQL):
#   COGNOS_CS_HOST unset  an embedded PostgreSQL in this container, its data in
#                         /cognos/postgres (mount a volume there to keep it)
#   COGNOS_CS_HOST set    an external PostgreSQL: COGNOS_CS_PORT, COGNOS_CS_NAME,
#                         COGNOS_CS_SCHEMA, COGNOS_CS_USER, COGNOS_CS_PASSWORD
#
# Users (an LDAP namespace for logons):
#   COGNOS_USERS          user:password[:group,group] entries, separated by spaces or
#                         newlines, e.g. "alice:pw1:Admins bob:pw2:Writers,Readers"
#   COGNOS_USERS_FILE     a file with more entries in the same format
#   With users, an LDAP directory runs in the container, Cognos gets a namespace
#   (COGNOS_NAMESPACE, default LDAP) and anonymous access is off (COGNOS_ANONYMOUS).
#
# OIDC (a second namespace, for interactive and token logons):
#   COGNOS_KEYCLOAK_URL   a Keycloak, as this container reaches it (e.g. http://keycloak:8080):
#                         a realm is built there from the users, and an OIDC namespace
#                         (COGNOS_OIDC_NAMESPACE, default OIDC) points at it
#   COGNOS_OIDC_DISCOVERY_URL, COGNOS_OIDC_CLIENT_ID, COGNOS_OIDC_CLIENT_SECRET
#                         or any OIDC provider, configured by hand
#   COGNOS_PUBLIC_URL     Cognos as browsers reach it (default http://localhost:9300)
#
# PAW and TM1 Web through Cognos:
#   COGNOS_PMHUB_URLS     their addresses in browsers, e.g. "http://localhost:8800": installs
#                         IBM's interoperability files (bi_interop.zip, from the TM1 bundle in
#                         /ibm) so logons through Cognos go back to them
#
# Other environment:
#   COGNOS_HOST           host name in the dispatcher URIs (default: the container's
#                         hostname). Others on the Docker network reach Cognos by it.
#   COGNOS_MAX_MEMORY     dispatcher JVM heap in MB (default 6144)
#
# Port 9300: the dispatcher, http://localhost:9300/bi/
#
# Commands:
#   (none) | start   run Cognos
#   install [SOURCE] install Cognos from a zip into /opt/ibm/cognos/analytics and
#                    exit; SOURCE is a URL, a file, or - for stdin (see install --help)
#   <anything else>  exec'd as-is (e.g. bash)
set -euo pipefail

banner() { cat /usr/local/lib/cognos-server/banner.txt 2>/dev/null || true; }

case "${1:-start}" in
  start) banner ;;
  install) shift; banner; exec /usr/local/bin/cognos-install "$@" ;;
  *) exec "$@" ;;
esac

COGNOS_HOME=${COGNOS_HOME:-/opt/ibm/cognos/analytics}
STATE="$COGNOS_HOME/.cognos-server"

log() { echo "[cognos-entrypoint] $*"; }
die() { echo "[cognos-entrypoint] ERROR: $*" >&2; exit 2; }

# ---- find or install Cognos -------------------------------------------------
have_zip() {
  [ -n "${COGNOS_ZIP_URL:-}" ] || [ -n "${COGNOS_ZIP:-}" ] && return 0
  compgen -G "${COGNOS_ZIP_DIR:-/ibm}/casrv-*-linuxi38664h.zip" >/dev/null
}
# Wait for an install another container may be running into a shared volume.
if [ -f "$COGNOS_HOME/.install.lock" ] && [ -w "$COGNOS_HOME/.install.lock" ]; then
  flock "$COGNOS_HOME/.install.lock" true
fi
installed() { [ -x "$COGNOS_HOME/bin64/cogconfig.sh" ] && [ -f "$STATE/install-key" ] && [ ! -f "$STATE/installing" ]; }
if [ -f "$STATE/installing" ] && ! have_zip; then
  die "the Cognos install in $COGNOS_HOME didn't finish. Provide the zip again (mount it at ${COGNOS_ZIP_DIR:-/ibm} or set COGNOS_ZIP_URL) to finish it, or use an empty volume."
fi
if ! installed && { [ -f "$STATE/installing" ] || [ ! -e "$COGNOS_HOME/bin64" ]; } && have_zip; then
  if mountpoint -q "$COGNOS_HOME"; then
    log "No Cognos install in the volume at $COGNOS_HOME - installing from the zip"
  else
    log "No Cognos install - installing from the zip into this container. It takes a"
    log "few minutes and 10 GB, every time a new container starts: mount a volume at"
    log "$COGNOS_HOME to keep the install, or build a private image (see the README)."
  fi
  /usr/local/bin/cognos-install --target "$COGNOS_HOME" || exit $?
fi

if [ ! -x "$COGNOS_HOME/bin64/cogconfig.sh" ]; then
  cat >&2 <<MSG
[cognos-entrypoint] ERROR: $COGNOS_HOME/bin64/cogconfig.sh not found.

  cognos-server does not include IBM software. Give it your own licensed Cognos
  Analytics (the casrv-<version>-linuxi38664h.zip repository) in one of these ways:

    Mount the folder with the zip (installed on first start):
      -v /path/to/ibm-downloads:/ibm:ro -v cognos:$COGNOS_HOME
    Download the zip (e.g. a pre-signed S3 link; installed on first start):
      -e COGNOS_ZIP_URL=https://... -v cognos:$COGNOS_HOME
    Pipe the zip into a volume, then mount that volume:
      cat casrv-*.zip | docker run -i --rm -v cognos:$COGNOS_HOME cognos-server install -
    Build a private image with Cognos inside (see examples/private-image).

  The volume at $COGNOS_HOME keeps the install (about 10 GB) between containers.
MSG
  exit 2
fi
installed || die "$COGNOS_HOME holds a Cognos install this image didn't make (no $STATE/install-key), e.g. one from IBM's installer. Install into an empty volume with this image's install command instead."

# ---- content store ------------------------------------------------------------
EMBEDDED_PG=F
if [ -z "${COGNOS_CS_HOST:-}" ]; then
  EMBEDDED_PG=T
  /usr/local/bin/cognos-postgres start
  export COGNOS_CS_HOST=127.0.0.1 COGNOS_CS_PORT=5432
else
  port=${COGNOS_CS_PORT:-5432}
  log "Using the PostgreSQL content store at $COGNOS_CS_HOST:$port (database ${COGNOS_CS_NAME:-cm})"
  for i in $(seq 1 ${COGNOS_CS_WAIT:-60}); do
    (exec 3<>"/dev/tcp/$COGNOS_CS_HOST/$port") 2>/dev/null && break
    [ "$i" = "${COGNOS_CS_WAIT:-60}" ] && die "can't reach PostgreSQL at $COGNOS_CS_HOST:$port"
    sleep 1
  done
fi

stop_pg() { [ "$EMBEDDED_PG" = T ] && /usr/local/bin/cognos-postgres stop; [ "$EMBEDDED_LDAP" = T ] && /usr/local/bin/cognos-ldap stop; true; }

# ---- users: an embedded LDAP directory and namespace -----------------------------
EMBEDDED_LDAP=F
if [ -n "${COGNOS_USERS:-}" ] || [ -n "${COGNOS_USERS_FILE:-}" ]; then
  ldap_env=$(/usr/local/bin/cognos-ldap start) || { stop_pg; die "couldn't start the LDAP directory"; }
  EMBEDDED_LDAP=T
  while IFS='=' read -r key value; do export "$key=$value"; done <<<"$ldap_env"
  log "Users log on through namespace ${COGNOS_NAMESPACE:-LDAP}; anonymous access $([ "${COGNOS_ANONYMOUS:-F}" = T ] && echo on || echo off)"
fi
if [ -n "${COGNOS_KEYCLOAK_URL:-}" ]; then
  oidc_env=$(python3 /usr/local/lib/cognos-server/keycloak-setup.py) || { stop_pg; die "couldn't set up the Keycloak realm"; }
  while IFS='=' read -r key value; do export "$key=$value"; done <<<"$oidc_env"
fi
if [ -n "${COGNOS_OIDC_DISCOVERY_URL:-}" ]; then
  log "OIDC namespace ${COGNOS_OIDC_NAMESPACE:-OIDC} against ${COGNOS_OIDC_DISCOVERY_URL%%/.well-known*}; browsers return to ${COGNOS_PUBLIC_URL:-http://localhost:9300}"
fi

# ---- PAW and TM1 Web logons through Cognos -----------------------------------------
if [ -n "${COGNOS_PMHUB_URLS:-}" ]; then
  # shellcheck disable=SC2086  # a list of URLs
  python3 /usr/local/lib/cognos-server/bi-interop.py "${COGNOS_ZIP_DIR:-/ibm}" "$COGNOS_HOME" $COGNOS_PMHUB_URLS \
    || { stop_pg; die "couldn't install the interoperability files"; }
fi

# ---- configuration --------------------------------------------------------------
cd "$COGNOS_HOME"
if [ -e configuration/cogstartup.lock ]; then
  # Left behind when a container is killed while Cognos Configuration runs; with it
  # there, cogconfig refuses to start ("locked by another instance").
  log "Removing a stale configuration lock"
  rm -f configuration/cogstartup.lock
fi
python3 /usr/local/lib/cognos-server/configure-cognos.py render "$STATE/cogstartup.xml" configuration/cogstartup.xml >/dev/null \
  || { stop_pg; die "couldn't write configuration/cogstartup.xml"; }
log "Configured: host ${COGNOS_HOST:-$(hostname)}, dispatcher heap ${COGNOS_MAX_MEMORY:-6144} MB"

# ---- start ----------------------------------------------------------------------
# Show the application server's errors (and its "ready" line) while it starts and runs.
mkdir -p logs
touch logs/p2pd_messages.log
tail -n 0 -F logs/p2pd_messages.log 2>/dev/null | grep --line-buffered -E ' E |CWWKF0011I' &
TAIL_PID=$!
cleanup() { kill "$TAIL_PID" 2>/dev/null || true; }

STARTING=
abort_start() {
  log "Stopping during startup"
  if [ -n "$STARTING" ]; then
    # cogconfig.sh is a wrapper: stop its Java too, which holds the configuration lock.
    pkill -TERM -P "$STARTING" 2>/dev/null || true
    kill -TERM "$STARTING" 2>/dev/null || true
    wait "$STARTING" 2>/dev/null || true
    rm -f configuration/cogstartup.lock
  fi
  ./bin64/cogconfig.sh -stop >/dev/null 2>&1 || true
  stop_pg; cleanup; exit 0
}
trap abort_start INT TERM

log "Starting Cognos (this takes a few minutes; healthy once http://localhost:9300/bi/ answers)"
# cogconfig -s applies the configuration, starts the services and returns once
# they're up. Run in the background, so a stop signal is handled straight away.
./bin64/cogconfig.sh -s > logs/cognos-server-start.log 2>&1 &
STARTING=$!
rc=0; wait "$STARTING" || rc=$?
STARTING=
if [ "$rc" != 0 ]; then
  log "Cognos Configuration failed (exit $rc):"
  grep -E '^(ERROR|WARNING)' logs/cogconfig_response.csv >&2 || tail -20 logs/cogconfig.log >&2 || true
  stop_pg; cleanup; exit 1
fi
PID=$(cat logs/wlp_cogbootstrap_service.pid 2>/dev/null || true)
[ -n "$PID" ] && kill -0 "$PID" 2>/dev/null || { log "Cognos didn't start (no bootstrap service running)"; stop_pg; cleanup; exit 1; }
log "Cognos running (bootstrap service PID $PID)"

running() { kill -0 "$PID" 2>/dev/null; }
shutdown() {
  log "Stopping Cognos"
  ./bin64/cogconfig.sh -stop >/dev/null 2>&1 || kill -TERM "$PID" 2>/dev/null || true
  while running; do sleep 1; done
  stop_pg; cleanup
  log "Stopped."
  exit 0
}
trap shutdown INT TERM

# Sleep in the background + wait, so the trap fires immediately on a signal.
while running; do sleep 2 & wait $! || true; done
log "Cognos exited unexpectedly - last lines of cognosserver.log:"
tail -30 logs/cognosserver.log || true
stop_pg; cleanup
exit 1

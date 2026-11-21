#!/bin/bash
# tm1-web entrypoint. The container is a thin runtime shell around your own TM1 Web.
#
# Where TM1 Web comes from (first match wins):
#   1. a TM1 Web install at /opt/ibm/cognos/tm1web: a mounted folder, a named volume
#      already holding an install, or a private image with TM1 Web built in
#   2. a bundle, installed on first start: TM1_WEB_BUNDLE_URL (download),
#      TM1_WEB_BUNDLE, or the newest tm1web bundle mounted at /ibm. An empty volume
#      mounted at /opt/ibm/cognos/tm1web is installed into directly; otherwise the
#      install goes to a cache in /tm1-web/cache (mount a volume there to keep it
#      between containers), one folder per bundle.
#   3. `install -` with a bundle piped in on stdin (docker run -i)
#
# The install is only read, so it can be mounted read-only and shared. Each start
# builds a runtime folder (/tm1-web/runtime) holding this container's Liberty server
# config and a copy of the tm1web app, and writes the settings below into it.
#
# Optional mounts (unmounted ones live inside the container and are removed with it):
#   /tm1-web/config          tm1web_config.xml to start from (default: the install's)
#   /tm1-web/logs            tm1web/tm1web.log, server/ (Liberty and evalservice logs)
#
# Port inside the container is fixed; map it to any host port with -p:
#   9510 TM1 Web: /tm1web/ and /evalservice/
#
# Commands:
#   (none) | start   run TM1 Web
#   install [SOURCE] install TM1 Web from a bundle into /opt/ibm/cognos/tm1web and
#                    exit; SOURCE is a URL, a file, or - for stdin (tm1-web-install --help)
#   <anything else>  exec'd as-is (e.g. bash)
#
# Environment (all optional):
#   TM1_ADMIN_HOST       Admin Server TM1 Web lists TM1 servers from (AdminHostName).
#                        Unset: users type it on the login page.
#   TM1_ADMIN_PORT       its port, if not the default (AdminHostPort)
#   TM1_WEB_SERVER_NAME  log on to this TM1 server without asking (TM1ServerName)
#   TM1_WEB_CFG_<Key>    any tm1web_config.xml appSettings key, e.g.
#                        TM1_WEB_CFG_HttpSessionTimeout=30
#   TM1_WEB_HEAP         maximum Java heap, e.g. 1g or 2048m     (default: 1g)
#   TM1_WEB_JAVA_OPTS    extra JVM options, space-separated, added to jvm.options
set -euo pipefail

# Who made this image, at the top of every start (and install)
banner() { cat /usr/local/lib/tm1-web/banner.txt 2>/dev/null || true; }

case "${1:-start}" in
  start) banner ;;
  install) shift; banner; exec /usr/local/bin/tm1-web-install "$@" ;;
  *) exec "$@" ;;
esac

TM1_WEB_HOME=${TM1_WEB_HOME:-/opt/ibm/cognos/tm1web}
CONF=${TM1_WEB_CONFIG:-/tm1-web/config}
LOGS=${TM1_WEB_LOGS:-/tm1-web/logs}
RUN=/tm1-web/runtime
LIB=/usr/local/lib/tm1-web

log() { echo "[tm1-web-entrypoint] $*"; }
die() { echo "[tm1-web-entrypoint] ERROR: $*" >&2; exit 2; }

installed() { [ -x "$TM1_WEB_HOME/wlp/bin/server" ] && [ -d "$TM1_WEB_HOME/webapps/tm1web" ]; }

# ---- find or install TM1 Web --------------------------------------------------
have_bundle() {
  [ -n "${TM1_WEB_BUNDLE_URL:-}" ] || [ -n "${TM1_WEB_BUNDLE:-}" ] && return 0
  compgen -G "${TM1_WEB_BUNDLE_DIR:-/ibm}/tm1web-*_bundle.tar.gz" >/dev/null \
    || compgen -G "${TM1_WEB_BUNDLE_DIR:-/ibm}/tm1web-*-linuxi38664h.zip" >/dev/null
}
if ! installed && have_bundle; then
  if mountpoint -q "$TM1_WEB_HOME"; then
    # A volume (or folder) is mounted there without an install: install into it, so
    # it's filled once and reused.
    log "No TM1 Web install in the volume at $TM1_WEB_HOME - installing from the bundle"
    /usr/local/bin/tm1-web-install --target "$TM1_WEB_HOME" || exit $?
  else
    CACHE=${TM1_WEB_CACHE:-/tm1-web/cache}
    key=$(/usr/local/bin/tm1-web-install --print-key) || exit $?
    /usr/local/bin/tm1-web-install --target "$CACHE/$key" || exit $?
    rm -rf "$TM1_WEB_HOME"
    mkdir -p "$(dirname "$TM1_WEB_HOME")"
    ln -s "$CACHE/$key" "$TM1_WEB_HOME"
    log "Using TM1 Web from $CACHE/$key"
  fi
fi

if ! installed; then
  cat >&2 <<MSG
[tm1-web-entrypoint] ERROR: no TM1 Web install at $TM1_WEB_HOME (wlp/bin/server not found).

  tm1-web does not include IBM software. Give it your own licensed TM1 Web in
  one of these ways:

    Mount an installed TM1 Web (the folder with wlp/, jre/ and webapps/):
      -v /path/to/tm1web:$TM1_WEB_HOME:ro
    Mount the folder with your IBM bundle (installed on first start):
      -v /path/to/ibm-downloads:/ibm:ro
    Download the bundle (e.g. a pre-signed S3 link; installed on first start):
      -e TM1_WEB_BUNDLE_URL=https://...
    Pipe a bundle into a volume, then mount that volume:
      cat bundle.tar.gz | docker run -i --rm -v tm1web:$TM1_WEB_HOME tm1-web install -

  To keep a bundle install between containers, also mount a volume at /tm1-web/cache.
MSG
  exit 2
fi
log "TM1 Web $(sed -n 's/^VersionNum=//p' "$TM1_WEB_HOME"/tm1javaweb-*.txt 2>/dev/null | head -1) from $(readlink -f "$TM1_WEB_HOME")"

# ---- runtime folder -------------------------------------------------------------
# Mirrors the install's layout, so the relative paths in IBM's config resolve:
#   wlp/usr/servers/tm1web   Liberty server config (WLP_USER_DIR=$RUN/wlp/usr)
#   webapps/                 tm1web (copied: its config is inside it) and evalservice.war
#   bin64 -> install         SSL truststore (jvm.options: ../../../../bin64/ssl/tm1store)
# Rebuilt when the install changes; the config files are rewritten on every start.
SERVER="$RUN/wlp/usr/servers/tm1web"
APP="$RUN/webapps/tm1web"
SOURCE_ID="$(readlink -f "$TM1_WEB_HOME"):$(cat "$TM1_WEB_HOME/.tm1-web-install" 2>/dev/null || stat -c %Y "$TM1_WEB_HOME/webapps/tm1web")"
if [ "$(cat "$RUN/.source" 2>/dev/null)" != "$SOURCE_ID" ]; then
  log "Preparing the runtime folder $RUN"
  rm -rf "$RUN"
  mkdir -p "$RUN/wlp/usr/servers" "$RUN/webapps"
  cp -a "$TM1_WEB_HOME/wlp/usr/servers/tm1web" "$RUN/wlp/usr/servers/"
  cp -a "$TM1_WEB_HOME/webapps/tm1web" "$RUN/webapps/"
  ln -s "$TM1_WEB_HOME/webapps/evalservice.war" "$RUN/webapps/evalservice.war"
  ln -s "$TM1_WEB_HOME/bin64" "$RUN/bin64"
  # Keep pristine copies of the files rewritten below, to start from on each start
  cp "$SERVER/jvm.options" "$SERVER/jvm.options.install"
  cp "$APP/WEB-INF/configuration/tm1web_config.xml" "$APP/WEB-INF/configuration/tm1web_config.xml.install"
  echo "$SOURCE_ID" > "$RUN/.source"
fi
# Logs: tm1web.log is written into the app (WEB-INF/logs); Liberty and evalservice
# write into the server folder (logs/). Both go to $LOGS.
mkdir -p "$LOGS/tm1web" "$LOGS/server"
rm -rf "$APP/WEB-INF/logs" "$SERVER/logs"
ln -s "$LOGS/tm1web" "$APP/WEB-INF/logs"
ln -s "$LOGS/server" "$SERVER/logs"

# ---- tm1web_config.xml ----------------------------------------------------------
WEB_CFG="$APP/WEB-INF/configuration/tm1web_config.xml"
if [ -f "$CONF/tm1web_config.xml" ]; then
  log "Using tm1web_config.xml from $CONF"
  cp "$CONF/tm1web_config.xml" "$WEB_CFG"
else
  cp "$WEB_CFG.install" "$WEB_CFG"
fi
log "Applying settings:"
python3 "$LIB/configure-tm1-web.py" "$WEB_CFG"
[ -n "${TM1_ADMIN_HOST:-}" ] || grep -qE 'key="AdminHostName"[[:space:]]+value="[^"]+' "$WEB_CFG" \
  || log "  TM1_ADMIN_HOST is not set: users type the Admin Server's host on the login page"

# ---- jvm.options ----------------------------------------------------------------
# IBM's defaults (-Xms4096m -Xmx4096m -Xmn2048m) are sized for a shared server. In a
# container the heap is TM1_WEB_HEAP, and the JVM sizes the nursery itself.
HEAP=${TM1_WEB_HEAP:-1g}
[[ $HEAP =~ ^[0-9]+[kKmMgG]?$ ]] || die "TM1_WEB_HEAP=$HEAP: use a size like 1g or 1536m"
grep -vE '^-Xm[xsn]' "$SERVER/jvm.options.install" > "$SERVER/jvm.options"
printf '%s\n' "" "# tm1-web: heap from TM1_WEB_HEAP" "-Xmx$HEAP" "-Xms256m" \
  "# tm1-web: keep logback's own start-up chatter (evalservice) out of the console" \
  "-Dlogback.statusListenerClass=ch.qos.logback.core.status.NopStatusListener" >> "$SERVER/jvm.options"
log "  Java heap: -Xmx$HEAP"
if [ -n "${TM1_WEB_JAVA_OPTS:-}" ]; then
  echo "# tm1-web: TM1_WEB_JAVA_OPTS" >> "$SERVER/jvm.options"
  read -ra extra <<<"$TM1_WEB_JAVA_OPTS"
  printf '%s\n' "${extra[@]}" >> "$SERVER/jvm.options"
  log "  Extra JVM options: $TM1_WEB_JAVA_OPTS"
fi

# ---- start ------------------------------------------------------------------------
# Stream TM1 Web's own log to stdout; Liberty's console already goes there.
touch "$LOGS/tm1web/tm1web.log"
tail -n 0 -F "$LOGS/tm1web/tm1web.log" 2>/dev/null &

export JAVA_HOME="$TM1_WEB_HOME/jre" WLP_USER_DIR="$RUN/wlp/usr"
log "Starting TM1 Web on port 9510 (http://localhost:9510/tm1web/ inside the container)"
# `server run` keeps Liberty in the foreground and execs Java, so tini's SIGTERM
# reaches the JVM, and Liberty stops cleanly.
exec "$TM1_WEB_HOME/wlp/bin/server" run tm1web

#!/bin/sh
# Healthy once the REST API answers.
# - server role: the TM1 REST API (200 or 401 without credentials). Port and
#   scheme come from the tm1s.cfg that is actually running.
# - admin role: the Admin Server's REST API lists servers on port 5895.
if [ "$(cat /tmp/tm1-role 2>/dev/null)" = admin ]; then
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:5895/api/v1/Servers")
  [ "$code" = 200 ] && exit 0 || exit 1
fi
cfg=${TM1_CONFIG:-/tm1/config}/tm1s.cfg
val() { grep -iE "^[[:space:]]*$1[[:space:]]*=" "$cfg" | tail -1 | cut -d= -f2- | tr -d '\r '; }
port=$(val HTTPPortNumber); [ -n "$port" ] || exit 1
scheme=https; case "$(val UseSSL)" in F|f|FALSE|false|False) scheme=http ;; esac
code=$(curl -sk -o /dev/null -w '%{http_code}' "$scheme://localhost:$port/api/v1/Configuration/ProductVersion/\$value")
case "$code" in 200|401) exit 0 ;; *) exit 1 ;; esac

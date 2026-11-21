#!/bin/sh
# Healthy once the REST API answers.
# - server role: GET /api/v1/$metadata on the REST API's fixed container port 8010
#   returns 200. TM1 serves $metadata without a login, so a 200 means the REST API is
#   really up (no credentials needed, and a 401 doesn't count as healthy). The scheme
#   comes from UseSSL in the tm1s.cfg TM1 runs from.
# - admin role: the Admin Server's REST API lists servers on port 5895.
if [ "$(cat /tmp/tm1-role 2>/dev/null)" = admin ]; then
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:5895/api/v1/Servers")
  [ "$code" = 200 ] && exit 0 || exit 1
fi
cfg="$(cat /tmp/tm1-config-dir 2>/dev/null || echo /tm1/config)/tm1s.cfg"
ssl=$(grep -iE '^[[:space:]]*UseSSL[[:space:]]*=' "$cfg" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\r ')
scheme=https; case "$ssl" in F|f|FALSE|false|False) scheme=http ;; esac
code=$(curl -sk -o /dev/null -w '%{http_code}' "$scheme://localhost:8010/api/v1/\$metadata")
[ "$code" = 200 ] && exit 0 || exit 1

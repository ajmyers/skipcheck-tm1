#!/bin/sh
# Healthy once Cognos answers GET /bi/v1/login with 200. That needs no login: with
# anonymous access it returns a session, and with a namespace it returns the logon
# prompt. While Cognos is starting it refuses the connection or returns 503.
code=$(curl -s -m 8 -o /dev/null -w '%{http_code}' "http://localhost:9300/bi/v1/login")
[ "$code" = 200 ] && exit 0 || exit 1

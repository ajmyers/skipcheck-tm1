#!/bin/sh
# SPDX-License-Identifier: Apache-2.0

# Healthy once Cognos answers GET /bi/v1/login, which needs no login:
#   200  anonymous access is on, and it returned a session
#   441  a namespace is configured, and it returned the logon prompt
#        (Cognos's "camAuthUserRecoverable")
# While Cognos is starting it refuses the connection or returns 503. A 403 means
# the namespace failed to load (see logs/cognosserver.log for CAM-AAA errors).
code=$(curl -s -m 8 -o /dev/null -w '%{http_code}' "http://localhost:9300/bi/v1/login")
case "$code" in 200|441) exit 0 ;; *) exit 1 ;; esac

#!/bin/sh
# SPDX-License-Identifier: Apache-2.0

# Healthy once TM1 Web's login page answers 200 on its fixed container port.
code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:9510/tm1web/")
[ "$code" = 200 ] && exit 0 || exit 1

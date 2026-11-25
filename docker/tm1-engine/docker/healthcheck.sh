#!/bin/sh
# SPDX-License-Identifier: Apache-2.0

# Healthy once the entrypoint has finished setting up (instances, applications,
# databases, users) and every database it was asked for is ready.
exec /usr/local/bin/tm1-engine health

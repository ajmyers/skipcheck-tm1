#!/bin/sh
# SPDX-License-Identifier: Apache-2.0

# Stands in for mongod when TM1 runs its own (no TM1_DATASTORE_URL). TM1 starts MongoDB
# with --wiredTigerCacheSizeGB 16 whatever the machine, and has no setting for it. This
# puts TM1_DATASTORE_CACHE_GB (default 0.5) there instead, and runs the real mongod.
real="$(dirname "$0")/mongod.bin"
cache=${TM1_DATASTORE_CACHE_GB:-0.5}
n=$#
while [ "$n" -gt 0 ]; do
  arg=$1; shift; n=$((n - 1))
  if [ "$arg" = "--wiredTigerCacheSizeGB" ] && [ "$n" -gt 0 ]; then
    shift; n=$((n - 1))
    set -- "$@" "$arg" "$cache"
  else
    set -- "$@" "$arg"
  fi
done
exec "$real" "$@"

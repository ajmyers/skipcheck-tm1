#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

# cognos-postgres: the PostgreSQL content store that runs inside the container when
# no external one is configured (COGNOS_CS_HOST unset).
#
#   cognos-postgres start   create the cluster on first use, start it on 127.0.0.1:5432,
#                           and make sure the content store role and database exist
#   cognos-postgres stop    fast shutdown (waits until it has stopped)
#
# Environment:
#   COGNOS_PG_DATA      cluster directory (default /cognos/postgres); mount a volume
#                       there to keep the content store
#   COGNOS_CS_NAME      database (default cm)
#   COGNOS_CS_USER      role (default cognos)
#   COGNOS_CS_PASSWORD  its password (default cognos)
set -euo pipefail

PGDATA=${COGNOS_PG_DATA:-/cognos/postgres}
PGBIN=$(ls -d /usr/lib/postgresql/*/bin | sort -V | tail -1)
DB=${COGNOS_CS_NAME:-cm}
ROLE=${COGNOS_CS_USER:-cognos}
PASSWORD=${COGNOS_CS_PASSWORD:-cognos}

log() { echo "[cognos-postgres] $*"; }
die() { echo "[cognos-postgres] ERROR: $*" >&2; exit 2; }
for name in "$DB" "$ROLE"; do
  [[ $name =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || die "'$name' isn't a plain SQL name (COGNOS_CS_NAME / COGNOS_CS_USER: letters, digits, _)"
done
as_postgres() { runuser -u postgres -- "$@"; }
# SQL goes in on stdin, with names and the password as psql variables: the password
# is read from the environment (\getenv), so it never shows in ps.
sql() { as_postgres env COGNOS_CS_PASSWORD="$PASSWORD" "$PGBIN/psql" -h /tmp -p 5432 -U postgres -d postgres \
          -v ON_ERROR_STOP=1 -v role="$ROLE" -v db="$DB" -tAq; }

case "${1:-}" in
  start)
    mkdir -p "$PGDATA"
    chown postgres:postgres "$PGDATA"
    chmod 700 "$PGDATA"
    if [ ! -s "$PGDATA/PG_VERSION" ]; then
      log "Creating the content store database cluster in $PGDATA"
      # Local socket: trust (only root and postgres can reach it). TCP: password.
      as_postgres "$PGBIN/initdb" -D "$PGDATA" -U postgres -E UTF8 --locale=C.UTF-8 \
        --auth-local=trust --auth-host=scram-sha-256 >/dev/null
    fi
    # Left behind if the container was killed. (Never share this folder between
    # containers: this would let a second PostgreSQL start on a live cluster.)
    rm -f "$PGDATA/postmaster.pid"
    log "Starting PostgreSQL on 127.0.0.1:5432"
    as_postgres "$PGBIN/pg_ctl" -D "$PGDATA" -l "$PGDATA/postgres.log" -w -t 120 \
      -o "-c listen_addresses=127.0.0.1 -c port=5432 -c unix_socket_directories=/tmp" start >/dev/null
    # Create the role and database if they're missing, and keep the password in step
    # with COGNOS_CS_PASSWORD, even on an existing cluster.
    sql <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'role') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role') \gexec
\getenv pw COGNOS_CS_PASSWORD
ALTER ROLE :"role" PASSWORD :'pw';
SELECT format('CREATE DATABASE %I OWNER %I ENCODING %L', :'db', :'role', 'UTF8') WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db') \gexec
SQL
    log "PostgreSQL ready (database $DB, user $ROLE)"
    ;;
  stop)
    if [ -f "$PGDATA/postmaster.pid" ]; then
      log "Stopping PostgreSQL"
      as_postgres "$PGBIN/pg_ctl" -D "$PGDATA" -m fast -w -t 60 stop >/dev/null || true
    fi
    ;;
  *) echo "usage: cognos-postgres start|stop" >&2; exit 2 ;;
esac

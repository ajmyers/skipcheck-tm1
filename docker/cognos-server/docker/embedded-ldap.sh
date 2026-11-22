#!/bin/bash
# cognos-ldap: the LDAP directory that runs inside the container when test users are
# given (COGNOS_USERS / COGNOS_USERS_FILE). Cognos logs users on against it through
# its LDAP namespace.
#
#   cognos-ldap start   build the directory from the user list (from scratch, every
#                       time: the list is the source of truth) and start slapd on
#                       127.0.0.1:389; prints the settings for the namespace
#   cognos-ldap stop
#
# Environment:
#   COGNOS_USERS        user:password[:group,group] entries, separated by whitespace
#   COGNOS_USERS_FILE   a file with more entries in the same format (# comments)
#   COGNOS_LDAP_BASE    base DN (default dc=cognos,dc=local)
set -euo pipefail

DIR=/run/cognos-ldap
BASE=${COGNOS_LDAP_BASE:-dc=cognos,dc=local}
LIB=/usr/local/lib/cognos-server

log() { echo "[cognos-ldap] $*" >&2; }
die() { echo "[cognos-ldap] ERROR: $*" >&2; exit 2; }

case "${1:-}" in
  start)
    [[ $BASE =~ ^dc=[A-Za-z0-9-]+(,dc=[A-Za-z0-9-]+)*$ ]] || die "COGNOS_LDAP_BASE must look like dc=example,dc=com, not '$BASE'"
    rm -rf "$DIR"; mkdir -p "$DIR/db"
    ldif=$(python3 "$LIB/users.py" "$BASE") || die "couldn't read the user list"
    # A fresh admin password every start: only Cognos (bind for searches) uses it.
    admin_pw=$(head -c 18 /dev/urandom | base64 | tr -d '/+=')
    cat > "$DIR/slapd.conf" <<CONF
include /etc/ldap/schema/core.schema
include /etc/ldap/schema/cosine.schema
include /etc/ldap/schema/inetorgperson.schema
pidfile $DIR/slapd.pid
modulepath /usr/lib/ldap
moduleload back_mdb
database mdb
maxsize 104857600
suffix "$BASE"
rootdn "cn=admin,$BASE"
rootpw $(slappasswd -s "$admin_pw")
directory $DIR/db
access to attrs=userPassword by self read by anonymous auth by * none
access to * by * read
CONF
    printf '%s\n' "$ldif" | slapadd -q -f "$DIR/slapd.conf" >/dev/null || die "couldn't load the users into the directory"
    chown -R openldap:openldap "$DIR"
    slapd -f "$DIR/slapd.conf" -h "ldap://127.0.0.1:389/" -u openldap -g openldap || die "slapd didn't start"
    for _ in $(seq 1 30); do
      ldapsearch -x -H ldap://127.0.0.1:389 -b "$BASE" -s base dn >/dev/null 2>&1 && break
      sleep 0.5
    done
    log "LDAP directory ready on 127.0.0.1:389 ($BASE)"
    # For the entrypoint: the settings configure-cognos.py reads.
    printf 'COGNOS_LDAP_HOST=127.0.0.1\nCOGNOS_LDAP_PORT=389\nCOGNOS_LDAP_BASE=%s\nCOGNOS_LDAP_BIND_DN=cn=admin,%s\nCOGNOS_LDAP_BIND_PASSWORD=%s\n' \
      "$BASE" "$BASE" "$admin_pw"
    ;;
  stop)
    if [ -f "$DIR/slapd.pid" ]; then
      kill "$(cat "$DIR/slapd.pid")" 2>/dev/null || true
    fi
    ;;
  *) echo "usage: cognos-ldap start|stop" >&2; exit 2 ;;
esac

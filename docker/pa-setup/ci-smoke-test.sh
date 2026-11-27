#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

# CI smoke test for the pa-setup image (run by .github/workflows/docker.yml). It needs
# no IBM software and starts nothing: it generates compose projects from example
# files, checks them with docker compose config, checks the wiring, and checks that
# bad files fail with a clear message.
#
# Usage: ci-smoke-test.sh <image>
set -euo pipefail
image=${1:?usage: ci-smoke-test.sh <image>}
here=$(cd "$(dirname "$0")" && pwd)
fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "ok - $*"; }

# 1. Shell scripts pass shellcheck
if command -v shellcheck >/dev/null; then
  shellcheck -S warning "$here"/setup.sh "$here"/ci-smoke-test.sh || fail "shellcheck"
  pass "shellcheck"
fi

# Runs the tool on a file, in a fresh folder; prints its output (and compose.yaml)
tool() {  # tool <yaml> <command...>
  local dir; dir=$(mktemp -d)
  printf '%s\n' "$1" > "$dir/pa-environment.yaml"; shift
  docker run --rm -v "$dir:/work" -e PA_HOST_DIR=/host/project -e PA_HOST_HOME=/home/me "$image" "$@" 2>&1 \
    && { [ ! -f "$dir/compose.yaml" ] || { echo "---"; cat "$dir/compose.yaml"; \
         docker run --rm -v "$dir:/work" --entrypoint docker "$image" compose -f /work/compose.yaml config -q \
           || echo "COMPOSE INVALID"; }; }
}

# 2. The example with everything generates a valid compose project with the wiring
out=$(tool "$(cat "$here/examples/everything.yaml")" generate) || fail "everything.yaml: $out"
! grep -q "COMPOSE INVALID" <<<"$out" || fail "everything.yaml: docker compose config refused it: $out"
for want in "  keycloak:" "  cognos:" "  tm1-admin:" "  planning:" "  reporting:" "  tm1-web:" \
  "TM1_ADMIN_HOST: tm1-admin" "TM1_CAM_URI: http://cognos:9300" "TM1_CAM_ADMINS: admin" \
  "TM1_CAM_GROUPS: Admins Analysts Readers" "TM1_SEED: sdata" "COGNOS_KEYCLOAK_URL: http://keycloak:8080" \
  "KC_HOSTNAME: http://localhost:8180" "/home/me/Downloads/ibm:/ibm:ro" "/host/project/pa-files/planning/data:/tm1/data" \
  "8011:8010" "condition: service_healthy"; do
  grep -qF -- "$want" <<<"$out" || fail "everything.yaml: compose.yaml lacks '$want': $out"
done
pass "examples/everything.yaml generates a valid, fully wired compose project"

# 2b. With PAW, Cognos hands browser logons back to PAW and TM1 Web
out=$(tool "$(sed 's/^# paw:/paw:/' "$here/examples/everything.yaml")" generate) || fail "everything with PAW: $out"
grep -qF "COGNOS_PMHUB_URLS: http://localhost:8800 http://localhost:9510" <<<"$out" || fail "no COGNOS_PMHUB_URLS: $out"
pass "with PAW and TM1 Web, Cognos hands logons back to them"

# 3. One server: its own Admin Server, no CAM
out=$(tool $'version: 1\nibm: ./ibm\ntm1: {servers: {tm1: {}}}' generate) || fail "one server: $out"
! grep -q "COMPOSE INVALID" <<<"$out" || fail "one server: invalid compose: $out"
grep -qF "/host/project/ibm:/ibm:ro" <<<"$out" || fail "one server: ibm not resolved against the folder: $out"
! grep -q "tm1-admin\|TM1_CAM" <<<"$out" || fail "one server: unexpected Admin Server or CAM: $out"
pass "one server uses its own Admin Server"

# 4. urls lists every service with its login
out=$(tool "$(cat "$here/examples/everything.yaml")" urls) || fail "urls: $out"
for want in "http://localhost:8010/api/v1/" "http://localhost:9510/tm1web/" "http://localhost:9300/bi/" \
  "http://localhost:8180/" "namespace LDAP: admin, analyst, viewer"; do
  grep -qF -- "$want" <<<"$out" || fail "urls lacks '$want': $out"
done
pass "urls lists every service"

# 4b. A TM1 v12 engine: its two services, valid compose, and what urls prints
out=$(tool "$(cat "$here/examples/tm1-engine.yaml")" generate) || fail "tm1-engine.yaml: $out"
! grep -q "COMPOSE INVALID" <<<"$out" || fail "tm1-engine.yaml: docker compose config refused it: $out"
for want in "  tm1-engine:" "  tm1-engine-mongo:" "  legacy:" "TM1_DATABASES: tm1/planning,tm1/sales" \
  "TM1_USERS: analyst:Analysts:Planners,viewer" "4444:4444" "/home/me/Downloads/ibm:/ibm:ro" \
  "/host/project/pa-files/tm1-engine/deployment:/tm1/deployment" \
  "/host/project/pa-files/tm1-engine/datastore:/data/db"; do
  grep -qF -- "$want" <<<"$out" || fail "tm1-engine.yaml: compose.yaml lacks '$want': $out"
done
out=$(tool $'version: 1\ntm1Engine: true' urls) || fail "engine urls: $out"
for want in "http://localhost:4444/manage/v1/" "http://localhost:4444/tm1/api/v1/Databases('tm1')/" \
  "/host/project/pa-files/tm1-engine/credentials/tm1/tm1.json" "setup.sh session tm1"; do
  grep -qF -- "$want" <<<"$out" || fail "engine urls lacks '$want': $out"
done
pass "examples/tm1-engine.yaml generates the engine and its MongoDB, and urls lists them"

# 4c. OAuth 2.0 logins: the engine's own Keycloak with its realm, in a compose project docker accepts
out=$(tool $'version: 1\ntm1Engine: {users: ["analyst:an$pw:Analysts"], oauth2: true}' generate) || fail "oauth2: $out"
! grep -q "COMPOSE INVALID" <<<"$out" || fail "oauth2: docker compose config refused it: $out"
for want in "  tm1-engine-keycloak:" "KC_HOSTNAME: http://tm1-engine-keycloak:8080" "8181:8080" \
  "TM1_OAUTH2_ISSUER_URL: http://tm1-engine-keycloak:8080/realms/tm1" "TM1_OAUTH2_AUDIENCE: tm1" \
  "target: /opt/keycloak/data/import/tm1-realm.json" "oidc-audience-mapper"; do
  grep -qF -- "$want" <<<"$out" || fail "oauth2: compose.yaml lacks '$want': $out"
done
out=$(tool $'version: 1\ntm1Engine: true' generate) || fail "no oauth2: $out"
grep -qF "TM1_OAUTH2_ISSUER_URL: none" <<<"$out" || fail "without oauth2 the engine isn't told it's off: $out"
pass "tm1Engine.oauth2 adds the engine's Keycloak and its realm"

# 4d. Pass-through logins: the engine is pointed at the TM1 11 server, and waits for it
out=$(tool $'version: 1\ntm1: {servers: {legacy: {seed: sdata}}}\ntm1Engine: {passthrough: {server: legacy}}' generate) || fail "passthrough: $out"
! grep -q "COMPOSE INVALID" <<<"$out" || fail "passthrough: docker compose config refused it: $out"
grep -qF "TM1_PASSTHROUGH_URL: http://legacy:8010/api/v1/" <<<"$out" || fail "passthrough: the engine isn't pointed at the server: $out"
out=$(tool $'version: 1\ntm1Engine: true' generate) || fail "no passthrough: $out"
grep -qF "TM1_PASSTHROUGH_URL: none" <<<"$out" || fail "without passthrough the engine isn't told it's off: $out"
out=$(tool $'version: 1\ncognos: {users: ["admin:adminpw:Admins"]}\ntm1: {servers: {legacy: {}}}\ntm1Engine: {passthrough: {server: legacy}}' generate) || fail "passthrough with cognos: $out"
! grep -q "COMPOSE INVALID" <<<"$out" || fail "passthrough with cognos: docker compose config refused it: $out"
# The TM1 server has the same line: the engine's is the second
[ "$(grep -cF "TM1_CAM_NAMESPACE: LDAP" <<<"$out")" -eq 2 ] || fail "passthrough with cognos: the engine isn't told the namespace: $out"
grep -qF "TM1_USERS: admin:ADMIN" <<<"$out" || fail "passthrough with cognos: the administrator isn't one in the engine: $out"
pass "tm1Engine.passthrough points the engine at a TM1 11 server of the file"

# 5. Bad files fail with exit 2 and say what's wrong
check_error() {  # check_error <yaml> <expected message>
  local rc=0 out
  out=$(tool "$1" generate) || rc=$?
  [ "$rc" -eq 2 ] && grep -qF -- "$2" <<<"$out" || fail "expected exit 2 and '$2', got $rc: $out"
}
check_error $'version: 2' "version: must be 1"
check_error $'version: 1\ntm1: {servers: {tm1: {prot: 1}}}' "unknown setting(s) prot"
check_error $'version: 1\ntm1: {servers: {a: {port: 8010}, b: {port: 8010}}}' "port 8010 is used twice"
check_error $'version: 1\ntm1: {servers: {tm1: {}}}\ncognos: {users: [bad user:pw]}' "user name"
check_error $'version: 1\ntm1: {servers: {tm1: {}}}\ncognos: {users: ["a:p"], cam: {namespace: oidc}}' "oidc needs cognos.oidc"
check_error $'version: 1\ntm1: {servers: {tm1: {}}}\ncognos: {users: ["a:p"], cam: {admins: [b]}}' "b isn't one of cognos.users"
check_error $'version: 1\nibm: ./ibm\ntm1: {servers: {tm1: {}}}\npaw: {}' "paw.acceptLicense"
check_error $'version: 1\ntm1Web: true' "tm1Web: needs a TM1 server"
check_error $'version: 1\ntm1Engine: {databases: {ab: {}}}' "at least 3 characters"
check_error $'version: 1\ntm1Engine: {login: oauth2}' "unknown setting(s) login"
check_error $'version: 1\ntm1Engine: {passthrough: {server: nosuch}}' "name one of tm1.servers"
check_error $'version: 1\ntm1: {servers: {tm1: {}}}\ncredentials: 1' "unknown setting(s) credentials"
check_error $'version: 1' "nothing to run"
# The engine's commands need an engine in the file, and say so
rc=0; out=$(tool $'version: 1\ntm1: {servers: {tm1: {}}}' credentials) || rc=$?
[ "$rc" -eq 2 ] && grep -qF "add tm1Engine to the file" <<<"$out" || fail "credentials without an engine: got $rc: $out"
# An option after the command is still an option
rc=0; out=$(tool $'version: 1\ntm1Engine: true' urls -f nosuch.yaml) || rc=$?
[ "$rc" -eq 2 ] && grep -qF "nosuch.yaml not found" <<<"$out" || fail "urls -f nosuch.yaml: got $rc: $out"
pass "bad files fail with a clear message"

# 6. init writes a starting file that generates
dir=$(mktemp -d)
docker run --rm -v "$dir:/work" "$image" init >/dev/null || fail "init failed"
docker run --rm -v "$dir:/work" -e PA_HOST_DIR=/host/p "$image" generate >/dev/null || fail "init's file doesn't generate"
pass "init writes a file that generates"

# 7. Starting from nothing: where TM1 comes from is all it takes, and nothing is started
start() {  # start <options...>: runs the tool with no file, and prints the folder it wrote in
  local dir; dir=$(mktemp -d)
  docker run --rm -v "$dir:/work" -e PA_HOST_DIR=/host/p -e PA_HOST_HOME=/home/me -e CI=true "$image" "$@" >"$dir/out" 2>&1 \
    || { cat "$dir/out" >&2; return 1; }
  echo "$dir"
}
# shellcheck disable=SC2088  # the tilde is the tool's to read, as the host's home
dir=$(start --bundle '~/Downloads/tm1server-0.0.0-1-linuxi38664h_bundle.tar.gz' --samples --no-start) || fail "--bundle"
grep -qx 'ibm: "~/Downloads"   # the folder holding your IBM downloads' "$dir/pa-environment.yaml" || fail "--bundle: ibm isn't the file's folder: $(cat "$dir/pa-environment.yaml")"
grep -qx 'TM1_BUNDLE=/ibm/tm1server-0.0.0-1-linuxi38664h_bundle.tar.gz' "$dir/.env" || fail "--bundle: the bundle isn't named in .env"
grep -qF "/home/me/Downloads:/ibm:ro" "$dir/compose.yaml" || fail "--bundle: the folder isn't mounted"
grep -qF "sdata:" "$dir/compose.yaml" || fail "--samples: no sample server"
dir=$(start --tm1-home /opt/ibm/tm1_64 --no-start) || fail "--tm1-home"
grep -qF "/opt/ibm/tm1_64:/opt/ibm/cognos/tm1_64" "$dir/compose.yaml" || fail "--tm1-home: the folder isn't the install: $(cat "$dir/compose.yaml")"
! grep -qF "tm1-install" "$dir/compose.yaml" || fail "--tm1-home: there's still a volume to install into"
dir=$(start --bundle /dl/tm1-v0.0.0-standalone-linux.tgz --no-start) || fail "--bundle with TM1 v12's package"
grep -qF "tm1-engine:" "$dir/compose.yaml" || fail "TM1 v12's package didn't make an engine"
dir=$(mktemp -d)
rc=0; out=$(docker run --rm -v "$dir:/work" -e CI=true "$image" 2>&1) || rc=$?
[ "$rc" -eq 2 ] && grep -qF "Say where TM1 comes from" <<<"$out" || fail "no file, nothing said: got $rc: $out"
rc=0; out=$(docker run --rm -v "$dir:/work" -e CI=true "$image" --bundle /dl/tm1server-0.0.0-1-win64_bundle.zip 2>&1) || rc=$?
[ "$rc" -eq 2 ] && grep -qF "isn't the Linux TM1 Server download" <<<"$out" || fail "the Windows download: got $rc: $out"
rc=0; out=$(docker run --rm -v "$dir:/work" -e CI=true "$image" --dir tm1 2>&1) || rc=$?
[ "$rc" -eq 2 ] && grep -qF "mkdir -p tm1 && cd tm1" <<<"$out" || fail "--dir: got $rc: $out"
docker run --rm "$image" --help | grep -qF -- "--bundle FILE" || fail "--help doesn't say how to start from nothing"
pass "starts from nothing with where TM1 comes from"

echo "All smoke tests passed."

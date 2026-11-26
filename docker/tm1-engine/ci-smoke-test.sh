#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

# CI smoke test for the tm1-engine image (run by .github/workflows/docker.yml).
# CI has no IBM binaries, so this can't start TM1. It checks that the image contains no
# IBM software or MongoDB, has what TM1 needs from the OS, fails cleanly with a helpful
# message when there's no TM1, and installs a stand-in package (a made-up installer
# with IBM's layout) the way it installs IBM's, and runs from start to stop on it, with
# test/fake_tm1sd.py standing in for TM1. The setup tool's logic is tested in
# scripts/tests/test_tm1_engine.py; real TM1 is tested by hand.
#
# Usage: ci-smoke-test.sh <image>
set -euo pipefail
image=${1:?usage: ci-smoke-test.sh <image>}
here=$(cd "$(dirname "$0")" && pwd)
fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "ok - $*"; }

run() { docker run --rm "$@"; }  # image is linux/amd64 (pinned in the Dockerfile)

# 1. Shell scripts pass shellcheck
if command -v shellcheck >/dev/null; then
  shellcheck -S warning "$here"/docker/*.sh "$here"/ci-smoke-test.sh || fail "shellcheck"
  pass "shellcheck"
else
  echo "skip - shellcheck not installed"
fi

# 2. No IBM software or MongoDB baked into the image
contents=$(run --entrypoint sh "$image" -c 'ls -A /opt/ibm/tm1-engine 2>/dev/null; find / -xdev \( -name "tm1sd.exe" -o -name "tm1s.exe" -o -name "mongod*" -o -name "*standalone*.run" \) ! -name "mongod-wrapper.sh" 2>/dev/null')
[ -z "$contents" ] || fail "image contains IBM or MongoDB files: $contents"
pass "image contains no IBM software and no MongoDB"

# 3. Runtime dependencies
run --entrypoint sh "$image" -c 'command -v tini >/dev/null && command -v python3 >/dev/null && command -v flock >/dev/null && command -v curl >/dev/null && command -v pkill >/dev/null' \
  || fail "missing runtime dependency (tini / python3 / flock / curl / procps)"
pass "runtime dependencies present"

# 4. Without a TM1 install: exits 2 and explains what to mount
set +e
out=$(run "$image" 2>&1); rc=$?
set -e
[ "$rc" -eq 2 ] || fail "expected exit 2 without a TM1 install, got $rc: $out"
grep -q "does not include IBM software" <<<"$out" || fail "missing help text: $out"
pass "start without TM1 install fails with help text"
grep -qF "$(sed -n 3p "$here/docker/banner.txt")" <<<"$out" || fail "startup log doesn't show docker/banner.txt: $out"
pass "startup log shows the banner"

# 5. install helper without a package: exits 2 with a clear error
set +e
out=$(run -v "$(mktemp -d):/ibm:ro" "$image" install 2>&1); rc=$?
set -e
[ "$rc" -eq 2 ] || fail "expected exit 2 from install without a package, got $rc: $out"
grep -q "no package found" <<<"$out" || fail "missing install error text: $out"
pass "install without a package fails with help text"

# 6. Package URL that can't be downloaded: exits 2, and never prints the query
#    string (pre-signed URLs carry credentials there)
set +e
out=$(run -e TM1_ENGINE_BUNDLE_URL='http://127.0.0.1:9/tm1-v12-standalone-linux.tgz?X-Amz-Signature=SECRET123' "$image" 2>&1); rc=$?
set -e
[ "$rc" -eq 2 ] || fail "expected exit 2 for an unreachable package URL, got $rc: $out"
grep -q "download failed: http://127.0.0.1:9/tm1-v12-standalone-linux.tgz" <<<"$out" || fail "missing download error: $out"
! grep -q "SECRET123" <<<"$out" || fail "package URL credentials were printed: $out"
pass "unreachable package URL fails cleanly without printing credentials"

# 7. A stand-in package with IBM's layout (a .tgz holding a Makeself installer) installs
#    as IBM's does: bin/, the deployment template, and the versions the installer names.
#    A damaged one is refused. A TM1 v11 bundle beside it is ignored.
make_package='
import hashlib, io, os, sys, tarfile
def tar_of(files, mode):
    data = io.BytesIO()
    with tarfile.open(fileobj=data, mode=mode) as tar:
        for name, content in files.items():
            info = tarfile.TarInfo(name); info.size = len(content); info.mode = 0o755
            tar.addfile(info, io.BytesIO(content))
    return data.getvalue()
payload = tar_of({
    "./installer.sh": b"#!/bin/bash\nVERSION=\"12.0.0\"\nexit 1\n",
    "./bin/tm1sd.exe": open("/fake/fake_tm1sd.py", "rb").read(),
    "./deployment/config.internal.json": b"{}",
    "./deployment/tls/ca/cert.pem": b"cert",
    "./scripts/datastore.sh": b"#!/bin/bash\nDS_VERSION=\"8.0.1\"\n",
}, "w:gz")
if sys.argv[2] == "damaged":
    payload = payload[:-8] + b"00000000"
lines = ["#!/bin/sh", "# stand-in for a Makeself installer", "SHA=\"%s\"" % hashlib.sha256(payload if sys.argv[2] != "damaged" else b"").hexdigest(), "skip=\"6\"", "exit 1", "eof"]
run = ("\n".join(lines) + "\n").encode() + payload
os.makedirs(sys.argv[1], exist_ok=True)
with open(os.path.join(sys.argv[1], "tm1-v12.0.0-standalone-linux.tgz"), "wb") as out:
    out.write(tar_of({"tm1-v12.0.0-standalone-installer-linux.run": run, "README.md": b"readme"}, "w:gz"))
open(os.path.join(sys.argv[1], "tm1server-9.9.9-1-linuxi38664h_bundle.tar.gz"), "wb").write(b"not ours")
# A stand-in MongoDB download too, with its checksum beside it as mongodb.org has
mongo = tar_of({"mongodb-linux/bin/mongod": b"#!/bin/sh\nexit 0\n", "mongodb-linux/LICENSE-Community.txt": b"license"}, "w:gz")
open(os.path.join(sys.argv[1], "mongodb.tgz"), "wb").write(mongo)
open(os.path.join(sys.argv[1], "mongodb.tgz.sha256"), "w").write(hashlib.sha256(mongo).hexdigest() + "  mongodb.tgz\n")
'
fake=(-v "$here/test:/fake:ro")
out=$(run "${fake[@]}" -e MAKE="$make_package" --entrypoint bash "$image" -c '
  set -e
  python3 -c "$MAKE" /ibm good
  tm1-engine-install
  cd /opt/ibm/tm1-engine
  test -x bin/tm1sd.exe && test -f deployment-template/config.internal.json && test -f deployment-template/tls/ca/cert.pem
  test ! -e installer.sh && test ! -e scripts
  echo "version=$(cat version.txt) datastore=$(cat datastore-version.txt)"
  tm1-engine-install 2>&1 | grep -q "already installed" && echo again-is-a-no-op
' 2>&1) || fail "installing a stand-in package failed: $out"
grep -q "version=12.0.0 datastore=8.0.1" <<<"$out" || fail "the install didn't record the versions: $out"
grep -q "again-is-a-no-op" <<<"$out" || fail "a second install wasn't a no-op: $out"
pass "a package is installed without running its installer, once"
set +e
out=$(run "${fake[@]}" -e MAKE="$make_package" --entrypoint bash "$image" -c 'python3 -c "$MAKE" /ibm damaged && tm1-engine-install' 2>&1); rc=$?
set -e
[ "$rc" -eq 2 ] && grep -q "damaged" <<<"$out" || fail "a damaged installer wasn't refused (exit $rc): $out"
pass "a damaged installer is refused"
set +e
out=$(run "${fake[@]}" -e MAKE="$make_package" --entrypoint bash "$image" -c 'python3 -c "$MAKE" /ibm good && mkdir -p /opt/ibm/tm1-engine && echo mine > /opt/ibm/tm1-engine/notes.txt && tm1-engine-install; echo "rc=$? kept=$(cat /opt/ibm/tm1-engine/notes.txt)"' 2>&1)
set -e
grep -q "rc=2 kept=mine" <<<"$out" && grep -q "isn't empty" <<<"$out" || fail "installing into a folder with files in it wasn't refused: $out"
pass "a folder with something else in it is never emptied"

# 8. What setup would create, from the environment
out=$(run -e TM1_DATABASES=planning,finance/sales -e TM1_USERS=analyst:Analysts "$image" tm1-engine plan) || fail "tm1-engine plan failed: $out"
for want in '"finance"' '"planning"' '"sales"' '"analyst"' '"Analysts"'; do
  grep -qF -- "$want" <<<"$out" || fail "tm1-engine plan lacks $want: $out"
done
set +e
out=$(run -e TM1_DATABASES=ab "$image" tm1-engine plan 2>&1); rc=$?
set -e
[ "$rc" -eq 2 ] && grep -q "at least 3 characters" <<<"$out" || fail "a short database name wasn't refused (exit $rc): $out"
pass "tm1-engine plan reads the environment, and refuses names TM1 would"

# 9. The MongoDB wrapper replaces TM1's 16 GB cache with TM1_DATASTORE_CACHE_GB
out=$(run --entrypoint sh "$image" -c '
  mkdir /tmp/ds && cp /usr/local/lib/tm1-engine/mongod-wrapper.sh /tmp/ds/mongod
  printf "#!/bin/sh\necho \"\$@\"\n" > /tmp/ds/mongod.bin && chmod 755 /tmp/ds/mongod /tmp/ds/mongod.bin
  /tmp/ds/mongod --port 27027 --dbpath "/a b" --auth --wiredTigerCacheSizeGB 16
  TM1_DATASTORE_CACHE_GB=2 /tmp/ds/mongod --wiredTigerCacheSizeGB 16 --auth')
[ "$out" = "--port 27027 --dbpath /a b --auth --wiredTigerCacheSizeGB 0.5
--wiredTigerCacheSizeGB 2 --auth" ] || fail "the mongod wrapper changed the arguments wrongly: $out"
pass "the MongoDB wrapper holds the cache to TM1_DATASTORE_CACHE_GB"

# 10. From start to stop, with the stand-in for TM1 in the package: a new deployment, setup,
#     health, a login, a clean stop, and a second start that creates nothing again
name=tm1-engine-smoke-$$
packages=tm1-engine-smoke-packages-$$
cleanup() { docker rm -f "$name" "$name-own" >/dev/null 2>&1 || true; docker volume rm "$packages" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker volume create "$packages" >/dev/null
run "${fake[@]}" -v "$packages:/ibm" -e MAKE="$make_package" --entrypoint python3 "$image" -c 'import os; exec(os.environ["MAKE"])' /ibm good \
  || fail "couldn't make the stand-in package"
healthy() {  # healthy <container>: waits up to 90 s
  for _ in $(seq 1 45); do
    case "$(docker inspect -f '{{.State.Status}} {{.State.Health.Status}}' "$1" 2>/dev/null)" in
      "running healthy") return 0 ;;
      exited*) return 1 ;;
    esac
    sleep 2
  done
  return 1
}
docker run -d --name "$name" -v "$packages:/ibm:ro" -e TM1_DATASTORE_URL=mongodb://tm1:d4tast0rePW@mongo:27017 \
  -e HTTP_PROXY=http://127.0.0.1:9 -e TM1_DATABASES=planning -e TM1_USERS=analyst:Analysts "$image" >/dev/null
healthy "$name" || fail "the container didn't become healthy: $(docker logs "$name" 2>&1 | tail -30)"
out=$(docker logs "$name" 2>&1)
grep -q "New deployment in /tm1/deployment" <<<"$out" && grep -q "Created application tm1/tm1" <<<"$out" && grep -q "Creating database tm1/planning" <<<"$out" \
  || fail "the first start didn't set TM1 up: $out"
! grep -q "d4tast0rePW" <<<"$out" || fail "the datastore password was logged: $out"
[ "$(docker exec "$name" tm1-engine session tm1 analyst)" = "TM1SessionId=tm1:analyst" ] || fail "tm1-engine session didn't log in"
# Captured, then grepped, rather than "| grep -q": with pipefail, grep stopping at the first match
# can fail the pipeline though the line was there. Captured with its own check, so a command that
# fails after printing the line still fails the test
creds=$(docker exec "$name" tm1-engine credentials) || fail "tm1-engine credentials failed: $creds"
grep -qF "/tm1/api/v1/Databases('planning')/" <<<"$creds" || fail "tm1-engine credentials doesn't list the database: $creds"
pass "the container sets TM1 up and becomes healthy (behind a proxy setting, too)"
docker stop -t 60 "$name" >/dev/null
[ "$(docker inspect -f '{{.State.ExitCode}}' "$name")" = 0 ] || fail "stopping didn't exit 0: $(docker logs "$name" 2>&1 | tail -15)"
# Read whole (see above), for up to 10 s in case Docker hasn't written the last lines yet, and the
# message shows the log the check read, not a later one
for _ in $(seq 1 10); do
  logs=$(docker logs "$name" 2>&1) || fail "couldn't read the container's log"
  grep -q "Stopped 1 database(s) cleanly" <<<"$logs" && break
  sleep 1
done
grep -q "Stopped 1 database(s) cleanly" <<<"$logs" || fail "the database wasn't stopped through TM1: $(tail -15 <<<"$logs")"
pass "stopping stops each database through TM1 first, and exits 0"
docker start "$name" >/dev/null
healthy "$name" || fail "the container didn't become healthy again: $(docker logs "$name" 2>&1 | tail -30)"
[ "$(docker logs "$name" 2>&1 | grep -c "Created application")" = 1 ] || fail "the second start made the application again: $(docker logs "$name" 2>&1 | tail -30)"
[ "$(docker exec "$name" tm1-engine session tm1 analyst)" = "TM1SessionId=tm1:analyst" ] || fail "no login after the restart"
pass "a second start brings the database back and creates nothing again"

# 11. Without TM1_DATASTORE_URL: MongoDB is downloaded, checked against the checksum beside
#     it, and put behind the wrapper
docker run -d --name "$name-own" -v "$packages:/ibm:ro" -e TM1_DATASTORE_DOWNLOAD_URL='file:///ibm/mongodb.tgz?X-Amz-Signature=SECRET123' "$image" >/dev/null
healthy "$name-own" || fail "the container with TM1's own MongoDB didn't become healthy: $(docker logs "$name-own" 2>&1 | tail -30)"
out=$(docker logs "$name-own" 2>&1)
grep -q "datastore: embedded" <<<"$out" || fail "TM1 wasn't started with its own MongoDB: $out"
! grep -q "not verified" <<<"$out" || fail "the MongoDB download's checksum wasn't found: $out"
! grep -q "SECRET123" <<<"$out" || fail "the MongoDB download URL's credentials were printed: $out"
docker exec "$name-own" sh -c 'd=/opt/ibm/tm1-engine/third_party/datastore; test -x "$d/mongod.bin" && cmp -s "$d/mongod" /usr/local/lib/tm1-engine/mongod-wrapper.sh' \
  || fail "MongoDB wasn't put behind the wrapper"
pass "TM1's own MongoDB is downloaded, verified and wrapped"

echo "All smoke tests passed."

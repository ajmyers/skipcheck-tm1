#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

# CI smoke test for the tm1-engine image (run by .github/workflows/docker.yml).
# CI has no IBM binaries, so this can't start TM1. It checks that the image contains no
# IBM software or MongoDB, has what TM1 needs from the OS, fails cleanly with a helpful
# message when there's no TM1, and installs a stand-in package (a made-up installer
# with IBM's layout) the way it installs IBM's. The setup tool's logic is tested in
# scripts/tests/test_tm1_engine.py; starting real TM1 is tested by hand.
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
  shellcheck -S warning "$here"/docker/*.sh "$here"/ci-smoke-test.sh && pass "shellcheck"
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
    "./bin/tm1sd.exe": b"#!/bin/sh\nexit 0\n",
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
'
out=$(run -e MAKE="$make_package" --entrypoint bash "$image" -c '
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
out=$(run -e MAKE="$make_package" --entrypoint bash "$image" -c 'python3 -c "$MAKE" /ibm damaged && tm1-engine-install' 2>&1); rc=$?
set -e
[ "$rc" -eq 2 ] && grep -q "damaged" <<<"$out" || fail "a damaged installer wasn't refused (exit $rc): $out"
pass "a damaged installer is refused"

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

echo "All smoke tests passed."

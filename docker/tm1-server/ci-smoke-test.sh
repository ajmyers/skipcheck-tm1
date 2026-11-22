#!/bin/bash
# CI smoke test for the tm1-server image (run by .github/workflows/docker.yml).
# CI has no IBM binaries, so this can't start TM1. It checks that the image
# contains no IBM software, has the runtime libraries TM1 needs, and fails
# cleanly with a helpful message when the TM1 install isn't mounted.
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
  shellcheck -S warning "$here"/docker/*.sh "$here"/ci-smoke-test.sh "$here"/setup.sh && pass "shellcheck"
else
  echo "skip - shellcheck not installed"
fi

# 2. No IBM software baked into the image
contents=$(run --entrypoint sh "$image" -c 'ls -A /opt/ibm/cognos/tm1_64 2>/dev/null; find / -xdev \( -name "tm1s.exe" -o -name "libgsk*" -o -name "*linuxi38664h*" \) 2>/dev/null')
[ -z "$contents" ] || fail "image contains IBM files: $contents"
pass "image contains no IBM software"

# 3. Runtime dependencies TM1 Server needs from the OS
run --entrypoint sh "$image" -c 'ldconfig -p | grep -q "libcurl.so.4 " && command -v tini >/dev/null && command -v pgrep >/dev/null' \
  || fail "missing runtime dependency (libcurl4 / tini / procps)"
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

# 5. Admin role without a TM1 install: same, naming the Admin Server binary
set +e
out=$(run "$image" admin 2>&1); rc=$?
set -e
[ "$rc" -eq 2 ] || fail "expected exit 2 for admin without a TM1 install, got $rc: $out"
grep -q "tm1admsrv.exe not found" <<<"$out" || fail "missing admin help text: $out"
pass "admin without TM1 install fails with help text"

# 6. install helper without a bundle: exits 2 with a clear error
set +e
out=$(run -v "$(mktemp -d):/ibm:ro" "$image" install 2>&1); rc=$?
set -e
[ "$rc" -eq 2 ] || fail "expected exit 2 from install without a bundle, got $rc: $out"
grep -q "no bundle found" <<<"$out" || fail "missing install error text: $out"
pass "install without a bundle fails with help text"

# 7. Bundle URL that can't be downloaded: exits 2, and never prints the query
#    string (pre-signed URLs carry credentials there)
set +e
out=$(run -e TM1_BUNDLE_URL='http://127.0.0.1:9/tm1server-bundle.tar.gz?X-Amz-Signature=SECRET123' "$image" 2>&1); rc=$?
set -e
[ "$rc" -eq 2 ] || fail "expected exit 2 for an unreachable bundle URL, got $rc: $out"
grep -q "download failed: http://127.0.0.1:9/tm1server-bundle.tar.gz" <<<"$out" || fail "missing download error: $out"
! grep -q "SECRET123" <<<"$out" || fail "bundle URL credentials were printed: $out"
pass "unreachable bundle URL fails cleanly without printing credentials"

# 7b. CAM setup builds CAMIDs from short names and keeps full CAMIDs as given
out=$(run -e 'TM1_CAM_ADMINS=admin,CAMID("AD:u:uid=x, ou=people,dc=a")' -e 'TM1_CAM_GROUPS=Analysts,Readers' --entrypoint python3 "$image" /usr/local/lib/tm1-server/cam-setup.py --print 2>&1) \
  || fail "cam-setup.py --print failed: $out"
for want in 'admin CAMID("LDAP:u:uid=admin,ou=people")' 'admin CAMID("AD:u:uid=x, ou=people,dc=a")' \
  'group CAMID("LDAP:g:cn=analysts,ou=groups")' 'group CAMID("LDAP:g:cn=readers,ou=groups")'; do
  grep -qxF -- "$want" <<<"$out" || fail "cam-setup.py --print lacks $want: $out"
done
[ "$(grep -c -E '^(admin|group) ' <<<"$out")" -eq 4 ] || fail "cam-setup.py --print split a CAMID: $out"
out=$(run -e TM1_CAM_NAMESPACE=OIDC -e TM1_CAM_NAMESPACE_TYPE=oidc -e TM1_CAM_ADMINS=admin -e TM1_CAM_GROUPS=Analysts \
  --entrypoint python3 "$image" /usr/local/lib/tm1-server/cam-setup.py --print 2>&1) || fail "cam-setup.py --print (oidc) failed: $out"
grep -qxF 'admin CAMID("OIDC:u:admin")' <<<"$out" && grep -qxF 'group CAMID("OIDC:g:analysts")' <<<"$out" \
  || fail "cam-setup.py --print lacks OIDC CAMIDs: $out"
pass "CAM setup builds CAMIDs (LDAP and OIDC, and keeps full CAMIDs whole)"

# 8. The setup scripts show the same banner as the image (each embeds a copy)
while IFS= read -r line; do
  [ -n "$line" ] || continue
  for script in setup.sh setup.ps1; do
    grep -qF -- "$line" "$here/$script" || fail "$script's banner differs from docker/banner.txt, at: $line"
  done
done <"$here/docker/banner.txt"
pass "setup.sh and setup.ps1 show docker/banner.txt"

echo "All smoke tests passed."

#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

# CI smoke test for the tm1-web image (run by .github/workflows/docker.yml).
# CI has no IBM binaries, so this can't start TM1 Web. It checks that the image
# contains no IBM software, has the runtime libraries TM1 Web needs, and fails
# cleanly with a helpful message when the TM1 Web install isn't mounted.
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

# 2. No IBM software baked into the image
contents=$(run --entrypoint sh "$image" -c 'ls -A /opt/ibm/cognos/tm1web 2>/dev/null; find / -xdev \( -name "tm1web*.war" -o -name "evalservice*.war" -o -name "ws-server.jar" -o -name "*linuxi38664h*" \) 2>/dev/null')
[ -z "$contents" ] || fail "image contains IBM files: $contents"
pass "image contains no IBM software"

# 3. Runtime dependencies TM1 Web needs from the OS
run --entrypoint sh "$image" -c 'command -v tini >/dev/null && command -v python3 >/dev/null && command -v flock >/dev/null && fc-list | grep -q .' \
  || fail "missing runtime dependency (tini / python3 / flock / a font)"
pass "runtime dependencies present"

# 4. Without a TM1 Web install: exits 2 and explains what to mount
set +e
out=$(run "$image" 2>&1); rc=$?
set -e
[ "$rc" -eq 2 ] || fail "expected exit 2 without a TM1 Web install, got $rc: $out"
grep -q "does not include IBM software" <<<"$out" || fail "missing help text: $out"
pass "start without TM1 Web install fails with help text"
grep -qF "$(sed -n 2p "$here/docker/banner.txt")" <<<"$out" || fail "startup log doesn't show docker/banner.txt: $out"
pass "startup log shows the banner"

# 5. install helper without a bundle: exits 2 with a clear error
set +e
out=$(run -v "$(mktemp -d):/ibm:ro" "$image" install 2>&1); rc=$?
set -e
[ "$rc" -eq 2 ] || fail "expected exit 2 from install without a bundle, got $rc: $out"
grep -q "no bundle found" <<<"$out" || fail "missing install error text: $out"
pass "install without a bundle fails with help text"

# 6. Bundle URL that can't be downloaded: exits 2, and never prints the query
#    string (pre-signed URLs carry credentials there)
set +e
out=$(run -e TM1_WEB_BUNDLE_URL='http://127.0.0.1:9/tm1web-bundle.tar.gz?X-Amz-Signature=SECRET123' "$image" 2>&1); rc=$?
set -e
[ "$rc" -eq 2 ] || fail "expected exit 2 for an unreachable bundle URL, got $rc: $out"
grep -q "download failed: http://127.0.0.1:9/tm1web-bundle.tar.gz" <<<"$out" || fail "missing download error: $out"
! grep -q "SECRET123" <<<"$out" || fail "bundle URL credentials were printed: $out"
pass "unreachable bundle URL fails cleanly without printing credentials"

# 7. tm1web_config.xml settings: applied in place, quotes escaped, missing keys added
cfg=$(mktemp)
cat >"$cfg" <<'XML'
<tm1web_config>
    <appSettings>
        <add key="AdminHostName" value="" />
        <add key="Tm1DirectConnection" value='' />
    </appSettings>
</tm1web_config>
XML
out=$(run -v "$cfg:/tmp/c.xml" -e TM1_ADMIN_HOST=tm1-admin -e 'TM1_WEB_CFG_Tm1DirectConnection={"a":1}' -e TM1_WEB_CFG_NewKey=x \
  --entrypoint sh "$image" -c 'python3 /usr/local/lib/tm1-web/configure-tm1-web.py /tmp/c.xml >/dev/null && cat /tmp/c.xml')
grep -qF '<add key="AdminHostName" value="tm1-admin" />' <<<"$out" || fail "AdminHostName not set: $out"
grep -qF "<add key=\"Tm1DirectConnection\" value='{\"a\":1}' />" <<<"$out" || fail "JSON value not quoted safely: $out"
grep -qF '<add key="NewKey" value="x" />' <<<"$out" || fail "missing key not added: $out"
pass "tm1web_config.xml settings applied"

# 9. A tm1web bundle (fakes, built here) installs through its manifest, gzipped as 2.1.25's
#    is and uncompressed as 2.1.2 to 2.1.16's are, whatever the name says
fake=$(mktemp -d)
python3 - "$fake" <<'PY'
import io, json, sys, tarfile, zipfile
def tar_with(name, data):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as t:
        info = tarfile.TarInfo(name); info.size = len(data); t.addfile(info, io.BytesIO(data))
    return buf.getvalue()
repo = io.BytesIO()
with zipfile.ZipFile(repo, "w") as z:
    z.writestr("manifest/tm1web/9.9.9/m.json", json.dumps({
        "productName": "Fake TM1 Web", "productVersion": "9.9.9", "platform": "linuxi38664h",
        "license": {"name": "license", "version": "1", "repositoryPath": "p/license.tar.gz"},
        "installables": [{"name": "TM1_WebTier", "parts": [{"name": "web", "version": "9.9.9", "repositoryPath": "p/web.tar.gz"}]}]}))
    z.writestr("p/license.tar.gz", tar_with("license/LA_en", b"license"))
    z.writestr("p/web.tar.gz", tar_with("webapps/tm1web.txt", b"web"))
data = repo.getvalue()
for name, mode in (("tm1web-9.9.9-1-linuxi38664h_bundle.tar.gz", "w:gz"), ("plain/tm1web-9.9.9-1-linuxi38664h_bundle.tar.gz", "w")):
    path = sys.argv[1] + "/" + name
    import os; os.makedirs(os.path.dirname(path), exist_ok=True)
    with tarfile.open(path, mode) as bundle:
        info = tarfile.TarInfo("tm1web-9.9.9-1-linuxi38664h.zip"); info.size = len(data); bundle.addfile(info, io.BytesIO(data))
PY
for kind in gzipped plain; do
  dir=$fake; [ "$kind" = plain ] && dir=$fake/plain
  out=$(run -v "$dir:/ibm:ro" --entrypoint sh "$image" -c '
    tm1-web-install --target /t >/tmp/install.log 2>&1 || { cat /tmp/install.log; exit 1; }
    test -f /t/webapps/tm1web.txt && test -f /t/license/LA_en && echo installed' 2>&1) || true
  grep -qx installed <<<"$out" || fail "a $kind tm1web bundle didn't install: $out"
done
rm -rf "$fake"
pass "a tm1web bundle installs through its manifest, gzipped or not"

echo "All smoke tests passed."

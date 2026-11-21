#!/bin/bash
# CI smoke test for the cognos-server image (run by .github/workflows/docker.yml).
# CI has no IBM software, so this can't start Cognos. It checks that the image
# contains no IBM software and has what Cognos needs, that the unpacker follows a
# repository manifest (using a small fake repository), that the embedded
# PostgreSQL starts, and that the image fails cleanly with helpful messages when
# Cognos isn't there.
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

# 2. No IBM software baked into the image
contents=$(run --entrypoint sh "$image" -c 'ls -A /opt/ibm/cognos/analytics 2>/dev/null; find / -xdev \( -name "cogconfig.sh" -o -name "casrv-*" -o -name "*linuxi38664h*" \) 2>/dev/null')
[ -z "$contents" ] || fail "image contains IBM files: $contents"
pass "image contains no IBM software"

# 3. What Cognos needs from the image
run --entrypoint sh "$image" -c 'command -v tini >/dev/null && command -v python3 >/dev/null && command -v curl >/dev/null \
  && ls /usr/lib/postgresql/*/bin/postgres >/dev/null && ls /usr/local/lib/cognos-server/drivers/postgresql-*.jar >/dev/null \
  && ldconfig -p | grep -q "libfreetype.so.6 "' \
  || fail "missing runtime dependency (tini / python3 / curl / PostgreSQL / JDBC driver / freetype)"
pass "runtime dependencies present"

# 4. The unpacker follows a manifest: installables, installablesRef, tar.gz, zip
#    (keeping the executable bit) and plain jar parts, and records the manifest.
fake=$(mktemp -d)
python3 - "$fake/casrv-0.0.1-0-linuxi38664h.zip" <<'PY'
import io, json, sys, tarfile, zipfile

def tgz(name, data):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        info = tarfile.TarInfo(name); info.size = len(data); info.mode = 0o755
        tar.addfile(info, io.BytesIO(data))
    return buf.getvalue()

def zipped(name, data, mode):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        info = zipfile.ZipInfo(name); info.external_attr = (0o100000 | mode) << 16
        zf.writestr(info, data)
    return buf.getvalue()

part = lambda name, path, where="./": {"name": name, "version": "1", "repositoryPath": path, "installpath": where}
manifest = {
    "productName": "Fake Cognos", "productVersion": "0.0.1", "platform": "linuxi38664h",
    "license": part("license", "lic/license.zip"),
    "installResource": part("irb", "irb/irb.zip"),
    "installables": [
        {"name": "Top", "parts": [part("bin", "p/bin.tar.gz")], "installablesRef": ["Shared"]},
        {"name": "Shared", "parts": [part("script", "p/script.zip"), part("lib", "p/lib-1.jar", "webapps/lib")]},
        {"name": "NotWanted", "parts": [part("skip", "p/skip.tar.gz")]},
    ],
}
with zipfile.ZipFile(sys.argv[1], "w") as repo:
    repo.writestr("manifest/fake/0.0.1/fake-manifest.json", json.dumps(manifest))
    repo.writestr("lic/license.zip", zipped("license/LICENSE.txt", b"license", 0o644))
    repo.writestr("irb/irb.zip", zipped("irb.txt", b"irb", 0o644))
    repo.writestr("p/bin.tar.gz", tgz("bin64/tool.sh", b"#!/bin/sh\n"))
    repo.writestr("p/script.zip", zipped("configuration/run.sh", b"#!/bin/sh\n", 0o755))
    repo.writestr("p/lib-1.jar", b"jar")
    repo.writestr("p/skip.tar.gz", tgz("skipped.txt", b"no"))
PY
out=$(run -v "$fake:/ibm:ro" --entrypoint sh "$image" -c '
  python3 /usr/local/lib/cognos-server/unpack-cognos.py /ibm/casrv-0.0.1-0-linuxi38664h.zip /t Top >/dev/null &&
  test -x /t/bin64/tool.sh && test -x /t/configuration/run.sh && test -f /t/webapps/lib/lib-1.jar &&
  test -f /t/license/LICENSE.txt && test ! -e /t/skipped.txt &&
  test -f /t/uninstall/logs/fake-manifest.json && grep -qx fake-manifest.json /t/.cognos-server/manifest-name &&
  echo unpacked' 2>&1) || true
grep -q unpacked <<<"$out" || fail "unpacker didn't follow the fake manifest: $out"
out=$(run -v "$fake:/ibm:ro" --entrypoint sh "$image" -c 'python3 /usr/local/lib/cognos-server/unpack-cognos.py /ibm/casrv-0.0.1-0-linuxi38664h.zip /t Nope' 2>&1) && fail "unknown installable accepted"
grep -q "Not in this manifest: Nope" <<<"$out" || fail "unknown installable error missing: $out"
pass "unpacker follows the manifest (refs, tar.gz, zip with exec bit, jar; unwanted parts skipped)"

# 4b. A part whose member names escape the install directory is refused
python3 - "$fake/casrv-0.0.2-0-linuxi38664h.zip" <<'PY'
import io, json, sys, zipfile
buf = io.BytesIO()
with zipfile.ZipFile(buf, "w") as zf:
    zf.writestr("../escaped.txt", b"no")
manifest = {"productName": "Evil", "productVersion": "0.0.2", "platform": "linuxi38664h",
            "license": {"name": "l", "version": "1", "repositoryPath": "p/evil.zip", "installpath": "./"},
            "installResource": {"name": "r", "version": "1", "repositoryPath": "p/evil.zip", "installpath": "./"},
            "installables": []}
with zipfile.ZipFile(sys.argv[1], "w") as repo:
    repo.writestr("manifest/evil/0.0.2/evil.json", json.dumps(manifest))
    repo.writestr("p/evil.zip", buf.getvalue())
PY
out=$(run -v "$fake:/ibm:ro" --entrypoint sh "$image" -c 'python3 /usr/local/lib/cognos-server/unpack-cognos.py /ibm/casrv-0.0.2-0-linuxi38664h.zip /t/x; test ! -e /t/escaped.txt && echo contained' 2>&1) || true
grep -q "unsafe path in a part: ../escaped.txt" <<<"$out" && grep -q contained <<<"$out" || fail "unpacker didn't refuse an escaping path: $out"
pass "unpacker refuses paths that escape the install directory"

# 5. The cogstartup.xml template and render steps
out=$(run --entrypoint sh "$image" -c '
  cat > /tmp/in.xml <<XML
<crn:parameters xmlns:crn="c" xmlns:xsi="x"><crn:parameter name="hostName"><crn:value xsi:type="xsd:string"/></crn:parameter>
<crn:parameter name="externalDispatcher"><crn:value xsi:type="cfg:localURI">http://abc123:9300/p2pd/servlet/dispatch</crn:value></crn:parameter>
<crn:parameter name="pmp"><crn:value xsi:type="xsd:anyURI">http://localhost:9510/pmpsvc</crn:value></crn:parameter>
<crn:value xsi:type="xsd:string">GRP_abc123</crn:value><crn:value xsi:type="cfg:hostOnly">abc123</crn:value>
<credential><username>abc123</username></credential>
<crn:parameter name="serverCommonName"><crn:value xsi:type="xsd:string">localhost</crn:value></crn:parameter>
<crn:parameter name="sanDNSName"><crn:value xsi:type="xsd:string">localhost</crn:value></crn:parameter>
<crn:instances name="database"><crn:instance class="DB2" name="Content Store"><crn:parameter name="name"><crn:value>cm</crn:value></crn:parameter></crn:instance></crn:instances>
<crn:parameter name="dispatcherMaxMemory"><crn:value xsi:type="xsd:unsignedInt">8192</crn:value></crn:parameter></crn:parameters>
XML
  python3 /usr/local/lib/cognos-server/configure-cognos.py template /tmp/in.xml /tmp/t.xml >/dev/null &&
  COGNOS_HOST=cog COGNOS_CS_HOST=db COGNOS_MAX_MEMORY=4096 python3 /usr/local/lib/cognos-server/configure-cognos.py render /tmp/t.xml /tmp/out.xml &&
  cat /tmp/out.xml' 2>&1) || fail "template/render failed: $out"
for want in "http://cog:9300/p2pd/servlet/dispatch" "http://localhost:9510/pmpsvc" "GRP_cog" '"cfg:hostOnly">cog<' 'name="hostName"><crn:value xsi:type="xsd:string">cog<' 'class="PostgreSQL"' "db:5432" ">4096<" \
  'name="serverCommonName"><crn:value xsi:type="xsd:string">cog<' 'name="sanDNSName"><crn:value xsi:type="xsd:string">cog localhost<'; do
  grep -qF -- "$want" <<<"$out" || fail "rendered cogstartup.xml lacks $want: $out"
done
grep -qF '<username>abc123</username>' <<<"$out" || fail "a value that only equals the install hostname was changed: $out"
[ "$(grep -o abc123 <<<"$out" | wc -l)" -eq 1 ] || fail "install hostname left in rendered cogstartup.xml: $out"
pass "cogstartup.xml template and render"

# 6. The embedded PostgreSQL starts, and the content store role can log in over TCP
out=$(run --entrypoint sh "$image" -c '
  cognos-postgres start >/dev/null &&
  PGPASSWORD=cognos "$(ls -d /usr/lib/postgresql/*/bin | tail -1)/psql" -h 127.0.0.1 -U cognos -d cm -tAc "select 42" &&
  cognos-postgres stop >/dev/null' 2>&1) || true
grep -qx 42 <<<"$out" || fail "embedded PostgreSQL didn't work: $out"
pass "embedded PostgreSQL starts with the content store database"

# 7. Without a Cognos install: exits 2 and explains what to provide
set +e
out=$(run "$image" 2>&1); rc=$?
set -e
[ "$rc" -eq 2 ] || fail "expected exit 2 without a Cognos install, got $rc: $out"
grep -q "does not include IBM software" <<<"$out" || fail "missing help text: $out"
grep -qF "$(sed -n 3p "$here/docker/banner.txt")" <<<"$out" || fail "startup log doesn't show docker/banner.txt: $out"
pass "start without Cognos fails with help text and the banner"

# 7b. Embedded PostgreSQL refuses names that aren't plain SQL identifiers
out=$(run -e 'COGNOS_CS_USER=x"; DROP' --entrypoint cognos-postgres "$image" start 2>&1) && fail "odd role name accepted"
grep -q "isn't a plain SQL name" <<<"$out" || fail "missing name error: $out"
pass "embedded PostgreSQL refuses unsafe role names"

# 8. install without a zip: exits 2 with a clear error
set +e
out=$(run -v "$(mktemp -d):/ibm:ro" "$image" install 2>&1); rc=$?
set -e
[ "$rc" -eq 2 ] || fail "expected exit 2 from install without a zip, got $rc: $out"
grep -q "no Cognos repository zip found" <<<"$out" || fail "missing install error text: $out"
pass "install without a zip fails with help text"

# 9. A zip URL that can't be downloaded: exits 2, and never prints the query
#    string (pre-signed URLs carry credentials there)
set +e
out=$(run -e COGNOS_ZIP_URL='http://127.0.0.1:9/casrv-linuxi38664h.zip?X-Amz-Signature=SECRET123' "$image" 2>&1); rc=$?
set -e
[ "$rc" -eq 2 ] || fail "expected exit 2 for an unreachable zip URL, got $rc: $out"
grep -q "download failed: http://127.0.0.1:9/casrv-linuxi38664h.zip" <<<"$out" || fail "missing download error: $out"
! grep -q "SECRET123" <<<"$out" || fail "zip URL credentials were printed: $out"
pass "unreachable zip URL fails cleanly without printing credentials"

echo "All smoke tests passed."

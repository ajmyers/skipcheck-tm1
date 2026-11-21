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
  shellcheck -S warning "$here"/docker/*.sh "$here"/ci-smoke-test.sh && pass "shellcheck"
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

# 5. install helper without a bundle: exits 2 with a clear error
set +e
out=$(run -v "$(mktemp -d):/ibm:ro" "$image" install 2>&1); rc=$?
set -e
[ "$rc" -eq 2 ] || fail "expected exit 2 from install without a bundle, got $rc: $out"
grep -q "no tm1server-" <<<"$out" || fail "missing install error text: $out"
pass "install without a bundle fails with help text"

echo "All smoke tests passed."

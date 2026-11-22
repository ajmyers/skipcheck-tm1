#!/usr/bin/env bash
# Runs every client's tests against a real TM1: the Java *Tm1Test classes, the Python
# tests/live and the .NET Live tests. Give TM1 the way the Java tests take it, one of:
#
#   TM1_PRIVATE_IMAGE   an image with TM1 built in
#   TM1_HOME_DIR        an installed tm1_64 folder
#   TM1_INSTALL_VOLUME  a Docker volume holding an install
#   TM1_BUNDLE_DIR      a folder holding IBM's bundle (TM1_BUNDLE picks the file)
#   TM1_BUNDLE_URL      a download link for the bundle (TM1_BUNDLE_SHA256 checks it)
#
# Each suite starts its own tm1-server container from it (they share the bundle cache volume),
# or, with TM1_URL set, the Python and .NET suites use that server instead.
#
#   scripts/live-tests.sh                  # all three
#   scripts/live-tests.sh python dotnet    # some
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -z "${TM1_PRIVATE_IMAGE:-}${TM1_HOME_DIR:-}${TM1_INSTALL_VOLUME:-}${TM1_BUNDLE_DIR:-}${TM1_BUNDLE_URL:-}${TM1_URL:-}" ]]; then
  echo "No TM1 configured: set one of TM1_PRIVATE_IMAGE, TM1_HOME_DIR, TM1_INSTALL_VOLUME, TM1_BUNDLE_DIR, TM1_BUNDLE_URL (or TM1_URL)" >&2
  exit 2
fi

suites=("$@")
[[ ${#suites[@]} -eq 0 ]] && suites=(java python dotnet)
failed=()
for suite in "${suites[@]}"; do
  echo "== $suite"
  case "$suite" in
    java) ./gradlew test --tests '*Tm1Test' || failed+=(java) ;;
    python) (cd tm1-python && python -m pytest tests/live -rs) || failed+=(python) ;;
    dotnet) dotnet test tm1-dotnet/Tm1.slnx --filter 'FullyQualifiedName~.Live.' || failed+=(dotnet) ;;
    *) echo "Unknown suite: $suite (java, python, dotnet)" >&2; exit 2 ;;
  esac
done
if [[ ${#failed[@]} -gt 0 ]]; then
  echo "Failed: ${failed[*]}" >&2
  exit 1
fi

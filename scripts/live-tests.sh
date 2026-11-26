#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0

# Runs every client's tests against a real TM1: the Java *Tm1Test classes, the Python
# tests/live, the .NET Live tests and the TypeScript test/live. Give TM1 the way the Java tests take it, one of:
#
#   TM1_PRIVATE_IMAGE   an image with TM1 built in
#   TM1_HOME_DIR        an installed tm1_64 folder
#   TM1_INSTALL_VOLUME  a Docker volume holding an install
#   TM1_BUNDLE_DIR      a folder holding IBM's bundle (TM1_BUNDLE picks the file)
#   TM1_BUNDLE_URL      a download link for the bundle (TM1_BUNDLE_SHA256 checks it)
#
# Each suite starts its own tm1-server container from it (they share the bundle cache volume),
# or, with TM1_URL set, the Python, .NET and TypeScript suites use that server instead.
#
# Each suite also runs the live conformance scenarios (spec/conformance/live) and writes what it
# sent to build/conformance-live/<language>; afterwards scripts/compare-live-traffic.py requires
# every language to have sent the same requests. And each runs the walkthroughs' examples, and
# records what they print in api-site/example-output, which the docs show: commit what changed.
#
#   scripts/live-tests.sh                  # all four
#   scripts/live-tests.sh python dotnet    # some
#
# Against several TM1 versions in turn, each one's results kept: scripts/live-test-versions.py.
#
# The suites are Gradle tasks (build.gradle.kts), so the same runs on Windows, where there's no
# bash: ./gradlew liveTest --continue, or some of :tm1-client:liveTest, liveTestPython,
# liveTestDotnet, liveTestTypescript with compareLiveTraffic. This script is that, by suite name.
set -euo pipefail
cd "$(dirname "$0")/.."

suites=("$@")
[[ ${#suites[@]} -eq 0 ]] && suites=(java python dotnet typescript)
tasks=()
for suite in "${suites[@]}"; do
  case "$suite" in
    java) tasks+=(:tm1-client:liveTest) ;;
    python) tasks+=(liveTestPython) ;;
    dotnet) tasks+=(liveTestDotnet) ;;
    typescript) tasks+=(liveTestTypescript) ;;
    *) echo "Unknown suite: $suite (java, python, dotnet, typescript)" >&2; exit 2 ;;
  esac
done
# --continue: a suite that fails doesn't stop the ones after it, nor the comparison
exec ./gradlew --continue "${tasks[@]}" compareLiveTraffic

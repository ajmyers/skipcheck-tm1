#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0

# Runs every client's tests against a real TM1: the Java *Tm1Test classes, the Python
# tests/live, the .NET Live tests and the TypeScript test/live. Give TM1 the way the Java tests take it, one of:
#
#   TM1_PRIVATE_IMAGE          an image with TM1 11 built in
#   TM1_HOME_DIR               an installed tm1_64 folder
#   TM1_INSTALL_VOLUME         a Docker volume holding a TM1 11 install
#   TM1_BUNDLE_DIR             a folder holding IBM's TM1 11 bundle (TM1_BUNDLE picks the file)
#   TM1_BUNDLE_URL             a download link for the TM1 11 bundle (TM1_BUNDLE_SHA256 checks it)
#
# Or TM1 v12 (Planning Analytics 3.x), starting a tm1-engine container:
#
#   TM1_ENGINE_PRIVATE_IMAGE   an image with TM1 v12 built in
#   TM1_ENGINE_HOME_DIR        an installed tm1-engine folder
#   TM1_ENGINE_INSTALL_VOLUME  a Docker volume holding a TM1 v12 install
#   TM1_ENGINE_BUNDLE_DIR      a folder holding IBM's tm1-v*-standalone-linux.tgz (TM1_ENGINE_BUNDLE picks the file)
#   TM1_ENGINE_BUNDLE_URL      a download link for the TM1 v12 package (TM1_ENGINE_BUNDLE_SHA256 checks it)
#
# Each suite starts its own container from it (they share the bundle cache volume),
# or, with TM1_URL set, every suite uses that server instead (TM1_USER, TM1_PASSWORD, TM1_NAMESPACE,
# TM1_VERIFY=false as needed). A test that needs a container of its own (CAM, Git's daemon,
# encryption's own server, the examples' samples) still starts one from a source, or skips.
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
# liveTestDotnet, liveTestTypescript (and liveTestTypescriptContainers) with compareLiveTraffic. This script is that, by suite name.
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
    typescript) tasks+=(liveTestTypescript liveTestTypescriptContainers) ;;
    *) echo "Unknown suite: $suite (java, python, dotnet, typescript)" >&2; exit 2 ;;
  esac
done
# --continue: a suite that fails doesn't stop the ones after it, nor the comparison
exec ./gradlew --continue "${tasks[@]}" compareLiveTraffic

#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Publishes the release's npm tarballs, the client first (the testcontainers name it as a peer).
# The release workflow runs it twice: with --dry-run while packaging, before the gate, and for
# real after it, so a command that can't publish fails before anything is published.
#
#   scripts/npm-publish.sh <folder of tarballs> <dist-tag> [--dry-run]
#
# The paths are written with ./ on purpose: npm reads "packages/x.tgz" as a GitHub shorthand
# (user/repo) and tries to fetch it over SSH, which failed the v0.1.0 release.
set -euo pipefail
shopt -s nullglob

dir="${1:?folder of tarballs}"
tag="${2:?dist-tag}"
dry="${3:-}"
[[ -z "$dry" || "$dry" == "--dry-run" ]] || { echo "unknown option: $dry" >&2; exit 2; }

publish() {
  local pattern="$1" files
  # The pattern is meant to glob: it names the version-numbered tarball
  # shellcheck disable=SC2206
  files=("$dir"/$pattern)
  if [[ ${#files[@]} -ne 1 ]]; then
    echo "expected one $pattern in $dir, found: ${files[*]:-none}" >&2
    exit 1
  fi
  local file="${files[0]}"
  [[ "$file" == /* || "$file" == ./* ]] || file="./$file"
  if [[ -n "$dry" ]]; then
    npm publish "$file" --tag "$tag" --dry-run
  else
    npm publish "$file" --provenance --tag "$tag"
  fi
}

publish 'skipcheck-tm1-client-*.tgz'
publish 'skipcheck-tm1-testcontainers-*.tgz'

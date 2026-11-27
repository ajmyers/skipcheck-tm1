#!/bin/sh
# SPDX-License-Identifier: Apache-2.0

# pa-setup: one command for a whole IBM Planning Analytics environment in Docker,
# described by pa-environment.yaml in the current folder.
#
#   curl -fsSL https://skipcheck.io/pa/setup.sh | sh                # asks where TM1 comes from, and starts it
#   curl -fsSL https://skipcheck.io/pa/setup.sh | sh -s -- --bundle ~/Downloads/tm1server-...-linuxi38664h_bundle.tar.gz
#   curl -fsSL https://skipcheck.io/pa/setup.sh | sh -s -- init    # write a starting file to edit
#   curl -fsSL https://skipcheck.io/pa/setup.sh | sh -s -- up      # start everything in the file
#   ./setup.sh urls | status | logs [service] | pull | down | generate | --help
#
# This script only checks Docker and runs the setup tool in a container, with the
# Docker socket and this folder mounted. Docker is all the host needs.
set -eu

# :edge (built from master) until the first release; PA_IMAGE_TAG picks the other images' tag
IMAGE=${PA_SETUP_IMAGE:-ghcr.io/ajmyers/pa-setup:${PA_IMAGE_TAG:-edge}}

if ! command -v docker >/dev/null 2>&1; then
  echo "pa-setup needs Docker: https://www.docker.com/products/docker-desktop/ (Mac, Windows) or https://docs.docker.com/engine/install/ (Linux)" >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker isn't running (docker info failed). Start Docker Desktop, or the Docker service, and try again." >&2
  exit 1
fi

# The setup tool's own image is fetched afresh where a new environment starts, and for
# pull: a copy from an earlier day wouldn't know what this script may ask of it. Without a
# network, or for an image of your own (PA_SETUP_IMAGE), what's here is used.
case "${1:-}" in
  '' | -* | init | pull) docker pull -q "$IMAGE" >/dev/null 2>&1 || true ;;
esac

# The daemon's socket: DOCKER_HOST's, if it's a local one (Colima, rootless), else the default
socket=/var/run/docker.sock
case "${DOCKER_HOST:-}" in unix://*) socket=${DOCKER_HOST#unix://} ;; esac

run() {
  # shellcheck disable=SC2086  # $tty is empty or one flag
  docker run --rm $tty -v "$socket:/var/run/docker.sock" -v "$PWD:/work" \
    -e PA_HOST_DIR="$PWD" -e PA_HOST_HOME="$HOME" -e PA_SETUP_IMAGE="$IMAGE" \
    ${CI:+-e CI="$CI"} ${PA_IMAGE_TAG:+-e PA_IMAGE_TAG="$PA_IMAGE_TAG"} "$IMAGE" "$@"
}

if [ -t 0 ] && [ -t 1 ]; then
  tty=-it
  run "$@"
elif [ -t 1 ] && [ -z "${CI:-}" ] && (exec </dev/tty) 2>/dev/null; then
  # Piped from curl: this script is what's on standard input, so questions are asked
  # and answered at the terminal itself
  tty=-it
  run "$@" </dev/tty
else
  tty=
  run "$@"
fi

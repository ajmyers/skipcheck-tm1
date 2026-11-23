#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0

# paw-local.sh: run IBM Planning Analytics Workspace (PAW) Local next to tm1-server.
#
# IBM ships PAW Local as Docker images plus compose files and scripts, in
# ipa_workspace_local_<release>.<build>.zip. This script uses those as they are:
# it unpacks the zip, loads IBM's images, writes PAW's configuration and runs
# IBM's own scripts/paw.sh. No IBM software is in this repository.
#
# Usage: ./paw-local.sh [up|down|reset|status|logs [service...]|help]
#
#   up      Unpack the zip and load its images (first time only), write the
#           config, start PAW and wait until it answers. The default.
#   down    Stop and remove the PAW containers. PAW's data is kept.
#   reset   Like down, and also delete PAW's data (its Docker volumes).
#   status  List the PAW containers.
#   logs    Show recent logs of all or the named PAW services.
#
# Settings (environment variables; all optional except PAW_ACCEPT_LICENSE):
#   PAW_ACCEPT_LICENSE=true  You accept IBM's license for PAW (the text is in
#                            the zip, licenses/LA_en). Required by "up".
#   PAW_ZIP                  The PAW Local zip. Default: the newest
#                            ipa_workspace_local_*.zip in PAW_BUNDLE_DIR.
#   PAW_BUNDLE_DIR           Where to look for the zip. Default: ./bundles
#   PAW_HOME                 Where the zip is unpacked. Default: ~/.cache/tm1-paw
#   PAW_NETWORK              Docker network PAW joins. Default: the network of a
#                            running tm1-admin container, else paw_default.
#   PAW_TM1_ADMIN_URL        TM1 Admin Server, as PAW sees it.
#                            Default: http://tm1-admin:5895
#   PAW_AUTH_MODE            tm1 (log on to a TM1 server) or cam (Cognos).
#                            Default: tm1
#   PAW_TM1_LOGIN_URL        The TM1 server PAW logs users on to (tm1 mode).
#                            Default: http://tm1:8010
#   PAW_CAM_SERVLET          Cognos dispatcher (cam mode), e.g.
#                            http://cognos:9300/p2pd/servlet/dispatch
#   PAW_CAM_GATEWAY          Cognos gateway URL (cam mode, optional).
#   PAW_CAM_NAMESPACE        Cognos namespace ID (cam mode).
#   PAW_TM1WEB_URL           TM1 Web, as PAW sees it, e.g. http://tm1-web:9510
#   PAW_HTTP_PORT            Host port for PAW. Default: 8800
#   PAW_HTTPS_PORT           Host port for PAW over https. Default: 8843
#   PAW_JVM_ARGS             JVM options of prism-platform, the largest service.
#                            Default: IBM's (-Xmx4096m -Xms4096m)
#   PAW_WAIT                 Seconds "up" waits for PAW; 0 to not wait. Default: 600
set -euo pipefail

die()  { printf 'paw-local: %s\n' "$*" >&2; exit 1; }
info() { printf 'paw-local: %s\n' "$*"; }

usage() {
	sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
}

PAW_BUNDLE_DIR=${PAW_BUNDLE_DIR:-./bundles}
PAW_HOME=${PAW_HOME:-$HOME/.cache/tm1-paw}
PAW_HTTP_PORT=${PAW_HTTP_PORT:-8800}
PAW_HTTPS_PORT=${PAW_HTTPS_PORT:-8843}
PAW_WAIT=${PAW_WAIT:-600}

# The zip to use: PAW_ZIP, or the newest one in PAW_BUNDLE_DIR.
find_zip() {
	if [ -n "${PAW_ZIP:-}" ]; then
		[ -f "$PAW_ZIP" ] || die "PAW_ZIP is not a file: $PAW_ZIP"
		printf '%s\n' "$PAW_ZIP"
		return
	fi
	local newest
	# shellcheck disable=SC2012 # IBM's fixed file names: no newlines
	newest=$(ls -t "$PAW_BUNDLE_DIR"/ipa_workspace_local_*.zip 2>/dev/null | head -n 1 || true)
	[ -n "$newest" ] || die "no PAW Local zip: set PAW_ZIP, or put ipa_workspace_local_*.zip in $PAW_BUNDLE_DIR"
	printf '%s\n' "$newest"
}

# "<release>.<build>" from the zip's config/version.env, without unpacking it.
zip_version() {
	local env release build
	env=$(unzip -p "$1" config/version.env 2>/dev/null) || die "not a PAW Local zip (no config/version.env): $1"
	release=$(printf '%s\n' "$env" | sed -n 's/^export PAW_RELEASE=\([0-9.]*\).*/\1/p')
	build=$(printf '%s\n' "$env" | sed -n 's/^export PAW_BUILD=\([0-9]*\).*/\1/p')
	[ -n "$release" ] && [ -n "$build" ] || die "can't read PAW_RELEASE and PAW_BUILD from $1"
	printf '%s.%s\n' "$release" "$build"
}

# The unpacked PAW for down, reset, status and logs: the zip's, or the newest one.
find_paw_dir() {
	if [ -n "${PAW_ZIP:-}" ] || ls "$PAW_BUNDLE_DIR"/ipa_workspace_local_*.zip >/dev/null 2>&1; then
		printf '%s/%s\n' "$PAW_HOME" "$(zip_version "$(find_zip)")"
		return
	fi
	local newest
	# shellcheck disable=SC2012 # our own version-named folders
	newest=$(ls -td "$PAW_HOME"/*/ 2>/dev/null | head -n 1 || true)
	[ -n "$newest" ] || die "PAW hasn't been set up yet: run ./paw-local.sh up"
	printf '%s\n' "${newest%/}"
}

require_license() {
	[ "${PAW_ACCEPT_LICENSE:-}" = true ] && return
	die "set PAW_ACCEPT_LICENSE=true to accept IBM's license for Planning Analytics Workspace.
  Read it first: unzip -p '$1' licenses/LA_en | less
  (This script skips IBM's admin tool, where the license is normally accepted.)"
}

# Unpack everything but the images into PAW_HOME/<version>, once.
unpack() {
	local zip=$1 dir=$2 tmp
	[ -f "$dir/.unpacked" ] && return
	info "unpacking $(basename "$zip") into $dir"
	mkdir -p "$PAW_HOME"
	tmp=$(mktemp -d "$PAW_HOME/.unpack.XXXXXX")
	unzip -q "$zip" -x 'images/images.tar' -d "$tmp"
	[ -f "$tmp/scripts/paw.sh" ] || { rm -rf "$tmp"; die "not a PAW Local zip (no scripts/paw.sh): $zip"; }
	rm -rf "$dir"
	mv "$tmp" "$dir"
	touch "$dir/.unpacked"
}

# IBM's paw.sh calls software/docker-compose, a Linux x86-64 binary. Point it at
# "docker compose" instead, which works on macOS and Windows too.
shim_compose() {
	local bin="$1/software/docker-compose"
	docker compose version >/dev/null 2>&1 || return 0
	grep -q 'paw-local shim' "$bin" 2>/dev/null && return
	if [ -f "$bin" ]; then mv "$bin" "$bin.ibm"; fi
	printf '#!/bin/sh\n# paw-local shim: IBM ships a Linux-only binary here\nexec docker compose "$@"\n' >"$bin"
	chmod +x "$bin"
}

# The images listed in images.env, as tags.
image_tags() {
	(
		# shellcheck disable=SC2034 # read by images.env
		REGISTRY=127.0.0.1:5000/planninganalytics
		# shellcheck disable=SC1091
		. "$1/images/images.env"
		for v in $(compgen -v | grep '_IMAGE$'); do printf '%s\n' "${!v}"; done
	)
}

# docker load the images straight from the zip, when any is missing.
load_images() {
	local zip=$1 dir=$2 tag missing=0
	for tag in $(image_tags "$dir"); do
		docker image inspect "$tag" >/dev/null 2>&1 || missing=$((missing + 1))
	done
	if [ "$missing" -eq 0 ]; then
		info "PAW images already loaded"
		return
	fi
	info "loading PAW images from the zip ($missing missing; several GB, a few minutes)"
	unzip -p "$zip" images/images.tar | docker load
}

# random_text LENGTH: letters and digits. LC_ALL=C matters: macOS tr fails on
# binary input otherwise, and IBM's paw.sh then writes empty secret files.
random_text() {
	LC_ALL=C tr -dc 'a-zA-Z0-9' </dev/urandom | head -c "$1" || true
}

# PAW's secrets, made before paw.sh would make them (empty on macOS).
make_secrets() {
	local config="$1/config"
	if [ ! -s "$config/db_secret.key" ] && docker volume inspect paw_mongo >/dev/null 2>&1; then
		die "PAW's database exists but its password file is missing or empty ($config/db_secret.key).
  Run ./paw-local.sh reset to start PAW with fresh data."
	fi
	[ -s "$config/db_secret.key" ] || random_text 20 >"$config/db_secret.key"
	[ -s "$config/cookie_secret.key" ] || random_text 32 >"$config/cookie_secret.key"
	[ -s "$config/credential_store.key" ] || head -c 512 /dev/urandom >"$config/credential_store.key"
	chmod a+r "$config/db_secret.key" "$config/cookie_secret.key" "$config/credential_store.key"
	[ -s "$config/db_secret.key" ] && [ -s "$config/cookie_secret.key" ] || die "couldn't write PAW's secrets in $config"
}

# The network PAW joins: PAW_NETWORK, or tm1-admin's. PAW's services dial the IP
# addresses the Admin Server advertises, so they must share its network.
pick_network() {
	if [ -n "${PAW_NETWORK:-}" ]; then
		printf '%s\n' "$PAW_NETWORK"
		return
	fi
	local net
	net=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' tm1-admin 2>/dev/null | awk '{print $1}' || true)
	printf '%s\n' "${net:-paw_default}"
}

# export NAME=VALUE, quoted for bash (paw.sh sources this file).
env_line() {
	printf 'export %s=%q\n' "$1" "$2"
}

write_paw_env() {
	local dir=$1 network=$2 mode=${PAW_AUTH_MODE:-tm1}
	case "$mode" in
		tm1) ;;
		cam) [ -n "${PAW_CAM_SERVLET:-}" ] || die "PAW_AUTH_MODE=cam needs PAW_CAM_SERVLET (the Cognos dispatcher URL)" ;;
		*) die "PAW_AUTH_MODE must be tm1 or cam, not: $mode" ;;
	esac
	{
		echo "# Written by paw-local.sh on every start: set PAW_* variables instead of editing."
		env_line PAW_NET "$network"
		env_line TM1Location "${PAW_TM1_ADMIN_URL:-http://tm1-admin:5895}"
		env_line TM1ApplicationsLocation "${PAW_TM1WEB_URL:-}"
		env_line PAAuthMode "$mode"
		env_line TM1LoginServerURL "${PAW_TM1_LOGIN_URL:-http://tm1:8010}"
		env_line IBMCognosServlet "${PAW_CAM_SERVLET:-}"
		env_line IBMCognosGatewayURL "${PAW_CAM_GATEWAY:-}"
		env_line CAMLoginNamespace "${PAW_CAM_NAMESPACE:-}"
		env_line EnableSSL false
		env_line ServerName localhost
		env_line PAGatewayHTTPPort "$PAW_HTTP_PORT"
		env_line PAGatewayHTTPSPort "$PAW_HTTPS_PORT"
		if [ -n "${PAW_JVM_ARGS:-}" ]; then env_line PRISM_PLATFORM_JVM_ARGS "$PAW_JVM_ARGS"; fi
	} >"$dir/config/paw.env"
}

# IBM's paw.sh, run from the unpacked folder, passing its arguments to docker compose.
paw_sh() {
	local dir=$1
	shift
	(cd "$dir" && LC_ALL=C ./scripts/paw.sh "$@")
}

wait_for_paw() {
	local url="http://localhost:$PAW_HTTP_PORT" waited=0 code
	if [ "$PAW_WAIT" = 0 ]; then
		info "PAW is starting: $url (not waiting, PAW_WAIT=0)"
		return
	fi
	info "waiting for PAW at $url (up to ${PAW_WAIT}s)"
	while :; do
		code=$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$url/login" || true)
		[ "$code" = 200 ] && break
		[ "$waited" -ge "$PAW_WAIT" ] && die "PAW didn't answer within ${PAW_WAIT}s (last status: $code). See ./paw-local.sh status and logs."
		sleep 5
		waited=$((waited + 5))
	done
	info "PAW is up: $url"
	if [ "${PAW_AUTH_MODE:-tm1}" = tm1 ]; then
		info "log on as a user of the TM1 server at ${PAW_TM1_LOGIN_URL:-http://tm1:8010}"
	fi
}

cmd_up() {
	local zip dir network
	command -v docker >/dev/null 2>&1 || die "docker isn't installed or isn't on the PATH"
	command -v unzip >/dev/null 2>&1 || die "unzip isn't installed"
	command -v curl >/dev/null 2>&1 || die "curl isn't installed"
	zip=$(find_zip)
	dir="$PAW_HOME/$(zip_version "$zip")"
	require_license "$zip"
	docker info >/dev/null 2>&1 || die "can't reach Docker: is it running?"
	unpack "$zip" "$dir"
	shim_compose "$dir"
	load_images "$zip" "$dir"
	make_secrets "$dir"
	network=$(pick_network)
	write_paw_env "$dir" "$network"
	info "starting PAW $(basename "$dir") on network $network"
	paw_sh "$dir" up -d --remove-orphans
	wait_for_paw
}

main() {
	local cmd=${1:-up}
	if [ $# -gt 0 ]; then shift; fi
	case "$cmd" in
		up) cmd_up ;;
		down) paw_sh "$(find_paw_dir)" down --remove-orphans ;;
		reset) paw_sh "$(find_paw_dir)" down --remove-orphans --volumes ;;
		status) paw_sh "$(find_paw_dir)" ps ;;
		logs) paw_sh "$(find_paw_dir)" logs --tail 100 "$@" ;;
		help | -h | --help) usage ;;
		*) usage >&2; exit 2 ;;
	esac
}

main "$@"

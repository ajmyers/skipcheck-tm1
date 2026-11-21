#!/bin/sh
# tm1-server setup: installs and starts a local IBM Planning Analytics (TM1) server
# with Docker, doing the steps in GETTING-STARTED.md and checking them as it goes.
#
#   curl -fsSL https://ajmyers.net/tm1/setup.sh | sh
#   curl -fsSL https://ajmyers.net/tm1/setup.sh | sh -s -- --bundle ~/Downloads/tm1server-...-linuxi38664h_bundle.tar.gz
#
# Run with --help for every option. The options are a stable interface: the setup
# page on ajmyers.net builds one-liners from them, so don't rename or remove any.
# setup.ps1 is the Windows twin and takes the same options; keep them in step.
#
# Questions are only asked when someone is at a terminal and CI isn't set. Otherwise
# every answer comes from a flag or a TM1_SETUP_* environment variable, and a missing
# required answer fails straight away, naming the flag.
#
# Reruns are safe: an existing compose.yaml, .env and TM1 install are kept, and the
# script says what it reused.
set -eu

DEFAULT_BASE_URL=https://raw.githubusercontent.com/ajmyers/tm1-pa-developer-tools/master/docker/tm1-server
GUIDE=https://github.com/ajmyers/tm1-pa-developer-tools/blob/master/docker/tm1-server/GETTING-STARTED.md
DEFAULT_IMAGE=ghcr.io/ajmyers/tm1-server:latest
ISSUES=https://github.com/ajmyers/tm1-pa-developer-tools/issues

# --- Options: flags override TM1_SETUP_* environment variables -----------------
DIR=${TM1_SETUP_DIR:-}
SOURCE=${TM1_SETUP_SOURCE:-}
BUNDLE=${TM1_SETUP_BUNDLE:-}
URL=${TM1_SETUP_URL:-}
SHA256=${TM1_SETUP_SHA256:-}
TM1_HOME=${TM1_SETUP_TM1_HOME:-}
IMAGE=${TM1_SETUP_IMAGE:-}
FILES_DIR=${TM1_SETUP_FILES_DIR:-}
SAMPLES=${TM1_SETUP_SAMPLES:-}
YES=${TM1_SETUP_YES:-0}
NO_START=${TM1_SETUP_NO_START:-0}
TIMEOUT=${TM1_SETUP_TIMEOUT:-600}
BASE_URL=${TM1_SETUP_BASE_URL:-$DEFAULT_BASE_URL}

usage() {
  cat <<'EOF'
tm1-server setup: install and start a local TM1 server with Docker.

Usage: setup.sh [options]
  piped: curl -fsSL https://ajmyers.net/tm1/setup.sh | sh -s -- [options]

Where TM1 comes from (pick one; asked if not given):
  --bundle FILE        A: your IBM download, tm1server-<ver>-linuxi38664h_bundle.tar.gz.
                          It's copied into bundles/ and installed on first start.
  --url URL            B: a link to that file, e.g. a pre-signed S3/R2/GCS/Azure URL
  --sha256 HASH           with B: the file's SHA-256, checked before installing
  --tm1-home DIR       C: an installed tm1_64 folder (the one containing bin64/)
  --source image       D: a private image with TM1 built in; name it with --image
  --source NAME        bundle, url, folder or image (or A, B, C, D). Only needed when
                       the options above don't already say which.

Other choices:
  --dir DIR            The folder to set up TM1 in (default: ./tm1)
  --files-dir DIR      Where each server's config, data and logs go
                       (default: tm1-files inside --dir)
  --samples            Also run IBM's SData sample server, on port 8011
  --no-samples         Don't (the default)
  --image IMAGE        The tm1-server image (default: ghcr.io/ajmyers/tm1-server:latest),
                       e.g. ghcr.io/ajmyers/tm1-server:edge, or your private image (D)

Running:
  -y, --yes            Ask nothing: use the options given and the defaults
  --no-start           Set everything up, but don't start TM1
  --timeout SECONDS    How long to wait for TM1 to become healthy (default: 600)
  --base-url URL       Where to download compose.yaml and .env.example from
  -h, --help           Show this help

Each option can also be set in the environment as TM1_SETUP_<NAME>, with the name
in upper case and - as _: TM1_SETUP_BUNDLE, TM1_SETUP_FILES_DIR, TM1_SETUP_SAMPLES=1,
TM1_SETUP_YES=1, ... Flags win over the environment.
EOF
}

# --- Output ---------------------------------------------------------------------
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  B=$(printf '\033[1m'); R=$(printf '\033[31m'); G=$(printf '\033[32m'); Y=$(printf '\033[33m'); C=$(printf '\033[36m'); N=$(printf '\033[0m')
else
  B=; R=; G=; Y=; C=; N=
fi
step() { printf '\n%s==> %s%s\n' "$B" "$*" "$N"; }
ok()   { printf '  %s✓%s %s\n' "$G" "$N" "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '  %s!%s %s\n' "$Y" "$N" "$*" >&2; }
# Who made this. Same text as setup.ps1 and the image's startup log (docker/banner.txt).
banner() {
  printf '\n%s' "$C"
  cat <<'EOF'
  ------------------------------------------------------------------
   tm1-server: a local IBM Planning Analytics (TM1) server in Docker
   Free and open source, made by Andrew Myers

     Website  https://ajmyers.net
     GitHub   https://github.com/ajmyers
     Issues   https://github.com/ajmyers/tm1-pa-developer-tools/issues

   Useful? A star helps: https://github.com/ajmyers/tm1-pa-developer-tools
   Need help with TM1, or a commercial license? https://ajmyers.net
   Not affiliated with IBM. You need your own licensed copy of TM1.
  ------------------------------------------------------------------
EOF
  printf '%s' "$N"
}
# die MESSAGE [HOW TO FIX, one line per argument]
die() {
  printf '\n%sError:%s %s\n' "$R" "$N" "$1" >&2; shift
  for line in "$@"; do printf '  %s\n' "$line" >&2; done
  printf '\nHelp: %s\n' "$GUIDE" >&2
  printf 'Still stuck? Open an issue: %s\n' "$ISSUES" >&2
  exit 1
}

# --- Arguments ------------------------------------------------------------------
while [ $# -gt 0 ]; do
  arg=$1; shift
  case $arg in
    --*=*) val=${arg#*=}; arg=${arg%%=*}; has_val=1 ;;
    *) val=; has_val=0 ;;
  esac
  case $arg in
    --dir|--source|--bundle|--url|--sha256|--tm1-home|--image|--files-dir|--timeout|--base-url)
      if [ "$has_val" = 0 ]; then
        [ $# -gt 0 ] || die "$arg needs a value." "Run with --help to see the options."
        val=$1; shift
      fi
      [ -n "$val" ] || die "$arg needs a value." "Run with --help to see the options." ;;
    --samples|--no-samples|-y|--yes|--no-start|-h|--help)
      [ "$has_val" = 0 ] || die "$arg doesn't take a value." ;;
    *) die "Unknown option: $arg" "Run with --help to see the options." ;;
  esac
  case $arg in
    --dir) DIR=$val ;;
    --source) SOURCE=$val ;;
    --bundle) BUNDLE=$val ;;
    --url) URL=$val ;;
    --sha256) SHA256=$val ;;
    --tm1-home) TM1_HOME=$val ;;
    --image) IMAGE=$val ;;
    --files-dir) FILES_DIR=$val ;;
    --timeout) TIMEOUT=$val ;;
    --base-url) BASE_URL=$val ;;
    --samples) SAMPLES=1 ;;
    --no-samples) SAMPLES=0 ;;
    -y|--yes) YES=1 ;;
    --no-start) NO_START=1 ;;
    -h|--help) usage; exit 0 ;;
  esac
done

banner

case $TIMEOUT in ''|*[!0-9]*) die "--timeout must be a number of seconds, not '$TIMEOUT'." ;; esac
case $SAMPLES in ''|0|1) ;; true|yes|y) SAMPLES=1 ;; false|no|n) SAMPLES=0 ;;
  *) die "TM1_SETUP_SAMPLES must be 1 or 0, not '$SAMPLES'." ;; esac
case $YES in 1|true|yes) YES=1 ;; *) YES=0 ;; esac
case $NO_START in 1|true|yes) NO_START=1 ;; *) NO_START=0 ;; esac

# Ask questions only with a terminal to ask on. curl | sh leaves stdin as the pipe,
# so questions are read from /dev/tty.
INTERACTIVE=0
if [ "$YES" = 0 ] && [ -z "${CI:-}" ] && (exec </dev/tty) 2>/dev/null; then INTERACTIVE=1; fi

# ask PROMPT DEFAULT: prints the answer (the default if the answer is empty)
ask() {
  if [ -n "$2" ]; then printf '%s [%s]: ' "$1" "$2" >/dev/tty; else printf '%s: ' "$1" >/dev/tty; fi
  IFS= read -r reply </dev/tty || reply=
  printf '%s' "${reply:-$2}"
}
# confirm PROMPT DEFAULT(y|n): succeeds for yes
confirm() {
  while :; do
    if [ "$2" = y ]; then reply=$(ask "$1 (Y/n)" ""); else reply=$(ask "$1 (y/N)" ""); fi
    case ${reply:-$2} in [Yy]|[Yy][Ee][Ss]) return 0 ;; [Nn]|[Nn][Oo]) return 1 ;; esac
  done
}

# expand_path PATH: ~ expanded, made absolute against the current folder
expand_path() {
  p=$1
  # shellcheck disable=SC2088  # matching a literal ~ typed by the user
  case $p in "~") p=$HOME ;; "~/"*) p=$HOME/${p#"~/"} ;; esac
  case $p in /*) ;; *) p=$PWD/$p ;; esac
  while [ "$p" != / ] && [ "${p%/}" != "$p" ]; do p=${p%/}; done
  # Resolve ., .. and symlinks through the deepest part that already exists
  d=$p; rest=
  while [ ! -d "$d" ]; do rest=/$(basename "$d")$rest; d=$(dirname "$d"); done
  d=$(cd "$d" && pwd -P)
  printf '%s' "${d%/}$rest" | sed 's#/\./#/#g; s#/\.$##'
}

# A value for .env, single-quoted so compose takes it literally (no $ interpolation)
env_line() {
  case $2 in *"'"*) die "$1 can't contain a single quote ('): $2" ;; esac
  printf "%s='%s'\n" "$1" "$2"
}

BUNDLE_GLOB_HELP="It should be named tm1server-<version>-linuxi38664h_bundle.tar.gz: the Linux download, even on Mac or Windows."
# check_bundle FILE: the IBM Linux bundle, or die explaining what's wrong
check_bundle() {
  [ -f "$1" ] || die "Bundle file not found: $1" "$BUNDLE_GLOB_HELP"
  name=$(basename "$1")
  case $name in
    tm1server-*-linuxi38664h_bundle.tar.gz|tm1server-*-linuxi38664h.zip) ;;
    tm1server-*|*tm1*)
      die "$name isn't the Linux TM1 Server download." "$BUNDLE_GLOB_HELP" \
          "Download IBM Planning Analytics TM1 Server for Linux from IBM Passport Advantage or Fix Central." ;;
    *) die "$name doesn't look like an IBM TM1 Server bundle." "$BUNDLE_GLOB_HELP" ;;
  esac
  magic=$(head -c2 "$1" | od -An -tx1 | tr -d ' \n')
  case $name:$magic in
    *.tar.gz:1f8b|*.zip:504b) ;;
    *) die "$name is damaged or incomplete (it isn't a valid $(case $name in *.zip) echo zip ;; *) echo tar.gz ;; esac) file)." \
           "Download it again; a partly finished download looks like this." ;;
  esac
}

# --- 1. Check the computer and Docker ---------------------------------------------
step "Checking your computer"
OS=$(uname -s)
APPLE_SILICON=0
case $OS in
  Darwin)
    [ "$(sysctl -n hw.optional.arm64 2>/dev/null || true)" = 1 ] && APPLE_SILICON=1
    if [ "$APPLE_SILICON" = 1 ]; then ok "Mac with Apple Silicon"; else ok "Mac with Intel"; fi ;;
  Linux)
    ok "Linux ($(uname -m))" ;;
  MINGW*|MSYS*|CYGWIN*)
    die "This is the Mac/Linux script. On Windows, use setup.ps1 in PowerShell:" \
        "irm https://ajmyers.net/tm1/setup.ps1 | iex" ;;
  *) die "Unsupported system: $OS. tm1-server runs on Mac, Linux and Windows." ;;
esac

if [ "$APPLE_SILICON" = 1 ]; then
  /usr/bin/arch -x86_64 /usr/bin/true 2>/dev/null || die "Rosetta isn't installed. TM1 needs it on Apple Silicon Macs." \
    "Install it by running:" "  softwareupdate --install-rosetta --agree-to-license" "then run this setup again."
  ok "Rosetta is installed"
fi

command -v docker >/dev/null 2>&1 || {
  case $OS in
    Darwin) die "Docker isn't installed." \
      "Install Docker Desktop from https://www.docker.com/products/docker-desktop/" \
      "(pick the $( [ "$APPLE_SILICON" = 1 ] && echo "Apple Silicon" || echo "Intel" ) version), open it, accept the terms, then run this setup again." ;;
    *) die "Docker isn't installed." \
      "Install Docker Engine and the Compose plugin: https://docs.docker.com/engine/install/" \
      "then run this setup again." ;;
  esac
}

docker_up() { docker info >/dev/null 2>&1; }
if ! docker_up; then
  out=$(docker info 2>&1 || true)
  case $out in
    *"permission denied"*)
      die "Docker is installed, but your user isn't allowed to use it." \
          "Run:  sudo usermod -aG docker \"\$USER\"" "then log out and back in, and run this setup again." ;;
  esac
  if [ "$OS" = Darwin ] && open -a Docker >/dev/null 2>&1; then
    info "Starting Docker Desktop..."
    waited=0
    until docker_up; do
      [ "$waited" -lt 120 ] || die "Docker Desktop didn't start within 2 minutes." \
        "Open Docker Desktop, wait until it says the engine is running, then run this setup again."
      sleep 3; waited=$((waited + 3))
    done
  elif [ "$OS" = Darwin ]; then
    die "Docker isn't running." "Open Docker Desktop, wait until it says the engine is running, then run this setup again."
  else
    die "Docker isn't running." "Start it with:  sudo systemctl start docker" "then run this setup again."
  fi
fi
docker compose version >/dev/null 2>&1 || die "Docker Compose isn't installed." \
  "Install the Compose plugin: https://docs.docker.com/compose/install/linux/" "then run this setup again."
[ "$(docker info --format '{{.OSType}}' 2>/dev/null)" = linux ] || die "Docker is running Windows containers. TM1 needs Linux containers." \
  "In Docker Desktop, choose 'Switch to Linux containers...', then run this setup again."
ok "Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null) and Compose $(docker compose version --short 2>/dev/null) are running"
mem=$(docker info --format '{{.MemTotal}}' 2>/dev/null || echo 0)
if [ "${mem:-0}" -gt 0 ] && [ "$mem" -lt 3500000000 ]; then
  warn "Docker has $((mem / 1048576)) MB of memory; TM1 wants about 4 GB. Raise it in Docker Desktop's Settings > Resources if TM1 is slow or stops."
fi
case $(uname -m) in
  x86_64|amd64) ;;
  *) [ "$APPLE_SILICON" = 1 ] || warn "This isn't an x86-64 machine, so TM1 runs through emulation, which is slow." ;;
esac

# --- 2. Choices -------------------------------------------------------------------
[ "$INTERACTIVE" = 1 ] && step "A few questions (press Enter for the default)"

if [ -z "$DIR" ]; then
  if [ "$INTERACTIVE" = 1 ]; then DIR=$(ask "Folder to set up TM1 in" "./tm1"); else DIR=./tm1; fi
fi
DIR=$(expand_path "$DIR")
[ ! -e "$DIR" ] || [ -d "$DIR" ] || die "$DIR exists and isn't a folder." "Choose another with --dir."

# The compose project name, as compose derives it from the folder name
PROJECT=$(basename "$DIR" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_-')
VOLUME=${PROJECT}_tm1-install

# env_get KEY: KEY's value in the folder's existing .env, if any
env_get() {
  [ -f "$DIR/.env" ] || return 0
  grep -E "^[[:space:]]*$1=" "$DIR/.env" | tail -1 | cut -d= -f2- | sed -e "s/^['\"]//" -e "s/['\"][[:space:]]*$//" || true
}

# What's already there, from an earlier run
HAVE_VOLUME=0; docker volume inspect "$VOLUME" >/dev/null 2>&1 && HAVE_VOLUME=1
HAVE_ENV_SOURCE=0
[ -f "$DIR/.env" ] && grep -Eq '^[[:space:]]*(TM1_BUNDLE_URL|TM1_HOME_DIR|TM1_BUNDLE_DIR)=.' "$DIR/.env" && HAVE_ENV_SOURCE=1
HAVE_BUNDLES=0
for f in "$DIR"/bundles/tm1server-*_bundle.tar.gz "$DIR"/bundles/tm1server-*-linuxi38664h.zip; do [ -f "$f" ] && HAVE_BUNDLES=1; done

case $SOURCE in
  '') ;;
  A|a|bundle) SOURCE=bundle ;;
  B|b|url) SOURCE=url ;;
  C|c|folder) SOURCE=folder ;;
  D|d|image) SOURCE=image ;;
  *) die "Unknown --source '$SOURCE'." "Use bundle, url, folder or image (or A, B, C, D)." ;;
esac
given=
[ -n "$BUNDLE" ] && given="$given bundle"
[ -n "$URL" ] && given="$given url"
[ -n "$TM1_HOME" ] && given="$given folder"
given=${given# }
flag_for() { if [ "$1" = folder ]; then echo --tm1-home; else echo "--$1"; fi; }
case $given in *" "*)
  flags=; for g in $given; do flags="$flags $(flag_for "$g")"; done
  die "Choose one TM1 source: you gave$flags." ;;
esac
if [ -z "$SOURCE" ] && [ -n "$given" ]; then SOURCE=$given; fi
if [ -n "$SOURCE" ] && [ -n "$given" ] && [ "$SOURCE" != "$given" ]; then
  die "--source $SOURCE doesn't match the $(flag_for "$given") you gave."
fi

if [ -z "$SOURCE" ]; then
  if [ "$HAVE_VOLUME" = 1 ] || [ "$HAVE_ENV_SOURCE" = 1 ] || [ "$HAVE_BUNDLES" = 1 ]; then
    SOURCE=existing
  elif [ "$INTERACTIVE" = 1 ]; then
    cat >/dev/tty <<'EOF'

Where does TM1 come from?
  A  The IBM download file (tm1server-...-linuxi38664h_bundle.tar.gz)   [most people]
  B  That file in cloud storage, as a download link (e.g. pre-signed S3 URL)
  C  An installed tm1_64 folder, copied from a Linux TM1 server
  D  A private Docker image someone built with TM1 inside
EOF
    while [ -z "$SOURCE" ]; do
      case $(ask "Choose A, B, C or D" "A") in
        A|a) SOURCE=bundle ;; B|b) SOURCE=url ;; C|c) SOURCE=folder ;; D|d) SOURCE=image ;;
      esac
    done
  else
    die "No TM1 source given, and nothing to ask on (no terminal, or CI is set)." \
        "Give one of:  --bundle FILE  |  --url URL  |  --tm1-home DIR  |  --source image --image IMAGE"
  fi
fi

newest_download() {
  # shellcheck disable=SC2012  # file names from IBM have no newlines
  ls -t "$HOME"/Downloads/tm1server-*-linuxi38664h_bundle.tar.gz 2>/dev/null | head -1 || true
}
case $SOURCE in
  bundle)
    if [ -z "$BUNDLE" ]; then
      [ "$INTERACTIVE" = 1 ] || die "No bundle file given." "Use --bundle FILE."
      BUNDLE=$(ask "Path to the bundle file" "$(newest_download)")
      [ -n "$BUNDLE" ] || die "No bundle file given." "Use --bundle FILE."
    fi
    BUNDLE=$(expand_path "$BUNDLE")
    check_bundle "$BUNDLE" ;;
  url)
    if [ -z "$URL" ]; then
      [ "$INTERACTIVE" = 1 ] || die "No bundle URL given." "Use --url URL."
      URL=$(ask "Download link for the bundle" "")
      [ -n "$SHA256" ] || SHA256=$(ask "Its SHA-256 (optional, Enter to skip)" "")
    fi
    case $URL in http://*|https://*) ;; *) die "The bundle URL must start with https:// (or http://): $URL" ;; esac
    if [ -n "$SHA256" ]; then
      SHA256=$(printf '%s' "$SHA256" | tr '[:upper:]' '[:lower:]')
      case $SHA256 in *[!0-9a-f]*) die "--sha256 must be 64 hex characters." ;; esac
      [ ${#SHA256} -eq 64 ] || die "--sha256 must be 64 hex characters, not ${#SHA256}."
    fi ;;
  folder)
    if [ -z "$TM1_HOME" ]; then
      [ "$INTERACTIVE" = 1 ] || die "No TM1 folder given." "Use --tm1-home DIR."
      TM1_HOME=$(ask "Path to the tm1_64 folder" "")
    fi
    TM1_HOME=$(expand_path "$TM1_HOME")
    for exe in tm1s.exe tm1admsrv.exe; do
      [ -f "$TM1_HOME/bin64/$exe" ] || die "$TM1_HOME isn't an installed TM1 Server: there's no bin64/$exe in it." \
        "Point --tm1-home at the tm1_64 folder of a Linux TM1 Server install (usually /opt/ibm/cognos/tm1_64)."
    done ;;
  image)
    if [ -z "$IMAGE" ]; then
      [ "$INTERACTIVE" = 1 ] || die "No image given for --source image." "Use --image IMAGE, e.g. --image registry.example.com/private/tm1-server:2.1.19"
      IMAGE=$(ask "The private image (e.g. registry.example.com/private/tm1-server:2.1.19)" "")
      [ -n "$IMAGE" ] || die "No image given." "Use --image IMAGE."
    fi ;;
esac

if [ -z "$FILES_DIR" ] && [ "$INTERACTIVE" = 1 ] && [ ! -f "$DIR/.env" ]; then
  FILES_DIR=$(ask "Folder for TM1's config, data and logs" "$DIR/tm1-files")
  [ "$FILES_DIR" != "$DIR/tm1-files" ] || FILES_DIR=
fi
[ -z "$FILES_DIR" ] || FILES_DIR=$(expand_path "$FILES_DIR")

case $(env_get COMPOSE_PROFILES) in *samples*) [ -n "$SAMPLES" ] || SAMPLES=1 ;; esac
if [ -z "$SAMPLES" ]; then
  SAMPLES=0
  if [ "$INTERACTIVE" = 1 ] && confirm "Also start IBM's SData sample database (port 8011)?" n; then SAMPLES=1; fi
fi

# --- Summary --------------------------------------------------------------------
step "Plan"
info "Folder:     $DIR"
case $SOURCE in
  bundle)   info "TM1 from:   $BUNDLE" ;;
  url)      info "TM1 from:   $(printf '%s' "$URL" | sed 's/?.*/?.../')${SHA256:+ (SHA-256 checked)}" ;;
  folder)   info "TM1 from:   $TM1_HOME" ;;
  image)    info "TM1 from:   the image $IMAGE" ;;
  existing) info "TM1 from:   what's already set up there" ;;
esac
SHOW_IMAGE=${IMAGE:-$(env_get TM1_SERVER_IMAGE)}
SHOW_FILES=${FILES_DIR:-$(env_get TM1_FILES_DIR)}
info "Image:      ${SHOW_IMAGE:-$DEFAULT_IMAGE}"
info "Your files: ${SHOW_FILES:-$DIR/tm1-files}"
info "Servers:    tm1 (empty database)$( [ "$SAMPLES" = 1 ] && echo ", SData sample" )"
if [ "$INTERACTIVE" = 1 ]; then
  echo
  confirm "Go ahead?" y || { echo "Nothing changed."; exit 0; }
fi

# --- 3. Check for conflicts before changing anything ---------------------------
step "Checking ports and containers"
# owner_dir CONTAINER: the folder a compose container was started from ("" if none)
owner_dir() { docker inspect -f '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' "$1" 2>/dev/null || true; }
for c in tm1-admin tm1 tm1-sdata; do
  docker container inspect "$c" >/dev/null 2>&1 || continue
  from=$(owner_dir "$c")
  [ "$from" = "$DIR" ] && continue
  if [ -n "$from" ]; then
    die "A container named $c already exists, from a TM1 setup in $from." \
        "Only one setup can run at a time. Use that one (rerun with --dir \"$from\"), or remove it:" \
        "  cd \"$from\" && docker compose --profile samples down"
  fi
  die "A container named $c already exists, not started by tm1-server's compose.yaml." \
      "Remove it with:  docker rm -f $c"
done

port_busy() {
  if command -v lsof >/dev/null 2>&1; then lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
  elif command -v ss >/dev/null 2>&1; then [ -n "$(ss -ltnH "sport = :$1" 2>/dev/null)" ]
  elif command -v nc >/dev/null 2>&1; then nc -z 127.0.0.1 "$1" >/dev/null 2>&1
  else return 1
  fi
}
ports="5895 5495 8010 12345 12346"; [ "$SAMPLES" = 1 ] && ports="$ports 8011"
for p in $ports; do
  c=$(docker ps --filter "publish=$p" --format '{{.Names}}' 2>/dev/null | head -1)
  if [ -n "$c" ]; then
    [ "$(owner_dir "$c")" = "$DIR" ] && continue
    die "Port $p is in use by the Docker container $c." \
        "Stop it (docker stop $c), or change the left-hand port numbers under ports: in compose.yaml."
  fi
  port_busy "$p" && die "Port $p is in use by another program (perhaps another TM1)." \
    "Stop that program, or change the left-hand port numbers under ports: in $DIR/compose.yaml, e.g. \"9010:8010\"."
done
ok "Ports $(echo "$ports" | tr ' ' ',') are free"

# --- 4. Set up the folder --------------------------------------------------------
step "Setting up $DIR"
mkdir -p "$DIR/bundles"
fetch() {  # fetch NAME DEST: download NAME from BASE_URL
  case $BASE_URL in
    *://*) curl -fsSL --retry 3 -o "$2.tmp" "$BASE_URL/$1" ;;
    *) cp "$BASE_URL/$1" "$2.tmp" ;;
  esac || { rm -f "$2.tmp"; die "Couldn't download $1 from $BASE_URL." "Check your internet connection and try again."; }
  mv "$2.tmp" "$2"
}
if [ -f "$DIR/compose.yaml" ]; then
  ok "Kept the existing compose.yaml"
else
  fetch compose.yaml "$DIR/compose.yaml"; ok "Downloaded compose.yaml"
fi
fetch .env.example "$DIR/.env.example"; ok "Downloaded .env.example (every setting, explained)"

NEW_BUNDLE=0
if [ "$SOURCE" = bundle ]; then
  dest=$DIR/bundles/$(basename "$BUNDLE")
  size() { wc -c <"$1" | tr -d ' '; }
  if [ -f "$dest" ] && [ "$(size "$dest")" = "$(size "$BUNDLE")" ]; then
    ok "The bundle is already in bundles/"
  else
    info "Copying the bundle into bundles/ ($(($(size "$BUNDLE") / 1048576)) MB)..."
    cp "$BUNDLE" "$dest.tmp" && mv "$dest.tmp" "$dest"
    NEW_BUNDLE=1
    ok "Copied $(basename "$BUNDLE") into bundles/"
  fi
fi

# The settings this run wants in .env
want=$(mktemp); trap 'rm -f "$want"' EXIT
case $SOURCE in
  bundle)
    # Pin the bundle when bundles/ has others, which would otherwise win if newer
    n=0; for f in "$DIR"/bundles/tm1server-*; do [ -f "$f" ] && n=$((n + 1)); done
    [ "$n" -le 1 ] || env_line TM1_BUNDLE "$(basename "$BUNDLE")" >>"$want" ;;
  url)
    env_line TM1_BUNDLE_URL "$URL" >>"$want"
    [ -z "$SHA256" ] || env_line TM1_BUNDLE_SHA256 "$SHA256" >>"$want" ;;
  folder) env_line TM1_HOME_DIR "$TM1_HOME" >>"$want" ;;
esac
[ -z "$IMAGE" ] || env_line TM1_SERVER_IMAGE "$IMAGE" >>"$want"
[ -z "$FILES_DIR" ] || env_line TM1_FILES_DIR "$FILES_DIR" >>"$want"
[ "$SAMPLES" = 0 ] || env_line COMPOSE_PROFILES samples >>"$want"

PROFILE_ARGS=
if [ -f "$DIR/.env" ]; then
  ok "Kept the existing .env"
  # Report settings asked for here that the kept .env doesn't have
  while IFS= read -r line; do
    key=${line%%=*}; val=${line#*=}; val=${val#"'"}; val=${val%"'"}
    cur=$(env_get "$key")
    if [ "$cur" != "$val" ]; then
      if [ "$key" = COMPOSE_PROFILES ]; then PROFILE_ARGS="--profile samples"; continue; fi
      warn "Not applied, because .env already exists: $key=$val. Edit $DIR/.env to change it."
    fi
  done <"$want"
else
  { cat "$DIR/.env.example"; echo; echo "# --- Set by setup.sh on $(date '+%Y-%m-%d') ---"; cat "$want"; } >"$DIR/.env"
  ok "Wrote .env$( [ -s "$want" ] && printf ' (%s)' "$(cut -d= -f1 "$want" | tr '\n' ' ' | sed 's/ $//; s/ /, /g')")"
fi

if [ "$HAVE_VOLUME" = 1 ] && { [ "$NEW_BUNDLE" = 1 ] || [ "$SOURCE" = url ]; }; then
  warn "TM1 is already installed in the Docker volume $VOLUME, so that install is used, not the bundle given here."
  info "To install this one instead: cd \"$DIR\" && docker compose down && docker volume rm $VOLUME, then run this setup again."
elif [ "$HAVE_VOLUME" = 1 ]; then
  ok "Reusing the TM1 install in the Docker volume $VOLUME"
fi

compose() { (cd "$DIR" && docker compose "$@"); }
if [ "$NO_START" = 1 ]; then
  step "Ready, not started (--no-start)"
  info "To start TM1:  cd \"$DIR\" && docker compose up -d"
  exit 0
fi

# --- 5. Start TM1 -----------------------------------------------------------------
step "Starting TM1"
# shellcheck disable=SC2086  # PROFILE_ARGS is empty or two words
images=$(compose $PROFILE_ARGS config --images | sort -u)
for img in $images; do
  if docker image inspect "$img" >/dev/null 2>&1; then
    ok "Using the image $img (already downloaded; docker compose pull updates it)"
  else
    info "Downloading $img..."
    docker pull --platform linux/amd64 -q "$img" >/dev/null 2>&1 || {
      case $img in
        ghcr.io/ajmyers/tm1-server*) die "Couldn't download the image $img." \
          "Check your internet connection, and that your network allows ghcr.io." \
          "Before the first release, only :edge exists: add --image ghcr.io/ajmyers/tm1-server:edge" ;;
        *) die "Couldn't download the image $img." \
          "For a private registry, log in first:  docker login $(echo "$img" | cut -d/ -f1)" ;;
      esac
    }
    ok "Downloaded $img"
  fi
done

img=$(compose config --images | head -1)
if [ "$APPLE_SILICON" = 1 ]; then
  docker run --rm --platform linux/amd64 --entrypoint sh "$img" -c 'grep -q /run/rosetta /proc/self/maps' 2>/dev/null || die \
    "Docker isn't using Rosetta, so TM1 would be very slow or crash." \
    "In Docker Desktop, open Settings > General, turn on 'Use Rosetta for x86_64/amd64 emulation on Apple Silicon'," \
    "click 'Apply & restart', then run this setup again."
  ok "Docker uses Rosetta for TM1"
elif ! docker run --rm --platform linux/amd64 --entrypoint true "$img" >/dev/null 2>&1; then
  die "Docker can't run x86-64 containers on this machine." \
      "Enable emulation with:  docker run --privileged --rm tonistiigi/binfmt --install amd64"
fi

# shellcheck disable=SC2086
if ! out=$(compose $PROFILE_ARGS up -d 2>&1); then
  printf '%s\n' "$out" >&2
  die "docker compose up failed (the reason is above)." "See the Troubleshooting section of the guide."
fi
# shellcheck disable=SC2086
ok "Started $(compose $PROFILE_ARGS ps -a --format '{{.Name}}' | sort | tr '\n' ' ' | sed 's/ $//; s/ /, /g')"

# --- 6. Wait until healthy ----------------------------------------------------------
# shellcheck disable=SC2086
services=$(compose $PROFILE_ARGS ps -a --format '{{.Name}}' | sort)
if [ "$HAVE_VOLUME" = 0 ] && { [ "$SOURCE" = bundle ] || [ "$SOURCE" = url ]; }; then
  info "Waiting for TM1 to be ready (the first start installs TM1 from the bundle, about 15 s)..."
else
  info "Waiting for TM1 to be ready..."
fi
fail_with_logs() {  # fail_with_logs CONTAINER WHAT
  echo >&2
  docker logs --tail 40 "$1" >&2 2>&1 || true
  die "$1 $2." "Its log is above: lines starting [tm1-entrypoint] explain what happened." \
      "Full log:  cd \"$DIR\" && docker compose logs $(echo "$1" | sed 's/^tm1-sdata$/sdata/')"
}
start=$(date +%s)
pending=$services
while [ -n "$pending" ]; do
  still=
  for c in $pending; do
    st=$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} {{.RestartCount}}' "$c" 2>/dev/null || echo "missing none 0")
    # shellcheck disable=SC2086  # split into status, health, restarts
    set -- $st
    case $1:$2:$3 in
      running:healthy:*) ok "$c is healthy" ;;
      *:unhealthy:*) fail_with_logs "$c" "is unhealthy" ;;
      exited:*|dead:*|restarting:*|missing:*) fail_with_logs "$c" "stopped" ;;
      running:*:0|created:*) still="$still $c" ;;
      *) fail_with_logs "$c" "keeps restarting" ;;
    esac
  done
  pending=${still# }
  [ -n "$pending" ] || break
  [ $(($(date +%s) - start)) -lt "$TIMEOUT" ] || fail_with_logs "$(echo "$pending" | cut -d' ' -f1)" "didn't become healthy within $TIMEOUT seconds"
  sleep 3
done

# shellcheck disable=SC2016  # $value is part of the TM1 URL
version=$(curl -fsS -u admin: 'http://localhost:8010/api/v1/Configuration/ProductVersion/$value' 2>/dev/null || true)

# --- Done ---------------------------------------------------------------------------
step "TM1 is running${version:+: version $version}"
cat <<EOF

  tm1 (empty database)   http://localhost:8010/api/v1/   user admin, no password
EOF
case $services in *tm1-sdata*) echo "  SData (sample)         http://localhost:8011/api/v1/   user admin, password apple" ;; esac
cat <<EOF
  Admin Server           http://localhost:5895/api/v1/Servers

  Connect your tools to host localhost, port 8010, SSL off.
  Your files:            ${SHOW_FILES:-$DIR/tm1-files}

  Everyday commands, run in $DIR:
    docker compose ps            see what's running
    docker compose logs -f tm1   watch the TM1 log
    docker compose stop          stop (TM1 saves first)
    docker compose up -d         start again
EOF

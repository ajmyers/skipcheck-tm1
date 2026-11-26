# SPDX-License-Identifier: Apache-2.0
# shellcheck shell=bash
#
# Finding TM1's processes by where they run from, for the entrypoint (which sources this file)
# and its tests.
#
# Every process of TM1's runs from its install, so its command line holds the install's path.
# That's matched after the start of the line or a space, not only at the start: under an
# emulator the line begins with the emulator's own path. Docker Desktop's Rosetta shows
# "/run/rosetta/rosetta /tm1/cache/<package>/bin/tm1sd.exe ...", and a pattern anchored at the
# start matched nothing, so stop_tm1 left TM1's processes for the container's teardown (#418).
# tm1-server's daemon.sh finds its daemons the same way (#417).

# install_pattern <folder> [folder ...]: an extended regex for a command line holding a path inside
# any of the folders, after the line's start or a space. Looser than the line starting with it: a
# command given that path as an argument (a docker exec'd "ls /opt/ibm/tm1-engine/bin") matches
# too. It's only used as the container stops, when everything in it goes anyway.
install_pattern() {
  local folder quoted alternatives=
  for folder in "$@"; do
    quoted=$(printf '%s' "${folder%/}" | sed 's/[][\.*^$+?(){}|]/\\&/g')
    alternatives="${alternatives:+$alternatives|}$quoted"
  done
  printf '(^| )(%s)/' "$alternatives"
}

# kill_install <install folder>: kills (SIGKILL) whatever runs from the folder. The entrypoint
# starts TM1 through the folder as given (/opt/ibm/tm1-engine, which may be a link to the cache),
# and a process can show either path, so both are matched. Nothing is killed for a folder that is
# /, or resolves to it or to nothing: that pattern would match every process.
kill_install() {
  local given=${1%/} home
  home=$(readlink -f "$1") || return 0
  case "$given" in ''|/) return 0 ;; esac
  case "$home" in ''|/) return 0 ;; esac
  if [ "$home" = "$given" ]; then
    pkill -KILL -f "$(install_pattern "$home")" 2>/dev/null || true
  else
    pkill -KILL -f "$(install_pattern "$given" "$home")" 2>/dev/null || true
  fi
}

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

# install_pattern <install folder>: an extended regex for a command line holding a path inside
# that folder, after the line's start or a space. Looser than the line starting with it: a
# command given that path as an argument (a docker exec'd "ls /opt/ibm/tm1-engine/bin") matches
# too. It's only used as the container stops, when everything in it goes anyway.
install_pattern() {
  local quoted
  quoted=$(printf '%s' "${1%/}" | sed 's/[][\.*^$+?(){}|]/\\&/g')
  printf '(^| )%s/' "$quoted"
}

# kill_install <install folder>: kills (SIGKILL) whatever runs from the folder. Its processes run
# from where the install really is (the cache, behind a link), so the link is followed first.
kill_install() {
  local home
  home=$(readlink -f "$1") || return 0
  case "$home" in ''|/) return 0 ;; esac   # a pattern for / would match every process
  pkill -KILL -f "$(install_pattern "$home")" 2>/dev/null || true
}

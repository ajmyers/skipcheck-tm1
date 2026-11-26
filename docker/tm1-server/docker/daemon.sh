# SPDX-License-Identifier: Apache-2.0
# shellcheck shell=bash
#
# Finding a TM1 daemon's PID, for the entrypoint (which sources this file) and its tests.
#
# tm1s.exe and tm1admsrv.exe both daemonize: the launcher exits 0 and the real process keeps
# running, so the entrypoint finds it by its command line. That's matched wherever it starts,
# not only at the start of the line: under an emulator the line begins with the emulator's own
# path. Docker Desktop's Rosetta shows the server as
# "/run/rosetta/rosetta ./tm1s.exe ./tm1s.exe -w -z/tm1/config", and a pattern anchored at the
# start never matched it, so the entrypoint gave up and stopped a server that was running.

# daemon_pattern <command line as run>: an extended regex for that command line, at the end of
# the process's line, after its start or a space. Looser than the whole line being the command: a
# wrapper whose arguments end with it ("sh -c ./tm1admsrv.exe" from a docker exec) would match too,
# and the lowest PID is taken. Nothing in the image starts one; telling the emulator's prefix from
# any other would tie this to how each emulator writes the line.
daemon_pattern() {
  local quoted
  quoted=$(printf '%s' "$1" | sed 's/[][\.*^$+?(){}|]/\\&/g')
  printf '(^| )%s$' "$quoted"
}

# daemon_pid <command line as run> [tries]: waits for the daemon (a try a second, 30 by default)
# and prints its PID
daemon_pid() {
  local pattern pid
  pattern=$(daemon_pattern "$1")
  for _ in $(seq 1 "${2:-30}"); do
    pid=$(pgrep -f "$pattern" | head -1 || true)
    if [ -n "$pid" ]; then echo "$pid"; return 0; fi
    sleep 1
  done
  return 1
}

# SPDX-License-Identifier: Apache-2.0

# pa-setup: one command for a whole IBM Planning Analytics environment in Docker,
# described by pa-environment.yaml in the current folder. Windows twin of setup.sh.
#
#   & ([scriptblock]::Create((irm https://skipcheck.io/pa/setup.ps1)))          # asks where TM1 comes from, and starts it
#   & ([scriptblock]::Create((irm https://skipcheck.io/pa/setup.ps1))) --bundle $HOME\Downloads\tm1server-...-linuxi38664h_bundle.tar.gz
#   & ([scriptblock]::Create((irm https://skipcheck.io/pa/setup.ps1))) init     # write a starting file to edit
#   & ([scriptblock]::Create((irm https://skipcheck.io/pa/setup.ps1))) up       # start everything in the file
#   .\setup.ps1 urls | status | logs [service] | pull | down | generate | --help
#
# This script only checks Docker and runs the setup tool in a container, with the
# Docker socket and this folder mounted. Docker Desktop is all Windows needs.
$ErrorActionPreference = 'Stop'

# :edge (built from master) until the first release; PA_IMAGE_TAG picks the other images' tag
$tag = if ($env:PA_IMAGE_TAG) { $env:PA_IMAGE_TAG } else { 'edge' }
$image = if ($env:PA_SETUP_IMAGE) { $env:PA_SETUP_IMAGE } else { "ghcr.io/ajmyers/pa-setup:$tag" }

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  Write-Error 'pa-setup needs Docker Desktop: https://www.docker.com/products/docker-desktop/'
  exit 1
}
# Windows PowerShell 5.1 turns what a program writes to its error stream into an error of
# the script's own when it's redirected: not to be stopped by it, here and for the pull below
$ErrorActionPreference = 'Continue'
docker info 2>&1 | Out-Null
$running = $LASTEXITCODE -eq 0
$ErrorActionPreference = 'Stop'
if (-not $running) {
  Write-Error "Docker isn't running (docker info failed). Start Docker Desktop and try again."
  exit 1
}

if ((docker info --format '{{.OSType}}') -ne 'linux') {
  Write-Error "Docker is running Windows containers. Planning Analytics needs Linux containers: right-click the Docker icon in the taskbar, choose 'Switch to Linux containers...', then run this again."
  exit 1
}

# The setup tool's own image is fetched afresh where a new environment starts, and for
# pull: a copy from an earlier day wouldn't know what this script may ask of it. Without a
# network, or for an image of your own (PA_SETUP_IMAGE), what's here is used.
$first = if ($args.Count -gt 0) { [string]$args[0] } else { '' }
if ($first -eq '' -or $first.StartsWith('-') -or $first -eq 'init' -or $first -eq 'pull') {
  $ErrorActionPreference = 'Continue'
  docker pull -q $image 2>&1 | Out-Null
  $ErrorActionPreference = 'Stop'
}

$dir = (Get-Location).Path
$dockerArgs = @('run', '--rm', '-v', '//var/run/docker.sock:/var/run/docker.sock', '-v', "${dir}:/work",
  '-e', "PA_HOST_DIR=$dir", '-e', "PA_HOST_HOME=$HOME", '-e', "PA_SETUP_IMAGE=$image")
if ($env:PA_IMAGE_TAG) { $dockerArgs += @('-e', "PA_IMAGE_TAG=$($env:PA_IMAGE_TAG)") }
if ($env:CI) { $dockerArgs += @('-e', "CI=$($env:CI)") }
if (-not [Console]::IsInputRedirected) { $dockerArgs += '-it' }
& docker @dockerArgs $image @args
exit $LASTEXITCODE

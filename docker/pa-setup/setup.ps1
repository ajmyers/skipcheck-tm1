# SPDX-License-Identifier: Apache-2.0

# pa-setup: one command for a whole IBM Planning Analytics environment in Docker,
# described by pa-environment.yaml in the current folder. Windows twin of setup.sh.
#
#   & ([scriptblock]::Create((irm https://skipcheck.io/pa/setup.ps1))) init
#   & ([scriptblock]::Create((irm https://skipcheck.io/pa/setup.ps1))) up
#   .\setup.ps1 urls | status | logs [service] | down | generate
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
docker info *> $null
if ($LASTEXITCODE -ne 0) {
  Write-Error "Docker isn't running (docker info failed). Start Docker Desktop and try again."
  exit 1
}

$dir = (Get-Location).Path
$dockerArgs = @('run', '--rm', '-v', '//var/run/docker.sock:/var/run/docker.sock', '-v', "${dir}:/work",
  '-e', "PA_HOST_DIR=$dir", '-e', "PA_HOST_HOME=$HOME", '-e', "PA_SETUP_IMAGE=$image")
if ($env:PA_IMAGE_TAG) { $dockerArgs += @('-e', "PA_IMAGE_TAG=$($env:PA_IMAGE_TAG)") }
if (-not [Console]::IsInputRedirected) { $dockerArgs += '-it' }
& docker @dockerArgs $image @args
exit $LASTEXITCODE

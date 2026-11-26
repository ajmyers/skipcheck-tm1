# SPDX-License-Identifier: Apache-2.0

<#
.SYNOPSIS
  tm1-server setup for Windows: installs and starts a local IBM Planning Analytics
  (TM1) server with Docker Desktop, doing the steps in GETTING-STARTED.md and
  checking them as it goes.

.DESCRIPTION
  Interactive (asks the questions):
    irm https://skipcheck.io/tm1/setup.ps1 | iex

  With options (irm | iex can't pass parameters, so make a script block):
    & ([scriptblock]::Create((irm https://skipcheck.io/tm1/setup.ps1))) -Bundle "$HOME\Downloads\tm1server-...-linuxi38664h_bundle.tar.gz"

  Or download it and run it as a file:
    .\setup.ps1 -Bundle ... -Yes

  This is the Windows twin of setup.sh and takes the same options, in PowerShell
  spelling (--tm1-home is -Tm1Home, --files-dir is -FilesDir, ...), and the same
  TM1_SETUP_* environment variables. The options are a stable interface: the setup
  page on skipcheck.io builds one-liners from them, so keep the two scripts in step
  and don't rename or remove any.

  Questions are only asked in an interactive console with CI unset. Otherwise every
  answer comes from a parameter or a TM1_SETUP_* environment variable, and a missing
  required answer fails straight away, naming the parameter.

  Reruns are safe: an existing compose.yaml, .env and TM1 install are kept, and the
  script says what it reused.

.PARAMETER Bundle
  A: your IBM download, tm1server-<ver>-linuxi38664h_bundle.tar.gz. It's copied into
  bundles\ and installed on first start.
.PARAMETER Url
  B: a link to that file, e.g. a pre-signed S3/R2/GCS/Azure URL.
.PARAMETER Sha256
  With B: the file's SHA-256, checked before installing.
.PARAMETER Tm1Home
  C: an installed tm1_64 folder (the one containing bin64\).
.PARAMETER Source
  bundle, url, folder or image (or A, B, C, D). Only needed for image (D), with -Image.
.PARAMETER Dir
  The folder to set up TM1 in (default: .\tm1).
.PARAMETER FilesDir
  Where each server's config, data and logs go (default: tm1-files inside -Dir).
.PARAMETER Samples
  Also run IBM's SData sample server, on port 8011.
.PARAMETER NoSamples
  Don't (the default).
.PARAMETER Image
  The tm1-server image (default: ghcr.io/ajmyers/tm1-server:latest), e.g.
  ghcr.io/ajmyers/tm1-server:edge, or your private image (D).
.PARAMETER Yes
  Ask nothing: use the options given and the defaults.
.PARAMETER NoStart
  Set everything up, but don't start TM1.
.PARAMETER Timeout
  How long to wait for TM1 to become healthy, in seconds (default: 600).
.PARAMETER BaseUrl
  Where to download compose.yaml and .env.example from.
#>
[CmdletBinding()]
param(
  [string]$Dir,
  [string]$Source,
  [string]$Bundle,
  [string]$Url,
  [string]$Sha256,
  [string]$Tm1Home,
  [string]$Image,
  [string]$FilesDir,
  [switch]$Samples,
  [switch]$NoSamples,
  [switch]$Yes,
  [switch]$NoStart,
  [int]$Timeout = 0,
  [string]$BaseUrl,
  [Alias('h')][switch]$Help
)

Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is very slow with its progress bar

$DefaultBaseUrl = 'https://raw.githubusercontent.com/ajmyers/skipcheck-tm1/master/docker/tm1-server'
$Guide = 'https://github.com/ajmyers/skipcheck-tm1/blob/master/docker/tm1-server/GETTING-STARTED.md'
$DefaultImage = 'ghcr.io/ajmyers/tm1-server:latest'
$OnWindows = [System.Environment]::OSVersion.Platform -eq 'Win32NT'

# --- Output ---------------------------------------------------------------------
function Step([string]$m) { Write-Host ''; Write-Host "==> $m" -ForegroundColor White }
function Ok([string]$m)   { Write-Host '  ' -NoNewline; Write-Host 'OK' -ForegroundColor Green -NoNewline; Write-Host " $m" }
function Info([string]$m) { Write-Host "    $m" }
function Warn([string]$m) { Write-Host '  ' -NoNewline; Write-Host "! $m" -ForegroundColor Yellow }
# Who made this. Same text as setup.sh and the image's startup log (docker/banner.txt).
function Show-Banner {
  Write-Host ''
@'
  ------------------------------------------------------------------
   tm1-server: a local IBM Planning Analytics (TM1) server in Docker
   Free and open source, made by Andrew Myers (https://ajmyers.net)

     Website  https://skipcheck.io
     GitHub   https://github.com/ajmyers
     Issues   https://github.com/ajmyers/skipcheck-tm1/issues

   Useful? A star helps: https://github.com/ajmyers/skipcheck-tm1
   Need help with TM1? https://ajmyers.net
   Not affiliated with IBM. You need your own licensed copy of TM1.
  ------------------------------------------------------------------
'@ -split "`r?`n" | ForEach-Object { Write-Host $_ -ForegroundColor Cyan }
}

# A setup failure: a message and how to fix it. Thrown, not exit: under irm | iex,
# exit would close the user's PowerShell window.
$script:FailFix = @()
function Fail([string]$Message, [string[]]$Fix = @()) {
  $script:FailFix = $Fix
  throw (New-Object System.OperationCanceledException $Message)
}

# Runs docker without PowerShell turning its stderr into errors (Windows PowerShell
# 5.1 does, with ErrorActionPreference Stop). Returns the output lines; sets $LASTEXITCODE.
# Named Docker, so it shadows docker.exe for this script: it calls the real one by path.
$script:DockerExe = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
function Docker {
  $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try { & $script:DockerExe.Source @args 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $eap }
}
function DockerOk { $null = Docker @args; return $LASTEXITCODE -eq 0 }

function Show-Usage {
  @'
tm1-server setup for Windows: install and start a local TM1 server with Docker.

  irm https://skipcheck.io/tm1/setup.ps1 | iex
  & ([scriptblock]::Create((irm https://skipcheck.io/tm1/setup.ps1))) [options]

Where TM1 comes from (pick one; asked if not given):
  -Bundle FILE       A: your IBM download, tm1server-<ver>-linuxi38664h_bundle.tar.gz.
                        It's copied into bundles\ and installed on first start.
  -Url URL           B: a link to that file, e.g. a pre-signed S3/R2/GCS/Azure URL
  -Sha256 HASH          with B: the file's SHA-256, checked before installing
  -Tm1Home DIR       C: an installed tm1_64 folder (the one containing bin64\)
  -Source image      D: a private image with TM1 built in; name it with -Image
  -Source NAME       bundle, url, folder or image (or A, B, C, D). Only needed when
                     the options above don't already say which.

Other choices:
  -Dir DIR           The folder to set up TM1 in (default: .\tm1)
  -FilesDir DIR      Where each server's config, data and logs go
                     (default: tm1-files inside -Dir)
  -Samples           Also run IBM's SData sample server, on port 8011
  -NoSamples         Don't (the default)
  -Image IMAGE       The tm1-server image (default: ghcr.io/ajmyers/tm1-server:latest),
                     e.g. ghcr.io/ajmyers/tm1-server:edge, or your private image (D)

Running:
  -Yes               Ask nothing: use the options given and the defaults
  -NoStart           Set everything up, but don't start TM1
  -Timeout SECONDS   How long to wait for TM1 to become healthy (default: 600)
  -BaseUrl URL       Where to download compose.yaml and .env.example from
  -Help              Show this help

Each option can also be set in the environment as TM1_SETUP_<NAME>, with the name
in upper case and - as _: TM1_SETUP_BUNDLE, TM1_SETUP_FILES_DIR, TM1_SETUP_SAMPLES=1,
TM1_SETUP_YES=1, ... Parameters win over the environment.

Build the command for your choices at https://skipcheck.io/tm1/
'@ | Write-Host
}

function Expand-SetupPath([string]$p) {
  $p = $p.Trim('"')
  if ($p -eq '~') { $p = $HOME } elseif ($p -match '^~[\\/]') { $p = Join-Path $HOME $p.Substring(2) }
  # Combine, unlike Join-Path, keeps an absolute $p as it is
  return [System.IO.Path]::GetFullPath([System.IO.Path]::Combine((Get-Location).ProviderPath, $p)).TrimEnd('\', '/')
}
# Docker and compose want forward slashes in .env paths: C:/Users/...
function To-EnvPath([string]$p) { return $p -replace '\\', '/' }
# A value for .env, single-quoted so compose takes it literally (no $ interpolation)
function Env-Line([string]$Key, [string]$Value) {
  if ($Value.Contains("'")) { Fail "$Key can't contain a single quote ('): $Value" }
  return "$Key='$Value'"
}
function Write-Utf8([string]$Path, [string]$Text) {
  # No byte-order mark (Windows PowerShell 5.1's -Encoding UTF8 adds one) and LF endings
  [System.IO.File]::WriteAllText($Path, ($Text -replace "`r`n", "`n"), (New-Object System.Text.UTF8Encoding $false))
}

$BundleHelp = 'It should be named tm1server-<version>-linuxi38664h_bundle.tar.gz (for Planning Analytics 2.1.2 and 2.1.7, tm1_linuxi38664h_<version>_ml.tar.gz): the Linux download, even on Windows.'
function Test-Bundle([string]$File) {
  if (-not (Test-Path -LiteralPath $File -PathType Leaf)) { Fail "Bundle file not found: $File" @($BundleHelp) }
  $name = Split-Path -Leaf $File
  if ($name -notlike 'tm1server-*-linuxi38664h_bundle.tar.gz' -and $name -notlike 'tm1server-*-linuxi38664h.zip' -and $name -notlike 'tm1_linuxi38664h_*_ml.tar.gz') {
    if ($name -like '*tm1*') {
      Fail "$name isn't the Linux TM1 Server download." @($BundleHelp,
        'Download IBM Planning Analytics TM1 Server for Linux from IBM Passport Advantage or Fix Central.')
    }
    Fail "$name doesn't look like an IBM TM1 Server bundle." @($BundleHelp)
  }
  $fs = [System.IO.File]::OpenRead($File)
  try { $b = New-Object byte[] 2; $null = $fs.Read($b, 0, 2) } finally { $fs.Dispose() }
  $magic = '{0:x2}{1:x2}' -f $b[0], $b[1]
  $okMagic = if ($name -like '*.zip') { '504b' } else { '1f8b' }
  if ($magic -ne $okMagic) {
    Fail "$name is damaged or incomplete." @('Download it again; a partly finished download looks like this.')
  }
}

function Test-PortBusy([int]$Port) {
  $client = New-Object System.Net.Sockets.TcpClient
  try {
    $task = $client.ConnectAsync('127.0.0.1', $Port)
    return ($task.Wait(500) -and $client.Connected)
  } catch { return $false } finally { $client.Dispose() }
}

function Invoke-Setup {
  if ($Help) { Show-Usage; return }
  Show-Banner

  # Options: parameters override TM1_SETUP_* environment variables
  function FromEnv([string]$value, [string]$name) { if ($value) { return $value } return [Environment]::GetEnvironmentVariable($name) }
  $Dir = FromEnv $Dir 'TM1_SETUP_DIR'
  $Source = FromEnv $Source 'TM1_SETUP_SOURCE'
  $Bundle = FromEnv $Bundle 'TM1_SETUP_BUNDLE'
  $Url = FromEnv $Url 'TM1_SETUP_URL'
  $Sha256 = FromEnv $Sha256 'TM1_SETUP_SHA256'
  $Tm1Home = FromEnv $Tm1Home 'TM1_SETUP_TM1_HOME'
  $Image = FromEnv $Image 'TM1_SETUP_IMAGE'
  $FilesDir = FromEnv $FilesDir 'TM1_SETUP_FILES_DIR'
  $BaseUrl = FromEnv $BaseUrl 'TM1_SETUP_BASE_URL'; if (-not $BaseUrl) { $BaseUrl = $DefaultBaseUrl }
  $truthy = '^(1|true|yes|y)$'
  $SamplesWanted = $null
  if ($Samples) { $SamplesWanted = $true } elseif ($NoSamples) { $SamplesWanted = $false }
  elseif ($env:TM1_SETUP_SAMPLES) {
    if ($env:TM1_SETUP_SAMPLES -match $truthy) { $SamplesWanted = $true }
    elseif ($env:TM1_SETUP_SAMPLES -match '^(0|false|no|n)$') { $SamplesWanted = $false }
    else { Fail "TM1_SETUP_SAMPLES must be 1 or 0, not '$($env:TM1_SETUP_SAMPLES)'." }
  }
  $AskNothing = $Yes -or ($env:TM1_SETUP_YES -match $truthy)
  $DontStart = $NoStart -or ($env:TM1_SETUP_NO_START -match $truthy)
  if ($Timeout -le 0) {
    $t = 0
    if ($env:TM1_SETUP_TIMEOUT -and -not [int]::TryParse($env:TM1_SETUP_TIMEOUT, [ref]$t)) {
      Fail "TM1_SETUP_TIMEOUT must be a number of seconds, not '$($env:TM1_SETUP_TIMEOUT)'."
    }
    $Timeout = if ($t -gt 0) { $t } else { 600 }
  }

  # Ask only in an interactive console
  $noninteractiveHost = [Environment]::GetCommandLineArgs() | Where-Object { $_ -match '^-noni' }
  $Interactive = (-not $AskNothing) -and (-not $env:CI) -and [Environment]::UserInteractive -and
                 (-not [Console]::IsInputRedirected) -and (-not $noninteractiveHost)
  function Ask([string]$Prompt, [string]$Default = '') {
    $text = if ($Default) { "$Prompt [$Default]" } else { $Prompt }
    $reply = Read-Host $text
    if ($reply) { return $reply.Trim() } return $Default
  }
  function Confirm([string]$Prompt, [bool]$Default) {
    while ($true) {
      $r = Ask ($Prompt + $(if ($Default) { ' (Y/n)' } else { ' (y/N)' }))
      if (-not $r) { return $Default }
      if ($r -match '^(y|yes)$') { return $true }
      if ($r -match '^(n|no)$') { return $false }
    }
  }

  # --- 1. Check the computer and Docker -------------------------------------------
  Step 'Checking your computer'
  if ($OnWindows) {
    $arch = $env:PROCESSOR_ARCHITECTURE
    if ($env:PROCESSOR_ARCHITEW6432) { $arch = $env:PROCESSOR_ARCHITEW6432 }
    Ok "Windows ($arch)"
    if ($arch -eq 'ARM64') { Warn 'This is an ARM PC, so TM1 runs through emulation, which is slower.' }
  } else {
    Warn 'This is the Windows script. On Mac and Linux, setup.sh is the one to use; carrying on anyway.'
  }

  if (-not $script:DockerExe) {
    Fail "Docker isn't installed." @(
      'Install Docker Desktop from https://www.docker.com/products/docker-desktop/',
      '(or: winget install Docker.DockerDesktop). Keep "Use WSL 2" selected, restart if asked,',
      'open Docker Desktop and accept the terms, then run this setup again.')
  }
  if (-not (DockerOk info)) {
    $desktop = Join-Path ([string]$env:ProgramFiles) 'Docker\Docker\Docker Desktop.exe'
    if ($OnWindows -and (Test-Path -LiteralPath $desktop)) {
      Info 'Starting Docker Desktop...'
      Start-Process -FilePath $desktop
      $deadline = (Get-Date).AddSeconds(180)
      while (-not (DockerOk info)) {
        if ((Get-Date) -gt $deadline) {
          Fail "Docker Desktop didn't start within 3 minutes." @(
            'Open Docker Desktop, wait until it says the engine is running, then run this setup again.')
        }
        Start-Sleep -Seconds 3
      }
    } else {
      Fail "Docker isn't running." @('Open Docker Desktop, wait until it says the engine is running, then run this setup again.')
    }
  }
  if (-not (DockerOk compose version)) {
    Fail "Docker Compose isn't available." @('Update Docker Desktop, then run this setup again.')
  }
  if ((Docker info --format '{{.OSType}}') -ne 'linux') {
    Fail 'Docker is running Windows containers. TM1 needs Linux containers.' @(
      "Right-click the Docker icon in the taskbar, choose 'Switch to Linux containers...', then run this setup again.")
  }
  Ok "Docker $(Docker version --format '{{.Server.Version}}') and Compose $(Docker compose version --short) are running"
  if ($OnWindows) {
    $kernel = "$(Docker info --format '{{.KernelVersion}}')"
    if ($kernel -match 'WSL2') { Ok 'Docker uses WSL 2' }
    else {
      Warn "Docker Desktop isn't using WSL 2 (kernel $kernel). TM1 may be slow to start and file access slower."
      Info "To switch: Docker Desktop > Settings > General > 'Use the WSL 2 based engine'."
    }
  }
  $mem = 0; [long]::TryParse("$(Docker info --format '{{.MemTotal}}')", [ref]$mem) | Out-Null
  if ($mem -gt 0 -and $mem -lt 3500000000) {
    Warn "Docker has $([math]::Round($mem / 1MB)) MB of memory; TM1 wants about 4 GB. With WSL 2, raise it in %UserProfile%\.wslconfig (memory=4GB)."
  }

  # --- 2. Choices -----------------------------------------------------------------
  if ($Interactive) { Step 'A few questions (press Enter for the default)' }
  if (-not $Dir) { $Dir = if ($Interactive) { Ask 'Folder to set up TM1 in' '.\tm1' } else { '.\tm1' } }
  $Dir = Expand-SetupPath $Dir
  if ((Test-Path -LiteralPath $Dir) -and -not (Test-Path -LiteralPath $Dir -PathType Container)) {
    Fail "$Dir exists and isn't a folder." @('Choose another with -Dir.')
  }
  $envFile = Join-Path $Dir '.env'
  function Env-Get([string]$Key) {
    if (-not (Test-Path -LiteralPath $envFile)) { return '' }
    $line = Get-Content -LiteralPath $envFile | Where-Object { $_ -match "^\s*$Key=" } | Select-Object -Last 1
    if (-not $line) { return '' }
    return ($line -replace "^\s*$Key=", '').Trim().Trim("'", '"')
  }

  # The compose project name, as compose derives it from the folder name
  $project = ((Split-Path -Leaf $Dir).ToLowerInvariant() -replace '[^a-z0-9_-]', '')
  $volume = "$($project)_tm1-install"
  $haveVolume = DockerOk volume inspect $volume
  $haveEnvSource = [bool]((Env-Get 'TM1_BUNDLE_URL') -or (Env-Get 'TM1_HOME_DIR') -or (Env-Get 'TM1_BUNDLE_DIR'))
  $bundlesDir = Join-Path $Dir 'bundles'
  $haveBundles = [bool]((Get-ChildItem -LiteralPath $bundlesDir -Filter 'tm1server-*' -File -ErrorAction SilentlyContinue) +
    (Get-ChildItem -LiteralPath $bundlesDir -Filter 'tm1_linuxi38664h_*_ml.tar.gz' -File -ErrorAction SilentlyContinue))

  switch -Regex ($Source) {
    '^$' { break }
    '^(a|bundle)$' { $Source = 'bundle'; break }
    '^(b|url)$' { $Source = 'url'; break }
    '^(c|folder)$' { $Source = 'folder'; break }
    '^(d|image)$' { $Source = 'image'; break }
    default { Fail "Unknown -Source '$Source'." @('Use bundle, url, folder or image (or A, B, C, D).') }
  }
  $given = @()
  if ($Bundle) { $given += 'bundle' }
  if ($Url) { $given += 'url' }
  if ($Tm1Home) { $given += 'folder' }
  $paramFor = @{ bundle = '-Bundle'; url = '-Url'; folder = '-Tm1Home' }
  if ($given.Count -gt 1) { Fail "Choose one TM1 source: you gave $(($given | ForEach-Object { $paramFor[$_] }) -join ' ')." }
  if ($given.Count -eq 1) {
    if (-not $Source) { $Source = $given[0] }
    elseif ($Source -ne $given[0]) { Fail "-Source $Source doesn't match the $($paramFor[$given[0]]) you gave." }
  }

  if (-not $Source) {
    if ($haveVolume -or $haveEnvSource -or $haveBundles) { $Source = 'existing' }
    elseif ($Interactive) {
      Write-Host ''
      Write-Host 'Where does TM1 come from?'
      Write-Host '  A  The IBM download file (tm1server-...-linuxi38664h_bundle.tar.gz)   [most people]'
      Write-Host '  B  That file in cloud storage, as a download link (e.g. pre-signed S3 URL)'
      Write-Host '  C  An installed tm1_64 folder, copied from a Linux TM1 server'
      Write-Host '  D  A private Docker image someone built with TM1 inside'
      while (-not $Source) {
        switch -Regex (Ask 'Choose A, B, C or D' 'A') {
          '^a$' { $Source = 'bundle' } '^b$' { $Source = 'url' } '^c$' { $Source = 'folder' } '^d$' { $Source = 'image' }
        }
      }
    } else {
      Fail 'No TM1 source given, and nothing to ask on (not an interactive console, or CI is set).' @(
        'Give one of:  -Bundle FILE  |  -Url URL  |  -Tm1Home DIR  |  -Source image -Image IMAGE')
    }
  }

  switch ($Source) {
    'bundle' {
      if (-not $Bundle) {
        if (-not $Interactive) { Fail 'No bundle file given.' @('Use -Bundle FILE.') }
        $newest = Get-ChildItem -Path (Join-Path $HOME 'Downloads') -Filter 'tm1server-*-linuxi38664h_bundle.tar.gz' -File -ErrorAction SilentlyContinue |
          Sort-Object LastWriteTime -Descending | Select-Object -First 1
        $Bundle = Ask 'Path to the bundle file' $(if ($newest) { $newest.FullName } else { '' })
        if (-not $Bundle) { Fail 'No bundle file given.' @('Use -Bundle FILE.') }
      }
      $Bundle = Expand-SetupPath $Bundle
      Test-Bundle $Bundle
    }
    'url' {
      if (-not $Url) {
        if (-not $Interactive) { Fail 'No bundle URL given.' @('Use -Url URL.') }
        $Url = Ask 'Download link for the bundle'
        if (-not $Sha256) { $Sha256 = Ask 'Its SHA-256 (optional, Enter to skip)' }
      }
      if ($Url -notmatch '^https?://') { Fail "The bundle URL must start with https:// (or http://): $Url" }
      if ($Sha256) {
        $Sha256 = $Sha256.ToLowerInvariant()
        if ($Sha256 -notmatch '^[0-9a-f]{64}$') { Fail '-Sha256 must be 64 hex characters.' }
      }
    }
    'folder' {
      if (-not $Tm1Home) {
        if (-not $Interactive) { Fail 'No TM1 folder given.' @('Use -Tm1Home DIR.') }
        $Tm1Home = Ask 'Path to the tm1_64 folder'
      }
      $Tm1Home = Expand-SetupPath $Tm1Home
      foreach ($exe in 'tm1s.exe', 'tm1admsrv.exe') {
        if (-not (Test-Path -LiteralPath (Join-Path (Join-Path $Tm1Home 'bin64') $exe))) {
          Fail "$Tm1Home isn't an installed TM1 Server: there's no bin64\$exe in it." @(
            'Point -Tm1Home at the tm1_64 folder of a Linux TM1 Server install, copied to this PC.')
        }
      }
    }
    'image' {
      if (-not $Image) {
        if (-not $Interactive) { Fail 'No image given for -Source image.' @('Use -Image IMAGE, e.g. -Image registry.example.com/private/tm1-server:2.1.19') }
        $Image = Ask 'The private image (e.g. registry.example.com/private/tm1-server:2.1.19)'
        if (-not $Image) { Fail 'No image given.' @('Use -Image IMAGE.') }
      }
    }
  }

  if (-not $FilesDir -and $Interactive -and -not (Test-Path -LiteralPath $envFile)) {
    $defaultFiles = Join-Path $Dir 'tm1-files'
    $FilesDir = Ask "Folder for TM1's config, data and logs" $defaultFiles
    if ($FilesDir -eq $defaultFiles) { $FilesDir = '' }
  }
  if ($FilesDir) { $FilesDir = Expand-SetupPath $FilesDir }

  if ($null -eq $SamplesWanted -and (Env-Get 'COMPOSE_PROFILES') -match 'samples') { $SamplesWanted = $true }
  if ($null -eq $SamplesWanted) {
    $SamplesWanted = $Interactive -and (Confirm "Also start IBM's SData sample database (port 8011)?" $false)
  }

  # --- Summary --------------------------------------------------------------------
  Step 'Plan'
  Info "Folder:     $Dir"
  switch ($Source) {
    'bundle'   { Info "TM1 from:   $Bundle" }
    'url'      { Info "TM1 from:   $($Url -replace '\?.*', '?...')$(if ($Sha256) { ' (SHA-256 checked)' })" }
    'folder'   { Info "TM1 from:   $Tm1Home" }
    'image'    { Info "TM1 from:   the image $Image" }
    'existing' { Info "TM1 from:   what's already set up there" }
  }
  $showImage = if ($Image) { $Image } elseif (Env-Get 'TM1_SERVER_IMAGE') { Env-Get 'TM1_SERVER_IMAGE' } else { $DefaultImage }
  $showFiles = if ($FilesDir) { $FilesDir } elseif (Env-Get 'TM1_FILES_DIR') { Env-Get 'TM1_FILES_DIR' } else { Join-Path $Dir 'tm1-files' }
  Info "Image:      $showImage"
  Info "Your files: $showFiles"
  Info "Servers:    tm1 (empty database)$(if ($SamplesWanted) { ', SData sample' })"
  if ($Interactive) {
    Write-Host ''
    if (-not (Confirm 'Go ahead?' $true)) { Write-Host 'Nothing changed.'; return }
  }

  # --- 3. Check for conflicts before changing anything -----------------------------
  Step 'Checking ports and containers'
  # The folder a compose container was started from ('' if none). The labels come back
  # as JSON: Windows PowerShell 5.1 mangles double quotes in native arguments, so a
  # template like {{index .Config.Labels "..."}} can't be passed to docker.
  function Owner-Dir([string]$c) {
    $json = (Docker inspect -f '{{json .Config.Labels}}' $c) -join ''
    if ($LASTEXITCODE -ne 0 -or -not $json.StartsWith('{')) { return '' }
    $p = (ConvertFrom-Json $json).PSObject.Properties['com.docker.compose.project.working_dir']
    if ($p) { return [string]$p.Value } return ''
  }
  function Same-Dir([string]$a) {
    if (-not $a) { return $false }
    try { return (Expand-SetupPath $a) -eq $Dir } catch { return $false }
  }
  foreach ($c in 'tm1-admin', 'tm1', 'tm1-sdata') {
    if (-not (DockerOk container inspect $c)) { continue }
    $from = Owner-Dir $c
    if (Same-Dir $from) { continue }
    if ($from) {
      Fail "A container named $c already exists, from a TM1 setup in $from." @(
        "Only one setup can run at a time. Use that one (rerun with -Dir '$from'), or remove it:",
        "  cd '$from'; docker compose --profile samples down")
    }
    Fail "A container named $c already exists, not started by tm1-server's compose.yaml." @("Remove it with:  docker rm -f $c")
  }
  $ports = @(5895, 5495, 8010, 12345, 12346); if ($SamplesWanted) { $ports += 8011 }
  foreach ($p in $ports) {
    $c = Docker ps --filter "publish=$p" --format '{{.Names}}' | Select-Object -First 1
    if ($c) {
      if (Same-Dir (Owner-Dir $c)) { continue }
      Fail "Port $p is in use by the Docker container $c." @("Stop it (docker stop $c), or change the left-hand port numbers under ports: in compose.yaml.")
    }
    if (Test-PortBusy $p) {
      Fail "Port $p is in use by another program (perhaps another TM1)." @(
        "Stop that program, or change the left-hand port numbers under ports: in $(Join-Path $Dir 'compose.yaml'), e.g. '9010:8010'.")
    }
  }
  Ok "Ports $($ports -join ',') are free"

  # --- 4. Set up the folder ----------------------------------------------------------
  Step "Setting up $Dir"
  $null = New-Item -ItemType Directory -Force -Path $bundlesDir
  function Fetch([string]$Name, [string]$Dest) {
    try {
      if ($BaseUrl -match '://') {
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/$Name" -OutFile "$Dest.tmp"
      } else { Copy-Item -LiteralPath (Join-Path $BaseUrl $Name) -Destination "$Dest.tmp" }
      Move-Item -Force -LiteralPath "$Dest.tmp" -Destination $Dest
    } catch {
      Remove-Item -Force -ErrorAction SilentlyContinue -LiteralPath "$Dest.tmp"
      Fail "Couldn't download $Name from $BaseUrl." @('Check your internet connection and try again.')
    }
  }
  $composeFile = Join-Path $Dir 'compose.yaml'
  if (Test-Path -LiteralPath $composeFile) { Ok 'Kept the existing compose.yaml' }
  else { Fetch 'compose.yaml' $composeFile; Ok 'Downloaded compose.yaml' }
  Fetch '.env.example' (Join-Path $Dir '.env.example'); Ok 'Downloaded .env.example (every setting, explained)'

  $newBundle = $false
  if ($Source -eq 'bundle') {
    $dest = Join-Path $bundlesDir (Split-Path -Leaf $Bundle)
    if ((Test-Path -LiteralPath $dest) -and (Get-Item -LiteralPath $dest).Length -eq (Get-Item -LiteralPath $Bundle).Length) {
      Ok 'The bundle is already in bundles\'
    } else {
      Info "Copying the bundle into bundles\ ($([math]::Round((Get-Item -LiteralPath $Bundle).Length / 1MB)) MB)..."
      Copy-Item -LiteralPath $Bundle -Destination "$dest.tmp"
      Move-Item -Force -LiteralPath "$dest.tmp" -Destination $dest
      $newBundle = $true
      Ok "Copied $(Split-Path -Leaf $Bundle) into bundles\"
    }
  }

  # The settings this run wants in .env
  $want = [ordered]@{}
  switch ($Source) {
    'bundle' {
      # Pin the bundle when bundles\ has others, which would otherwise win if newer
      if (@(Get-ChildItem -LiteralPath $bundlesDir -Filter 'tm1server-*' -File).Count + @(Get-ChildItem -LiteralPath $bundlesDir -Filter 'tm1_linuxi38664h_*_ml.tar.gz' -File).Count -gt 1) { $want['TM1_BUNDLE'] = Split-Path -Leaf $Bundle }
    }
    'url' { $want['TM1_BUNDLE_URL'] = $Url; if ($Sha256) { $want['TM1_BUNDLE_SHA256'] = $Sha256 } }
    'folder' { $want['TM1_HOME_DIR'] = To-EnvPath $Tm1Home }
  }
  if ($Image) { $want['TM1_SERVER_IMAGE'] = $Image }
  if ($FilesDir) { $want['TM1_FILES_DIR'] = To-EnvPath $FilesDir }
  if ($SamplesWanted) { $want['COMPOSE_PROFILES'] = 'samples' }

  $profileArgs = @()
  if (Test-Path -LiteralPath $envFile) {
    Ok 'Kept the existing .env'
    foreach ($k in @($want.Keys)) {
      if ((Env-Get $k) -ne $want[$k]) {
        if ($k -eq 'COMPOSE_PROFILES') { $profileArgs = @('--profile', 'samples'); continue }
        Warn "Not applied, because .env already exists: $k=$($want[$k]). Edit $envFile to change it."
      }
    }
  } else {
    $nl = [string][char]10
    $lines = @($want.Keys | ForEach-Object { Env-Line $_ $want[$_] })
    $text = (Get-Content -Raw -LiteralPath (Join-Path $Dir '.env.example')) + $nl + "# --- Set by setup.ps1 on $(Get-Date -Format 'yyyy-MM-dd') ---" + $nl + (($lines | ForEach-Object { $_ + $nl }) -join '')
    Write-Utf8 $envFile $text
    Ok "Wrote .env$(if ($want.Count) { ' (' + (@($want.Keys) -join ', ') + ')' })"
  }

  if ($haveVolume -and ($newBundle -or $Source -eq 'url')) {
    Warn "TM1 is already installed in the Docker volume $volume, so that install is used, not the bundle given here."
    Info "To install this one instead: cd '$Dir'; docker compose down; docker volume rm $volume; then run this setup again."
  } elseif ($haveVolume) { Ok "Reusing the TM1 install in the Docker volume $volume" }

  if ($DontStart) {
    Step 'Ready, not started (-NoStart)'
    Info "To start TM1:  cd '$Dir'; docker compose up -d"
    return
  }

  # --- 5. Start TM1 --------------------------------------------------------------------
  Step 'Starting TM1'
  Push-Location -LiteralPath $Dir
  try {
    $images = @(Docker compose @profileArgs config --images | Sort-Object -Unique)
    if ($LASTEXITCODE -ne 0) { Fail "compose.yaml or .env has a problem: $($images -join ' ')" }
    foreach ($img in $images) {
      if (DockerOk image inspect $img) { Ok "Using the image $img (already downloaded; docker compose pull updates it)"; continue }
      Info "Downloading $img..."
      if (-not (DockerOk pull --platform linux/amd64 -q $img)) {
        if ($img -like 'ghcr.io/ajmyers/tm1-server*') {
          Fail "Couldn't download the image $img." @('Check your internet connection, and that your network allows ghcr.io.',
            'Before the first release, only :edge exists: add -Image ghcr.io/ajmyers/tm1-server:edge')
        }
        Fail "Couldn't download the image $img." @("For a private registry, log in first:  docker login $($img.Split('/')[0])")
      }
      Ok "Downloaded $img"
    }
    if (-not (DockerOk run --rm --platform linux/amd64 --entrypoint true $images[0])) {
      Fail "Docker can't run x86-64 containers on this machine." @('Update Docker Desktop, then run this setup again.')
    }

    $out = Docker compose @profileArgs up -d
    if ($LASTEXITCODE -ne 0) {
      $out | Write-Host
      Fail 'docker compose up failed (the reason is above).' @('See the Troubleshooting section of the guide.')
    }
    $services = @(Docker compose @profileArgs ps -a --format '{{.Name}}' | Sort-Object)
    Ok "Started $($services -join ', ')"

    # --- 6. Wait until healthy -----------------------------------------------------------
    if (-not $haveVolume -and ($Source -eq 'bundle' -or $Source -eq 'url')) {
      Info 'Waiting for TM1 to be ready (the first start installs TM1 from the bundle, about 15 s)...'
    } else { Info 'Waiting for TM1 to be ready...' }
    function Fail-WithLogs([string]$c, [string]$what) {
      Docker logs --tail 40 $c | Write-Host
      $svc = if ($c -eq 'tm1-sdata') { 'sdata' } else { $c }
      Fail "$c $what." @('Its log is above: lines starting [tm1-entrypoint] explain what happened.',
        "Full log:  cd '$Dir'; docker compose logs $svc")
    }
    $deadline = (Get-Date).AddSeconds($Timeout)
    $pending = $services
    while ($pending.Count) {
      $still = @()
      foreach ($c in $pending) {
        $st = "$(Docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} {{.RestartCount}}' $c)"
        if ($LASTEXITCODE -ne 0) { $st = 'missing none 0' }
        $status, $health, $restarts = $st.Trim() -split ' '
        if ($status -eq 'running' -and $health -eq 'healthy') { Ok "$c is healthy" }
        elseif ($health -eq 'unhealthy') { Fail-WithLogs $c 'is unhealthy' }
        elseif ($status -in 'exited', 'dead', 'restarting', 'missing') { Fail-WithLogs $c 'stopped' }
        elseif (($status -eq 'running' -and $restarts -eq '0') -or $status -eq 'created') { $still += $c }
        else { Fail-WithLogs $c 'keeps restarting' }
      }
      $pending = $still
      if (-not $pending.Count) { break }
      if ((Get-Date) -gt $deadline) { Fail-WithLogs $pending[0] "didn't become healthy within $Timeout seconds" }
      Start-Sleep -Seconds 3
    }
  } finally { Pop-Location }

  $version = ''
  try {
    $auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('admin:'))
    $version = (Invoke-WebRequest -UseBasicParsing -Headers @{ Authorization = $auth } -Uri 'http://localhost:8010/api/v1/Configuration/ProductVersion/$value').Content
  } catch { }

  # --- Done -------------------------------------------------------------------------------
  Step "TM1 is running$(if ($version) { ': version ' + $version })"
  Write-Host ''
  Write-Host '  tm1 (empty database)   http://localhost:8010/api/v1/   user admin, no password'
  if ($services -contains 'tm1-sdata') { Write-Host '  SData (sample)         http://localhost:8011/api/v1/   user admin, password apple' }
  Write-Host '  Admin Server           http://localhost:5895/api/v1/Servers'
  Write-Host ''
  Write-Host '  Connect your tools to host localhost, port 8010, SSL off.'
  Write-Host "  Your files:            $showFiles"
  Write-Host ''
  Write-Host "  Everyday commands, run in $($Dir):"
  Write-Host "    docker compose ps            see what's running"
  Write-Host '    docker compose logs -f tm1   watch the TM1 log'
  Write-Host '    docker compose stop          stop (TM1 saves first)'
  Write-Host '    docker compose up -d         start again'
}

# Run. A failure prints its message and fix. When run as a file (.\setup.ps1, or
# pwsh -File in CI) it also exits 1; under irm | iex it only returns, because exit
# would close the user's window.
$failed = $false
try { Invoke-Setup }
catch [System.OperationCanceledException] {
  $failed = $true
  Write-Host ''
  Write-Host 'Error: ' -ForegroundColor Red -NoNewline; Write-Host $_.Exception.Message
  foreach ($line in $script:FailFix) { Write-Host "  $line" }
  Write-Host ''
  Write-Host "Help: $Guide"
  Write-Host 'Still stuck? Open an issue: https://github.com/ajmyers/skipcheck-tm1/issues'
}
if ($failed -and $PSCommandPath) { exit 1 }

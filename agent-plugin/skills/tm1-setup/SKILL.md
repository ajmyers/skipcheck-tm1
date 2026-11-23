---
name: tm1-setup
description: Set up and start a local IBM Planning Analytics (TM1) server in Docker with tm1-server, check that it's healthy, and troubleshoot it. Use when the user wants TM1 running on their own machine (Mac, Windows or Linux), asks to install or start tm1-server, or has trouble with a local tm1-server.
argument-hint: "[path to bundle, URL, or what you have]"
---

# Set up TM1 locally with tm1-server

tm1-server (https://github.com/ajmyers/skipcheck-tm1, by Andrew Myers) runs
IBM Planning Analytics TM1 Server in Docker for development and tests. It contains no
IBM software: the user brings their own licensed TM1 Server for Linux. Your job is to
get it running for them with the setup script, check it, and explain how to use it.

If the user gave arguments, treat them as what they have, e.g. a bundle path: $ARGUMENTS

## Rules

- **Never upload, copy elsewhere, or share the IBM bundle or an installed TM1.** It's
  licensed IBM software. Only point the script at where it already is.
- **Never print, log or repeat pre-signed URLs** (S3, R2, GCS, Azure SAS links). Their
  query string carries credentials. Pass them to the script in a variable.
- **Ask before anything destructive:** `docker compose down`, removing volumes
  (`*_tm1-install`), deleting `tm1-files`, or stopping containers from another setup.
- Run the script **non-interactively, with flags.** You don't have a terminal to answer
  its questions; without a terminal it fails fast and names the missing flag.

## 1. Find out what the user has

Ask only for what you can't find out yourself:

1. **OS.** Detect it (`uname -s`, or that you're on Windows).
2. **Where TM1 comes from** (one of):
   - **A. The IBM bundle file**, `tm1server-<version>-linuxi38664h_bundle.tar.gz` (about 500 MB,
     the *Linux* download even on Mac or Windows). Look in `~/Downloads` first:
     `ls -t ~/Downloads/tm1server-*-linuxi38664h_bundle.tar.gz`. If it's a Windows or AIX
     bundle, tell them to download the Linux one from IBM Passport Advantage or Fix Central.
   - **B. A download link** to that file (e.g. a pre-signed S3 URL), optionally with its SHA-256.
   - **C. An installed `tm1_64` folder** copied from a Linux TM1 server (contains `bin64/tm1s.exe`).
   - **D. A private Docker image** with TM1 built in (e.g. `registry.example.com/private/tm1-server:2.1.19`).
3. **Optional:** the folder to set up in (default `./tm1`; suggest `~/tm1`), a separate folder
   for config/data/logs, and whether to also start IBM's **SData** sample (port 8011).

If they don't have TM1 at all, explain they need their own licensed copy from IBM; you
can't get it for them.

## 2. Check Docker first

`docker info` must work, and `docker compose version`. If Docker is missing, point them to
https://www.docker.com/products/docker-desktop/ (Mac/Windows) or
https://docs.docker.com/engine/install/ (Linux): installing it needs admin rights and a
license acceptance, so the user does it. The script checks the rest (Rosetta on Apple
Silicon, WSL 2 on Windows, free ports) and explains any fix.

## 3. Run the setup script

**Mac / Linux:**
```sh
curl -fsSL https://skipcheck.io/tm1/setup.sh | sh -s -- --yes --dir ~/tm1 --bundle ~/Downloads/tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz
```
**Windows (PowerShell):**
```powershell
& ([scriptblock]::Create((irm https://skipcheck.io/tm1/setup.ps1))) -Yes -Dir "$HOME\tm1" -Bundle "$HOME\Downloads\tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz"
```

Pick the source flag from step 1:

| Source | setup.sh | setup.ps1 |
|---|---|---|
| A. bundle file | `--bundle FILE` | `-Bundle FILE` |
| B. link | `--url "$URL" [--sha256 HASH]` | `-Url $url [-Sha256 HASH]` |
| C. installed folder | `--tm1-home DIR` | `-Tm1Home DIR` |
| D. private image | `--source image --image IMAGE` | `-Source image -Image IMAGE` |

Other flags: `--dir DIR` / `-Dir`, `--files-dir DIR` / `-FilesDir`, `--samples` / `-Samples`,
`--image IMAGE` / `-Image` (the tm1-server image; default `ghcr.io/ajmyers/tm1-server:latest`),
`--no-start` / `-NoStart`, `--timeout SECONDS` / `-Timeout`. Run with `--help` / `-Help` for all.

- **If the download returns HTML instead of a script** (some networks get a bot check from
  skipcheck.io), use the same script from GitHub:
  `https://raw.githubusercontent.com/ajmyers/skipcheck-tm1/master/docker/tm1-server/setup.sh`
  (or `setup.ps1`).
- **If the image pull fails with `:latest`** (before the first release), add
  `--image ghcr.io/ajmyers/tm1-server:edge` / `-Image ghcr.io/ajmyers/tm1-server:edge`.
- **Reruns are safe:** an existing `.env`, `compose.yaml` and TM1 install are kept. Flags that
  would change a kept `.env` aren't applied; the script warns, and the user edits `.env`.
- The first start installs TM1 from the bundle (about 15 s); later starts take a few seconds.

## 4. Check it worked

The script waits for every container to be healthy and prints the TM1 version. Confirm:
```sh
cd ~/tm1 && docker compose ps            # tm1 and tm1-admin (and tm1-sdata) show (healthy)
curl -u admin: 'http://localhost:8010/api/v1/Configuration/ProductVersion/$value'
```
On Windows use `curl.exe`, with a backtick before `$value` in double quotes.

Then tell the user:
- **tm1** (empty database): `http://localhost:8010/api/v1/`, user `admin`, no password.
- **SData** (if started): `http://localhost:8011/api/v1/`, user `admin`, password `apple`.
- **Admin Server:** `http://localhost:5895/api/v1/Servers`.
- For tools: host `localhost`, port 8010, SSL off. Their files are in `tm1-files/` in the folder.
- Everyday: `docker compose ps`, `logs -f tm1`, `stop`, `up -d` (in that folder).

## 5. Troubleshooting

Read the script's error first: each one says what to do. Then `docker compose logs tm1`
(or `tm1-admin`): lines starting `[tm1-entrypoint]` explain what happened.

| Symptom | Fix |
|---|---|
| `no bundle found` / `tm1s.exe not found` | Wrong source: check the file name is the Linux bundle, or that `--tm1-home` has `bin64/`. |
| `download failed` / `checksum mismatch` | The link expired or is wrong; get a new one or fix the SHA-256. |
| Port in use | Something else (often another TM1) uses 8010/8011/5895/12345/12346. Stop it, or change the left-hand ports in `compose.yaml`. |
| Container name already exists | Another tm1-server setup exists; only one runs at a time. Use that folder, or (after asking) `docker compose --profile samples down` there. |
| Slow or crashing on Apple Silicon | Rosetta is off: Docker Desktop → Settings → General → *Use Rosetta for x86_64/amd64 emulation*. |
| Windows containers mode | Docker Desktop → *Switch to Linux containers*. |
| Logins fail, `IntegratedSecurityMode` warning | Their `tm1s.cfg` uses CAM security; set `IntegratedSecurityMode=1`. |

Full guide: https://github.com/ajmyers/skipcheck-tm1/blob/master/docker/tm1-server/GETTING-STARTED.md
If it's a bug in tm1-server, suggest opening an issue: https://github.com/ajmyers/skipcheck-tm1/issues

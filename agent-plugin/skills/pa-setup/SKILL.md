---
name: pa-setup
description: Set up a local IBM Planning Analytics environment in Docker with pa-setup and pa-environment.yaml, in any combination of TM1 servers (with a shared Admin Server), TM1 Web, Planning Analytics Workspace (PAW), and Cognos Analytics security (CAM) with test users through LDAP and OpenID Connect (Keycloak). Check it's healthy, and troubleshoot it. Use when the user wants more than one TM1 server, or TM1 Web, PAW or Cognos, on their own machine (Mac, Windows or Linux), or has trouble with a pa-setup environment.
argument-hint: "[what to run, e.g. two TM1 servers and TM1 Web, or the folder with your IBM downloads]"
---

# Set up Planning Analytics locally with pa-setup

pa-setup (https://github.com/ajmyers/skipcheck-tm1, by Andrew Myers) runs a whole IBM
Planning Analytics environment in Docker from one file, `pa-environment.yaml`. It
generates a compose project, starts it, waits until every part is healthy, and prints
every address and login. It contains no IBM software: the user brings their own licensed
IBM downloads. Your job is to write the file for what they want, run pa-setup, check the
result, and explain how to use it.

If the user gave arguments, treat them as what they want or have: $ARGUMENTS

For a single TM1 server and nothing else, there's a short way with no file to write:
[One TM1 server, the short way](#one-tm1-server-the-short-way), below. It writes the same
`pa-environment.yaml`, so anything more can be added to it afterwards.

## Rules

- **Never upload, copy elsewhere, or share the IBM downloads** or anything installed from
  them. They're licensed IBM software. Point the file at where they already are.
- **Never print, log or repeat pre-signed URLs** (S3, R2, GCS, Azure SAS links). Their
  query string carries credentials. They go in `.env`, never in `pa-environment.yaml`.
- **Never accept IBM's PAW license for the user.** `paw.acceptLicense: true` is their
  decision. Show them how to read it (below) and ask.
- **Ask before anything destructive or disruptive:** `down`, removing Docker volumes,
  deleting `pa-files`, or stopping containers from another setup.
- Every command runs **in the folder that holds `pa-environment.yaml`**: the scripts mount
  the current folder. pa-setup asks questions only when it's run with no command, no
  `pa-environment.yaml` and a terminal: give it a command (`init`, `up`), or `--yes`, and it
  asks nothing.

## One TM1 server, the short way

When the user wants one TM1 server (and IBM's SData sample, if they like), say where TM1
comes from and pa-setup writes the file and starts it, in the current folder:

```sh
mkdir -p ~/pa && cd ~/pa
curl -fsSL https://skipcheck.io/pa/setup.sh | sh -s -- --yes --bundle ~/Downloads/tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz
```
```powershell
New-Item -ItemType Directory -Force $HOME\pa | Out-Null; Set-Location $HOME\pa
& ([scriptblock]::Create((irm https://skipcheck.io/pa/setup.ps1))) --yes --bundle "$HOME\Downloads\tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz"
```

| Where TM1 comes from | Option |
|---|---|
| The IBM bundle file, `tm1server-<version>-linuxi38664h_bundle.tar.gz` (the **Linux** download even on Mac or Windows; look in `~/Downloads` first) | `--bundle FILE` |
| A folder holding several IBM downloads | `--ibm DIR` |
| A download link to the bundle, e.g. a pre-signed one, optionally with its SHA-256 | `--url "$URL" [--sha256 HASH]` |
| An installed `tm1_64` folder of a Linux TM1 server (it contains `bin64/tm1s.exe`) | `--tm1-home DIR` |
| A private Docker image with TM1 built in | `--image IMAGE` |
| TM1 v12's package, `tm1-v<version>-standalone-linux.tgz`: a TM1 v12 engine in place of the server | `--bundle FILE` |

- **Other options:** `--samples` (IBM's SData sample, on port 8011), `--files-dir DIR`,
  `--no-start`, `--timeout SECONDS`. `--help` lists them all.
- **Always pass `--yes`,** or it may ask questions at a terminal.
- **It sets up the folder it's run in:** `cd` there first. There's no `--dir`.
- **A `pa-environment.yaml` that's already there is used as it is,** and the options are
  said to be unused. To change what runs, edit the file and run `up`.
- **If the download returns HTML instead of a script** (some networks get a bot check from
  skipcheck.io), use the same script from GitHub:
  `https://raw.githubusercontent.com/ajmyers/skipcheck-tm1/master/docker/pa-setup/setup.sh`
  (or `setup.ps1`).
- **An older `./tm1` folder,** made by tm1-server's own setup script (retired), keeps working
  with plain `docker compose` there. Don't run pa-setup in it: start a new folder, and stop
  the old one first if it holds the same ports (ask before `docker compose down`).

Then go to [5. Check it worked](#5-check-it-worked). For anything more than one server,
follow the steps from 1.

## 1. Find out what the user wants and has

Ask only for what you can't find out yourself:

1. **OS.** Detect it (`uname -s`, or that you're on Windows).
2. **Which parts.** Any mix of: TM1 servers (how many, and which sample each starts with:
   `empty`, `sdata` or `24retail`), TM1 Web, PAW, Cognos security (test users and their
   groups; LDAP, and OIDC through Keycloak if they want it).
3. **The IBM downloads**, all in one folder (often `~/Downloads`). Look for them:
   ```sh
   ls -t ~/Downloads/tm1server-*-linuxi38664h_bundle.tar.gz ~/Downloads/tm1web-*_bundle.tar.gz \
         ~/Downloads/casrv-*-linuxi38664h.zip ~/Downloads/ipa_workspace_local_*.zip 2>/dev/null
   ```

   | Part | File it needs |
   |---|---|
   | TM1 servers | `tm1server-<version>-linuxi38664h_bundle.tar.gz`, the **Linux** bundle even on Mac or Windows |
   | TM1 Web | `tm1web-<version>-...-linuxi38664h_bundle.tar.gz` |
   | Cognos | `casrv-<version>-linuxi38664h.zip`, the repository zip (not the installer) |
   | PAW | `ipa_workspace_local_<release>.<build>.zip` |

   PAW and TM1 Web logons through Cognos also need IBM's `bi_interop.zip`; Cognos takes it
   from the TM1 bundle in the same folder. Instead of files, TM1, TM1 Web and Cognos can
   come from download links in `.env` (step 3). PAW always needs the folder.
4. **Optional:** the folder to set up in (suggest `~/pa`), and other ports.

If they don't have a download, explain they need their own licensed copy from IBM
(Passport Advantage or Fix Central); you can't get it for them.

## 2. Check Docker and memory

`docker info` must work. If Docker is missing, point them to
https://www.docker.com/products/docker-desktop/ (Mac/Windows) or
https://docs.docker.com/engine/install/ (Linux): installing it needs admin rights and a
license acceptance, so the user does it. On Apple Silicon, Docker Desktop's
*Use Rosetta for x86_64/amd64 emulation* setting must be on: the IBM parts are x86-64 only.

Memory Docker needs, roughly: 0.5 GB per TM1 server, 1 GB for TM1 Web, 8 GB for Cognos (and
0.7 GB for Keycloak), 15 GB for PAW, plus room to spare. Check it with
`docker info --format '{{.MemTotal}}'` (bytes). `up` warns when it's short; the fix is
Docker Desktop's Settings > Resources, which the user changes.

## 3. Write pa-environment.yaml

Make the folder and write the file there yourself, from the recipes below. (`init` writes a
commented starting file instead, and refuses if one exists.) Every section is optional, but
there must be TM1 servers or Cognos. `ibm` is the downloads folder: absolute, starting with
`~`, or relative to the file.

**One TM1 server:**
```yaml
version: 1
ibm: ~/Downloads
tm1:
  servers:
    tm1: { port: 8010 }
```

**Several servers, TM1 Web and PAW.** More than one server gets a shared Admin Server
(`adminPort`, default 5895), and TM1 Web and PAW list every server:
```yaml
version: 1
ibm: ~/Downloads
tm1:
  servers:
    planning: { port: 8010, seed: sdata }
    reporting: { port: 8011 }
tm1Web: { port: 9510 }
paw: { port: 8800, acceptLicense: true }   # only once the user accepts IBM's license
```

**Cognos security, LDAP and OIDC.** With `cognos`, every TM1 server runs in CAM security
against it. `users` are `user:password[:group,group]`; every group becomes a TM1 group.
`oidc` adds Keycloak and an `OIDC` namespace next to `LDAP`. `cam.namespace` (`ldap` or
`oidc`, which needs `oidc`) is the one TM1 uses, and `cam.admins` (default: the first user)
are the users who administer TM1:
```yaml
version: 1
ibm: ~/Downloads
tm1:
  servers:
    planning: { port: 8010, seed: sdata }
cognos:
  port: 9300
  users:
    - admin:adminpw:Admins
    - analyst:analystpw:Analysts,Readers
    - viewer:viewerpw:Readers
  oidc: { keycloakPort: 8180 }
  cam: { namespace: ldap, admins: [admin] }
```

Cognos on its own (no `tm1`) works too. Add `paw` to the Cognos recipe and PAW's browser
logon goes through Cognos (and Keycloak, in the OIDC namespace). `tm1Web` works with Cognos
too, but its logon through Cognos hasn't been tested.

All the settings:

| Setting | Default | |
|---|---|---|
| `version` | (required) | `1` |
| `name` | the folder's name | The compose project name |
| `ibm` | | The folder with the IBM downloads |
| `files` | `./pa-files` | Each TM1 server's `config`, `data` and `logs`, in a folder per server |
| `images` | the published images | `tm1Server`, `tm1Web`, `cognosServer`, `keycloak`: e.g. a private image with IBM software built in |
| `tm1.servers.<name>` | | `port` (8010, 8011, ...), `seed` (`empty`, `sdata`, `24retail`), `cfg` (any `tm1s.cfg` parameter, e.g. `{ MaximumCubeLoadThreads: 4 }`) |
| `tm1.adminServer`, `tm1.adminPort` | on with several servers; 5895 | A shared Admin Server |
| `tm1Web` | off | `true`, or `port` (9510) and `heap` (`1g`). Needs a TM1 server. |
| `paw` | off | `acceptLicense: true` (required), `port` (8800; HTTPS on port + 43), `zip` (default: the newest in `ibm`). Needs a TM1 server and `ibm`. |
| `cognos` | off | `port` (9300), `users`, `oidc` (`true` or `keycloakPort`, 8180), `cam`, `memory` (dispatcher heap in MB, 6144, at least 2048) |

**IBM's PAW license** is in the zip; the user reads it with
`unzip -p ~/Downloads/ipa_workspace_local_*.zip licenses/LA_en | less`.

**Download links** instead of files go in `.env` next to the file, which `up` creates with
commented lines: `TM1_BUNDLE_URL`, `TM1_WEB_BUNDLE_URL`, `COGNOS_ZIP_URL`, and optionally
`TM1_BUNDLE_SHA256`, `TM1_WEB_BUNDLE_SHA256`, `COGNOS_ZIP_SHA256`. Write them from a
variable the user sets; don't echo the URL.

## 4. Run pa-setup

**Mac / Linux**, in the folder:
```sh
curl -fsSL https://skipcheck.io/pa/setup.sh | sh -s -- generate   # checks the file, writes compose.yaml
curl -fsSL https://skipcheck.io/pa/setup.sh | sh -s -- up
```
Or save it once (`curl -fsSLo setup.sh https://skipcheck.io/pa/setup.sh`) and run
`sh setup.sh up`.

**Windows (PowerShell)**, in the folder:
```powershell
& ([scriptblock]::Create((irm https://skipcheck.io/pa/setup.ps1))) generate
& ([scriptblock]::Create((irm https://skipcheck.io/pa/setup.ps1))) up
```

| Command | What it does |
|---|---|
| `init` | Writes a starting `pa-environment.yaml` |
| `up` (default) | Writes `compose.yaml`, starts everything, waits until healthy, prints the URLs. Safe to rerun after changing the file. |
| `generate` | Only checks the file and writes `compose.yaml`: a quick way to validate it |
| `urls` | Prints every address and login |
| `status` | Lists the containers and their health |
| `logs [service...]` | The last 100 lines, e.g. `logs planning cognos` |
| `down` | Stops and removes the containers, PAW's too. Data is kept. |

`-f other.yaml` uses another file in the same folder.

- **`up` can take a long time:** about 15 s per TM1 server the first time, several minutes
  for Cognos (it installs, then starts), and a few more for PAW's first start, which loads
  IBM's images. It waits up to 30 minutes for the containers, then up to 10 for PAW. Give
  the command a long timeout, or run it in the background and check `status`.
- **If the download returns HTML instead of a script** (some networks get a bot check from
  skipcheck.io), use the same script from GitHub:
  `https://raw.githubusercontent.com/ajmyers/skipcheck-tm1/master/docker/pa-setup/setup.sh`
  (or `setup.ps1`).
- **Images:** pa-setup and the parts it runs use the `:edge` images (built from `master`)
  until the first release. `PA_IMAGE_TAG` picks another tag, and `PA_SETUP_IMAGE` another
  pa-setup image, e.g. a local build:
  `docker build -f docker/pa-setup/Dockerfile -t pa-setup:local docker` in a clone of the
  repository, then `PA_SETUP_IMAGE=pa-setup:local`.
- **Changing things:** edit `pa-environment.yaml` and run `up` again. Don't edit the
  generated `compose.yaml`; `up` rewrites it. It works with plain `docker compose` too.

## 5. Check it worked

`up` ends with every address and login. `urls` prints them again, and `status` shows each
container's health. Then try a logon:

- **TM1 without Cognos:** native TM1 users. `empty` seed: `admin`, no password; `sdata`:
  `admin` / `apple`.
  ```sh
  curl -u admin: 'http://localhost:8010/api/v1/Configuration/ProductVersion/$value'
  ```
- **TM1 with Cognos:** CAM logons only; native ones are refused. The header is
  `CAMNamespace` and base64 of `user:password:namespace`, with the namespace `LDAP` (or
  `OIDC` with `cam.namespace: oidc`):
  ```sh
  curl -H "Authorization: CAMNamespace $(printf analyst:analystpw:LDAP | base64)" http://localhost:8010/api/v1/ActiveUser
  ```
- **Admin Server** (several servers): `http://localhost:5895/api/v1/Servers`.
- **TM1 Web:** `http://localhost:9510/tm1web/`, logons as for TM1.
- **Cognos:** `http://localhost:9300/bi/`, the test users in namespace `LDAP` (and `OIDC`).
- **Keycloak:** `http://localhost:8180/`, console `admin` / `admin`.
- **PAW:** `http://localhost:8800/`, logons as for TM1; with Cognos, the browser logs on at
  Cognos and comes back.

On Windows use `curl.exe`, with a backtick before `$value` in double quotes.

For tools: host `localhost`, the server's port, SSL off. Each server's files are in
`pa-files/<server>/`. Tell the user the passwords of the test users are the ones in
`pa-environment.yaml`: throwaway, for tests.

## 6. Troubleshooting

Read pa-setup's message first: a problem with the file stops it with `pa-setup: <what to
fix>` before anything starts. Then `status`, and `logs <service>`. Services are named
`tm1-admin`, each TM1 server's name in lower case, `tm1-web`, `cognos` and `keycloak`;
PAW is IBM's own containers, outside the project (`docker logs pa-gateway`).

| Symptom | Fix |
|---|---|
| `pa-environment.yaml not found here` | Run the commands in the folder that holds it. |
| `unknown setting(s)`, `port ... used twice`, `needs ...` | Fix the file as the message says; `generate` checks it again. |
| `paw.acceptLicense` | The user hasn't accepted IBM's PAW license in the file. Ask them. |
| `paw: no ipa_workspace_local_*.zip` | The zip isn't in `ibm`, or set `paw.zip` to its name. |
| `no bundle found` / `tm1s.exe not found` in `logs <server>` | The TM1 bundle isn't in `ibm`, or isn't the Linux one, and there's no `TM1_BUNDLE_URL`. |
| `download failed` / `checksum mismatch` | The link in `.env` expired or is wrong; get a new one or fix the SHA-256. |
| Port in use | Something else (often a tm1-server setup, or another pa-setup) uses the port. Stop it (after asking), or change the port in the file. |
| `WARNING: Docker has ... GB of memory` | Raise Docker's memory (Settings > Resources); Cognos and PAW stall without it. |
| Cognos stays `starting` | It takes minutes, more on the first start; follow `logs cognos`. |
| TM1 refuses logons with Cognos | Use `CAMNamespace` with a test user, not `admin` with `-u`. |
| `PAW didn't answer within 600s` | `docker logs pa-gateway`; check memory. There's one PAW per Docker daemon: another PAW (from `docker/paw` or tests) must be stopped first. |
| `DPR-ERR-2107 The User Capabilities Cache cookie cannot be decoded` | An old `localhost` cookie from before Cognos was reinstalled. Clear `localhost`'s cookies, or use a private window. |
| Slow or crashing on Apple Silicon | Rosetta is off: Docker Desktop > Settings > General > *Use Rosetta for x86_64/amd64 emulation*. |
| New TM1 bundle isn't used | The servers share one install volume. After asking: `down`, `docker volume rm <name>_tm1-install`, `up`. |

**Starting over** (ask first; it deletes data): `down`, then remove the project's volumes
(`docker volume ls --filter name=<name>_`: the TM1 install, the Cognos install and content
store, TM1 Web's cache) and `pa-files`. `<name>` is the project name: `name` in the file, or
the folder's name in lower case.

Full reference: https://github.com/ajmyers/skipcheck-tm1/blob/master/docker/pa-setup/README.md
If it's a bug in pa-setup, suggest opening an issue: https://github.com/ajmyers/skipcheck-tm1/issues

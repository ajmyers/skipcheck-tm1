# tm1-server

A thin Docker runtime for **IBM Planning Analytics / TM1 Server** (Linux x86-64),
built for local development and integration tests of TM1 client libraries.

> **No IBM software is included in this repository or the image.** The image is
> Ubuntu, a few system libraries, and an entrypoint script. You bring your own
> licensed TM1 Server installation, and you are responsible for complying with
> your IBM license terms.

**New here? Follow [GETTING-STARTED.md](GETTING-STARTED.md)** for step-by-step
instructions on Mac, Windows and Linux, or let the setup script do them:
`curl -fsSL https://skipcheck.io/tm1/setup.sh | sh` (Mac/Linux, [`setup.sh`](setup.sh)) or
`irm https://skipcheck.io/tm1/setup.ps1 | iex` (Windows, [`setup.ps1`](setup.ps1)).
The interactive guide at <https://skipcheck.io/tm1/> shows only the steps for your
choices. This README is the reference.

**More than one TM1 server, or TM1 Web, PAW or Cognos?** Use [`pa-setup`](../pa-setup/README.md):
you describe the environment in one file, `pa-environment.yaml`, and one command starts it
and prints every address and login. It runs this image, so the settings below still apply.

The shortest path: put your IBM bundle (`tm1server-*-linuxi38664h_bundle.tar.gz`)
in [`bundles/`](bundles/README.md), run `docker compose up -d`, and TM1 is at
http://localhost:8010 (user `admin`, no password).

## What you mount

TM1 itself comes from one of [six sources](#getting-tm1-into-the-container); the
simplest is mounting an installed `tm1_64` folder. Everything else is optional:

| Container path            | What                                              | Notes |
|---------------------------|---------------------------------------------------|-------|
| `/opt/ibm/cognos/tm1_64`  | Your TM1 Server install (the folder with `bin64/`) | One of the TM1 sources. Can be read-only (`:ro`). |
| `/ibm`                    | A folder holding your IBM bundle                  | One of the TM1 sources. Installed on first start. |
| `/tm1/cache`              | Where bundle installs are kept                    | Mount a volume to reuse installs across containers |
| `/tm1/config`             | `tm1s.cfg` and `tm1s-log.properties`              | Defaults are created if missing. See [Configuration](#configuration). |
| `/tm1/data`               | The TM1 database (cubes, dimensions, processes, ...) | A new database is created if the folder is empty |
| `/tm1/logs`               | `tm1server.log`, `tm1s.log`, `tm1event.log`, process error logs | Set as `LoggingDirectory` |

**Anything you don't mount stays inside the container.** It survives
`docker stop` / `docker start`, but is deleted with the container: no leftover
Docker volumes. Mounting only the TM1 install gives a disposable TM1 server with
the default config and an empty database, which is what tests want:

```sh
docker run --rm -p 8010:8010 -v /path/to/tm1_64:/opt/ibm/cognos/tm1_64:ro ghcr.io/ajmyers/tm1-server
```

Mount a folder to keep it, or to bring your own `tm1s.cfg` or database.

```sh
docker run -d --name tm1 -p 8010:8010 \
  -v /path/to/tm1_64:/opt/ibm/cognos/tm1_64:ro \
  -v "$PWD/config:/tm1/config" \
  -v "$PWD/data:/tm1/data" \
  -v "$PWD/logs:/tm1/logs" \
  ghcr.io/ajmyers/tm1-server
```

A Docker volume works in place of any of those folders, e.g. `-v tm1-data:/tm1/data`.

## Image

Published to GitHub Container Registry by [`.github/workflows/docker.yml`](../../.github/workflows/docker.yml):

| Tag | Published when |
|-----|----------------|
| `ghcr.io/ajmyers/tm1-server:latest`, `:1.2.3`, `:1.2` | a release tag `v1.2.3` is pushed (same version as the libraries) |
| `ghcr.io/ajmyers/tm1-server:edge` | each commit to `master` |

Publishing is off until the repository variable `PUBLISH_DOCKER_TM1_SERVER` is set to
`true`. Until then, the image is built and smoke-tested but not pushed.

To use your own build in place of the published image, build it (below) and use
`tm1-server:local` wherever this README says `ghcr.io/ajmyers/tm1-server`. For
compose, set `TM1_SERVER_IMAGE=tm1-server:local` in `.env`.

To build it yourself, run `docker build --platform linux/amd64 -t tm1-server .` from
this folder. Always pass `--platform linux/amd64`: without it, Docker on Apple Silicon
labels the image arm64 even though its contents are amd64, and compose or
`docker run --platform linux/amd64` then can't find it. The image is
`linux/amd64` only, because TM1 Server only runs on x86-64.

## Compose

`compose.yaml` runs a small TM1 environment: an Admin Server and a TM1 server with
an empty database, plus IBM's SData sample if you ask for it. It works on its own:
copy it into any folder. It uses the published image; to use your own build, set
`TM1_SERVER_IMAGE=tm1-server:local` in `.env`.

```sh
docker compose up -d                      # Admin Server + empty database "tm1" -> http://localhost:8010
docker compose --profile samples up -d    # adds IBM's SData sample             -> http://localhost:8011
docker compose --profile web up -d        # adds TM1 Web (see ../tm1-web)       -> http://localhost:9510/tm1web/
docker compose ps                         # wait for (healthy)
docker compose logs -f tm1                # server message log
docker compose stop                       # clean shutdown (TM1 saves on SIGINT)
```

| Container | What | Address | Login |
|-----------|------|---------|-------|
| `tm1-admin` | Admin Server, shared by every TM1 server | http://localhost:5895/api/v1/Servers | – |
| `tm1` | TM1 server, empty database | http://localhost:8010/api/v1/ | `admin`, blank password |
| `tm1-sdata` | TM1 server, IBM's SData sample | http://localhost:8011/api/v1/ | `admin` / `apple` |
| `tm1-web` | [TM1 Web](../tm1-web/README.md), listing every server registered with `tm1-admin` (`web` profile; needs a `tm1web-*_bundle.tar.gz` in the bundles folder) | http://localhost:9510/tm1web/ | as for each server |

**TM1 source.** With no settings, compose installs TM1 from the bundle in
[`bundles/`](bundles/README.md) into the Docker volume `tm1-server_tm1-install` on
first start. The three containers share it, and only the first one installs. Set
these in a `.env` file to use another source (see [`.env.example`](.env.example)):

| Setting | Source |
|---------|--------|
| `TM1_BUNDLE_DIR` (and optionally `TM1_BUNDLE`) | A bundle in another folder |
| `TM1_BUNDLE_URL` (and optionally `TM1_BUNDLE_SHA256`) | A bundle downloaded from a URL |
| `TM1_HOME_DIR` | An installed `tm1_64` folder |
| `TM1_SERVER_IMAGE` | A private image with TM1 built in |

To fill the volume from a pipe instead: `... | docker compose run --rm -T --no-deps tm1 install -`.

This compose file is one fixed layout. For another (more servers, other ports, Cognos,
PAW), [`pa-setup`](../pa-setup/README.md) writes a compose project from `pa-environment.yaml`.

**Your files.** Each server's config, database and logs are in
`tm1-files/<server>/{config,data,logs}` (or set `TM1_FILES_DIR`), created on first
start. The `tm1` and sample servers' files can go elsewhere, each to another folder or a
**Docker volume**. Set these in `.env`:

| Server | Settings | The volume each can name |
|---|---|---|
| `tm1` | `TM1_CONFIG_DIR`, `TM1_DATA_DIR`, `TM1_LOGS_DIR` | `tm1-config`, `tm1-data`, `tm1-logs` |
| sample (`tm1-sdata`) | `TM1_SDATA_CONFIG_DIR`, `TM1_SDATA_DATA_DIR`, `TM1_SDATA_LOGS_DIR` | `tm1-sdata-config`, `tm1-sdata-data`, `tm1-sdata-logs` |

- **A folder** is a path that starts with `/`, `./` or `~` (or a drive letter on Windows).
  Anything else is read as a volume's name.
- **A volume** is the one in the table for that setting, and only that one: two settings
  naming the same volume would put two things in one place. Docker shows it with the
  project's name in front (`tm1_tm1-data` in a folder named `tm1`), so a renamed or copied
  folder starts on new, empty volumes.
- **Nothing is moved for you.** A server switched to a volume starts with an empty one: a
  new database and a default `tm1s.cfg`, with your files left in `tm1-files`. Copy them in
  first, with the server stopped:

  ```sh
  docker compose down
  docker compose run --rm --no-deps -v "$PWD/tm1-files/tm1/data:/from:ro" tm1 cp -a /from/. /tm1/data/
  ```

  (and the same for `config`, with `/tm1/config/`).
- **`docker compose down -v` removes these volumes,** and the config, database or logs in
  them. With folders it only removed the installed TM1.
- The Admin Server's and TM1 Web's logs stay under `TM1_FILES_DIR`.

A database in a volume is faster than in a folder on Mac and Windows, and isn't a folder
you can open. These settings need this `compose.yaml`: an older one ignores them. Like a real deployment, one Admin Server serves every TM1 server: the
servers register with `tm1-admin` through `TM1_ADMIN_HOST` (see [Roles](#roles)).

The REST API is plain HTTP by default (`UseSSL=F`), so there are no certificates to
deal with. To use SSL, set `TM1_USE_SSL=T` or `UseSSL=T` in your own `tm1s.cfg`. TM1
then serves HTTPS only, using IBM's self-signed certificate unless you configure
your own.

```sh
curl -u admin: 'http://localhost:8010/api/v1/Configuration/ProductVersion/$value'
```

## Getting TM1 into the container

The image contains no IBM software, so it needs your licensed TM1 Server for Linux
(`linuxi38664h`). It looks in this order and uses the first it finds:

| # | Source | How | Start time |
|---|--------|-----|------------|
| 1 | **An installed `tm1_64` folder** | `-v /path/to/tm1_64:/opt/ibm/cognos/tm1_64:ro` | ~3 s |
| 2 | **Your IBM bundle, mounted** | `-v /path/to/ibm-downloads:/ibm:ro` (optionally `-e TM1_BUNDLE=<file name>`) | ~15 s on first start, then ~5 s with a cache volume |
| 3 | **A download URL** | `-e TM1_BUNDLE_URL=https://...`, e.g. a pre-signed S3, R2 or GCS link | download + ~15 s, then ~5 s with a cache volume |
| 4 | **A bundle piped in from your machine** | `... \| docker run -i --rm -v tm1:/opt/ibm/cognos/tm1_64 tm1-server install -`, then mount the `tm1` volume | ~3 s once filled |
| 5 | **A private image with TM1 built in** | see [`examples/private-image`](examples/private-image/Dockerfile) | ~3 s, nothing to install |
| 6 | **A named Docker volume** | mount a volume at `/opt/ibm/cognos/tm1_64`: if it's empty and a bundle is available (2 or 3), TM1 is installed into it on first start | ~3 s once filled |

**How bundles are installed.** A bundle is IBM's
`tm1server-<ver>-linuxi38664h_bundle.tar.gz` (or the repository zip inside it).
Rather than run IBM's installer, the container follows the bundle's manifest and
unpacks the TM1 Server, Admin Server, Tools and Samples components. That's the
same result as the installer, and it also works where the installer can't run
(e.g. macOS).

Planning Analytics 2.1.2 and 2.1.7 come only as IBM's older full installer,
`tm1_linuxi38664h_<ver>_ml.tar.gz`, which has no manifest: its components are
`zipfiles/linuxi38664h/*-inst.tar.gz`, each rooted at the install folder. The
container unpacks them all but the language packs, in name order, and writes
`tm1server_version.txt` and `versions.txt` from the components' names. It's found
in the bundles folder like a bundle (a `tm1server-*` bundle is preferred when both
are there), and `TM1_BUNDLE`, `TM1_BUNDLE_URL` and `install` take it too.

**Caching.** Without a mounted install, a bundle is installed into
`/tm1/cache/<key>`, one folder per bundle. The key comes from the file's name,
size and date, or for URLs from `TM1_BUNDLE_SHA256` or the URL without its query
string. Mount a volume at `/tm1/cache` and every container after the first
reuses the install. Several containers can share one cache; installs are locked.
Remove the volume to clear it.

**Downloads (3)** are verified when `TM1_BUNDLE_SHA256` is set. Logs show URLs
without their query string, since pre-signed links carry credentials there. Any
`curl` URL works, including `file://`. There's no built-in `s3://`: use a
pre-signed link, or pipe the file in (4), so no cloud tools or keys go into the
container.

**Piping (4)** works with anything that can write a file to stdout, using your
machine's own credentials:

```sh
aws s3 cp s3://my-bucket/tm1server-2.1.19-...-linuxi38664h_bundle.tar.gz - \
  | docker run -i --rm -v tm1-2.1.19:/opt/ibm/cognos/tm1_64 ghcr.io/ajmyers/tm1-server install -
docker run -d -p 8010:8010 -v tm1-2.1.19:/opt/ibm/cognos/tm1_64:ro ghcr.io/ajmyers/tm1-server
```

Streaming straight into the install isn't possible: the bundle is a `.tar.gz` that
contains a `.zip`, and a zip can't be unpacked until it has been fully received.

**Private images (5)** are the fastest option for CI. Build on top of
`tm1-server` with your bundle, and push the result **only to a private registry**,
because it contains IBM software licensed to you:

```sh
docker build --platform linux/amd64 \
  --build-context ibm=/path/to/folder-with-just-the-bundle \
  -t registry.example.com/private/tm1-server:2.1.19 examples/private-image
```

The `install` command installs from any source into `/opt/ibm/cognos/tm1_64` (or
`--target DIR`) and exits: `install [URL | file | -]`. Run
`docker run --rm ghcr.io/ajmyers/tm1-server install --help` for details.

Starts Planning Analytics 2.1.2 (TM1 11.8.02400.7), 2.1.7 (11.8.02800.9), 2.1.16 (11.8.03200.3),
2.1.19 (11.8.03500.6) and 2.1.25 (11.8.04000.1). TM1 11.8.024 to 11.8.032 link `libjsig.so`, a
Java VM's signal-chaining library that IBM's own startup script takes from a `JAVA_HOME`. The
image carries OpenJDK's HotSpot copy of that one file, not a Java runtime, in
`/usr/local/lib/tm1-server/jvm` with its `copyright` (GPLv2 with the Classpath Exception) and
`VERSION`, registered with the loader's cache: an install's own `bin64/libjsig.so` comes first.
Only starting, stopping and the live tests were tried with it; a TurboIntegrator Java extension
running IBM's own JVM alongside it hasn't been.

## Configuration

TM1 is started with `-z /tm1/config`, so the `tm1s.cfg` in your config folder is
the file that actually runs. TM1 also writes changes back to it, for example
when you update settings through the REST API's `StaticConfiguration`.

**No `tm1s.cfg` provided:** the container writes a short default one
([`docker/tm1s.cfg.default`](docker/tm1s.cfg.default)) into `/tm1/config`, using
the environment variables below. You can then edit it, and your changes are kept.
A default `tm1s-log.properties` is created the same way.

**Your own `tm1s.cfg` (for example, copied from a server):** it's used as-is,
except for three groups of settings:

| Group | Settings | What happens |
|-------|----------|--------------|
| **Enforced**: the container depends on them | `DataBaseDirectory` = `/tm1/data`, `LoggingDirectory` = `/tm1/logs`, `RunningInBackground` = `T`, `HTTPPortNumber` = `8010`, `PortNumber` = `12345`, `ClientMessagePortNumber` = `12346`, `AdminHost` = `TM1_ADMIN_HOST` or `localhost`; `IPAddress`/`IPAddressV4`/`IPAddressV6` are commented out | Always set. Each change is logged with its old value. |
| **Warned**: likely to break in a container | `IntegratedSecurityMode` 4 or 5 without a `ServerCAMURI`, or with one that isn't reachable yet ([Cognos (CAM) security](#cognos-cam-security)), absolute `IndexStoreDirectory`/`ODBCLibraryPath`/`FileRetry.FileSpec` paths that don't exist in the container, `StartupChores` | Left as they are, with a warning at startup. |
| **Passed through** | Everything else: `ServerName`, `UseSSL`, tuning, logging, ... | Unchanged. |

The file is only rewritten when something actually changes, and CRLF line
endings are kept.

**Read-only config folder (`:ro`):** if the file needs changes (or the folder has
no `tm1s-log.properties`), TM1 runs from a corrected copy instead, with a warning.
Your files stay untouched; the catch is that settings changed through the REST
API's `StaticConfiguration` aren't saved.

### Ports

Ports inside the container are always the same, whatever your `tm1s.cfg` said:
**8010** (REST API), **12345** (TM1 API), **12346** (client messages), plus
**5895/5898/5495/5498** for the Admin Server. Choose the outside port when you map
them, e.g. `-p 9000:8010` serves the REST API on `localhost:9000`. Docker fixes
port mappings when a container is created, before the config is read, so the
inside ports can't follow the file. Testcontainers maps them to random free
ports, so parallel test runs never collide.

### Environment variables

| Variable | Default | |
|----------|---------|-|
| `TM1_BUNDLE_URL` | | URL of a bundle to download and install (source 3) |
| `TM1_BUNDLE` | newest in `/ibm` | Bundle file name in `/ibm`, or an absolute path (source 2) |
| `TM1_BUNDLE_SHA256` | | Expected SHA-256 of the bundle. Verified before installing. |
| `TM1_BUNDLE_DIR` | `/ibm` | Where to look for bundle files |
| `TM1_CACHE` | `/tm1/cache` | Where bundle installs are kept |
| `TM1_SERVER_NAME` | `tm1` | `ServerName` |
| `TM1_USE_SSL` | `F` | `UseSSL`. Plain HTTP by default; `T` switches TM1 to HTTPS only. |
| `TM1_CFG_<Param>` | | Any `tm1s.cfg` parameter, e.g. `TM1_CFG_MaximumCubeLoadThreads=4` (enforced settings still win) |
| `TM1_CAM_URI` | | Cognos (CAM) security against this Cognos, e.g. `http://cognos:9300` ([below](#cognos-cam-security)) |
| `TM1_SECURITY_MODE` | `5` | `IntegratedSecurityMode` with `TM1_CAM_URI`: `5` (Cognos users, TM1 and Cognos groups) or `4` |
| `TM1_CAM_ADMINS` | | Cognos users to make TM1 administrators, e.g. `admin` |
| `TM1_CAM_GROUPS` | | Cognos groups to add to TM1, e.g. `Analysts Readers` |
| `TM1_CAM_NAMESPACE` | `LDAP` | The Cognos namespace ID, for building CAMIDs from short names |
| `TM1_CAM_NAMESPACE_TYPE` | `ldap` | `ldap` or `oidc`: how short names become CAMIDs (`oidc`: `CAMID("OIDC:u:admin")`, `CAMID("OIDC:g:analysts")`) |
| `TM1_NATIVE_ADMIN_PASSWORD` | | Password of TM1's native `Admin`, if your database has one (used to set up CAM admins) |
| `TM1_SEED` | `empty` | `empty`, `sdata` or `24retail`. Objects to copy into an **empty** `/tm1/data`, taken from the samples in your install. |
| `TM1_ADMIN_HOST` | | Host of a separate Admin Server (e.g. a container running `admin`). Sets `AdminHost` and turns the embedded Admin Server off. |
| `TM1_ADMIN_SERVER` | `T`, or `F` when `TM1_ADMIN_HOST` is set | Run an embedded Admin Server in this container |

`TM1_SERVER_NAME` and `TM1_USE_SSL` defaults only apply when the container creates
`tm1s.cfg`. For an existing file, they change it only if you set them.

### Cognos (CAM) security

TM1 can take its users from IBM Cognos Analytics (`IntegratedSecurityMode=5`).
For tests, run [`cognos-server`](../cognos-server/README.md) with a few test users,
and point TM1 at it on the same Docker network:

```sh
docker network create pa
docker run -d --name cognos --hostname cognos --network pa -p 9300:9300 --stop-timeout 180 \
  -e COGNOS_USERS="admin:adminpw:Admins analyst:analystpw:Analysts,Readers viewer:viewerpw:Readers" \
  -v cognos-install:/opt/ibm/cognos/analytics ghcr.io/ajmyers/cognos-server
docker run -d --name tm1 --network pa -p 8010:8010 \
  -v /path/to/tm1_64:/opt/ibm/cognos/tm1_64:ro \
  -e TM1_CAM_URI=http://cognos:9300 \
  -e TM1_CAM_ADMINS=admin -e "TM1_CAM_GROUPS=Admins Analysts Readers" \
  ghcr.io/ajmyers/tm1-server
```

Or run both with [`examples/cam-security/compose.yaml`](examples/cam-security/compose.yaml),
or with [`pa-setup`](../pa-setup/README.md): a `cognos` section in `pa-environment.yaml`
sets all of this up for every TM1 server, with Keycloak for OIDC if you ask for it.

- **`TM1_CAM_URI`** sets `IntegratedSecurityMode=5`, `ServerCAMURI`
  (`…/p2pd/servlet/dispatch`), `ClientCAMURI` (`…/bi/v1/disp`) and `CAMUseSSL`.
  TM1 can start before Cognos is ready; CAM logons work once it is.
- **Logging on:** `Authorization: CAMNamespace <base64 of user:password:namespace>`,
  e.g. `analyst:analystpw:LDAP`. Native TM1 logons are refused. TM1 knows users by
  their CAMID, e.g. `CAMID("LDAP:u:uid=analyst,ou=people")`.
- **Administrators (`TM1_CAM_ADMINS`).** In CAM security, nobody can administer
  TM1 until a Cognos user is in TM1's `ADMIN` group, and only a native admin can
  put one there. So when the list changes, TM1 first starts briefly in native
  security on a private port, the users are added to `ADMIN`, and TM1 saves and
  stops before the real start. That takes a few seconds, once.
- **Groups (`TM1_CAM_GROUPS`).** A Cognos group becomes a TM1 group named by its
  CAMID, e.g. `CAMID("LDAP:g:cn=analysts,ou=groups")`. At each logon, TM1 puts users
  into the groups Cognos says they belong to, so give each group the security it
  needs (cube, dimension, element, ...) to test different profiles. An
  administrator can add more groups the same way later.
- Short names assume `cognos-server`'s LDAP layout. For its OIDC namespace, set
  `TM1_CAM_NAMESPACE=OIDC` and `TM1_CAM_NAMESPACE_TYPE=oidc`
  ([`examples/cam-security-oidc`](examples/cam-security-oidc/compose.yaml)). For any
  other Cognos namespace, give full `CAMID("...")` values.
- **OIDC logons:** with an OIDC namespace, users log on with `CAMNamespace` (TM1 passes
  the password to Cognos, which checks it with the provider). An app holding a user's ID
  token logs on to Cognos with it and sends TM1 `Authorization: CAMPassport <passport>`.
  See [OIDC](../cognos-server/README.md#oidc) in `cognos-server`.

## Roles

One image runs either piece of a TM1 deployment, chosen by the command:

| Command | Runs | Healthy when |
|---------|------|--------------|
| `start` (default) | One TM1 server. Also an embedded Admin Server, unless `TM1_ADMIN_HOST` points at a separate one. | `GET /api/v1/$metadata` on 8010 returns 200 (no login needed) |
| `admin` | Only the Admin Server, on ports 5895/5898 (REST) and 5495/5498 (legacy clients) | `GET /api/v1/Servers` on 5895 returns 200 |
| `install [SOURCE]` | Installs TM1 from a bundle and exits ([above](#getting-tm1-into-the-container)) | – |

Anything else is run as-is (e.g. `docker run --rm -it ... tm1-server bash`).

**One container, quick start:** `start` with nothing else runs a TM1 server and
its own Admin Server together.

**Several TM1 servers:** run one container with `admin`, and give each TM1
server `TM1_ADMIN_HOST=<that container's name>`, all on the same Docker network.
This is what `compose.yaml` does, and [`pa-setup`](../pa-setup/README.md) with more than one server. TM1 servers register under their in-network
names (e.g. `http://tm1:8010`), so the addresses the Admin Server lists work
from other containers on the network (TM1 Web, PAW), not from your machine.
From your machine, use each server's published port.

## Platforms

TM1 Server is Linux x86-64 software, so the image is `linux/amd64` only. Setup for
each platform is in [GETTING-STARTED.md](GETTING-STARTED.md#step-1-install-docker).

| Platform | How TM1 runs |
|----------|--------------|
| Linux, x86-64 | Natively |
| Windows, Intel/AMD | Natively, in Docker Desktop's WSL2 Linux VM |
| Mac, Apple Silicon | Rosetta emulation, which is fast (ready in a few seconds). Needs `softwareupdate --install-rosetta` and Docker Desktop's **Use Rosetta for x86_64/amd64 emulation** setting. |
| Mac, Intel | Natively, in Docker Desktop's Linux VM |
| Windows or Linux on ARM | QEMU emulation: works, but noticeably slower |

Tested on Apple Silicon. The image's files keep LF line endings on Windows
(`.gitattributes`), so it also builds there.

## Notes and limitations

- Only TM1 Server and Admin Server are included. Security mode is 1 (native TM1
  users) unless you use [Cognos (CAM) security](#cognos-cam-security).
- `tm1s.exe` and `tm1admsrv.exe` both daemonize themselves, so the entrypoint
  tracks the daemons' PIDs. On `docker stop`, it sends TM1 SIGINT, which makes it
  save data and shut down cleanly.
- With a separate Admin Server, start it first (compose does, via `depends_on`).
  Whether TM1 servers re-register after the Admin Server restarts, or when it
  starts after them, hasn't been tested yet.
- The Admin Server writes `tm1admsrv.log` into `bin64/` of the install. If the
  install is mounted read-only, that log isn't written.

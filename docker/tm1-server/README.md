# tm1-server

A thin Docker runtime for **IBM Planning Analytics / TM1 Server** (Linux x86-64),
built for local development and integration tests of TM1 client libraries.

> **No IBM software is included in this repository or the image.** The image is
> Ubuntu, a few system libraries, and an entrypoint script. You bring your own
> licensed TM1 Server installation, and you are responsible for complying with
> your IBM license terms.

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

## Image

Published to GitHub Container Registry by [`.github/workflows/docker.yml`](../.github/workflows/docker.yml):

| Tag | Published when |
|-----|----------------|
| `ghcr.io/ajmyers/tm1-server:latest`, `:1.2.3`, `:1.2` | a release tag `v1.2.3` is pushed (same version as the libraries) |
| `ghcr.io/ajmyers/tm1-server:edge` | each commit to `master` |

Publishing is off until the repository variable `PUBLISH_DOCKER_TM1_SERVER` is set to
`true`. Until then, the image is built and smoke-tested but not pushed.

To build it yourself, run `docker build --platform linux/amd64 -t tm1-server .` from
this folder. Always pass `--platform linux/amd64`: without it, Docker on Apple Silicon
labels the image arm64 even though its contents are amd64, and compose or
`docker run --platform linux/amd64` then can't find it. The image is
`linux/amd64` only, because TM1 Server only runs on x86-64.

## Compose

`compose.yaml` builds the image from this folder by default. To use the published
image, set `TM1_SERVER_IMAGE=ghcr.io/ajmyers/tm1-server:latest` in `.env` and run
`docker compose pull`. Then `cp .env.example .env` and set `TM1_HOME_DIR`:

```sh
docker compose up -d                      # Admin Server + empty database "tm1" -> http://localhost:8010
docker compose --profile samples up -d    # adds IBM's SData sample             -> http://localhost:8011
docker compose logs -f tm1                # server message log
docker compose stop                       # clean shutdown (TM1 saves on SIGINT)
```

| Container | What | Address | Login |
|-----------|------|---------|-------|
| `tm1-admin` | Admin Server, shared by every TM1 server | http://localhost:5895/api/v1/Servers | – |
| `tm1` | TM1 server, empty database | http://localhost:8010/api/v1/ | `admin`, blank password |
| `tm1-sdata` | TM1 server, IBM's SData sample | http://localhost:8011/api/v1/ | `admin` / `apple` |

Like a real deployment, one Admin Server serves every TM1 server: the servers
register with `tm1-admin` through `TM1_ADMIN_HOST` (see [Roles](#roles)).

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

Tested with TM1 Server 2.1.19 (11.8.03500.6).

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
| **Warned**: likely to break in a container | `IntegratedSecurityMode` 4 or 5 (Cognos/CAM security, not provided), `ServerCAMURI`/`ClientCAMURI`, absolute `IndexStoreDirectory`/`ODBCLibraryPath`/`FileRetry.FileSpec` paths that don't exist in the container, `StartupChores` | Left as they are, with a warning at startup. |
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
| `TM1_SEED` | `empty` | `empty`, `sdata` or `24retail`. Objects to copy into an **empty** `/tm1/data`, taken from the samples in your install. |
| `TM1_ADMIN_HOST` | | Host of a separate Admin Server (e.g. a container running `admin`). Sets `AdminHost` and turns the embedded Admin Server off. |
| `TM1_ADMIN_SERVER` | `T`, or `F` when `TM1_ADMIN_HOST` is set | Run an embedded Admin Server in this container |

`TM1_SERVER_NAME` and `TM1_USE_SSL` defaults only apply when the container creates
`tm1s.cfg`. For an existing file, they change it only if you set them.

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
This is what `compose.yaml` does. TM1 servers register under their in-network
names (e.g. `http://tm1:8010`), so the addresses the Admin Server lists work
from other containers on the network (TM1 Web, PAW), not from your machine.
From your machine, use each server's published port.

## Apple Silicon

TM1 is x86-64 only. Docker Desktop runs it under Rosetta, which is fast. The
server is ready in a few seconds. You need to:

1. Run `softwareupdate --install-rosetta --agree-to-license`.
2. In Docker Desktop, enable **Settings → General → Use Rosetta for x86_64/amd64
   emulation on Apple Silicon**.

## Notes and limitations

- Only TM1 Server and Admin Server are included. TM1 Web, PAW and Cognos (CAM)
  security are not. Security mode is 1 (native TM1 users).
- `tm1s.exe` and `tm1admsrv.exe` both daemonize themselves, so the entrypoint
  tracks the daemons' PIDs. On `docker stop`, it sends TM1 SIGINT, which makes it
  save data and shut down cleanly.
- With a separate Admin Server, start it first (compose does, via `depends_on`).
  Whether TM1 servers re-register after the Admin Server restarts, or when it
  starts after them, hasn't been tested yet.
- The Admin Server writes `tm1admsrv.log` into `bin64/` of the install. If the
  install is mounted read-only, that log isn't written.

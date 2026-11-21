# cognos-server

A thin Docker runtime for **IBM Cognos Analytics 12** (Linux x86-64), built for
local development and integration tests. Its first job is to give TM1 servers
Cognos (CAM) security to test against.

> **No IBM software is included in this repository or the image.** The image is
> Ubuntu, PostgreSQL, a few system libraries, and an entrypoint script. You bring
> your own licensed Cognos Analytics, and you are responsible for complying with
> your IBM license terms.

The shortest path: put your IBM repository zip (`casrv-*-linuxi38664h.zip`) in
[`bundles/`](bundles/README.md), run `docker compose up -d`, wait a few minutes
for `(healthy)`, and Cognos is at http://localhost:9300/bi/.

Without compose:

```sh
docker run -d --name cognos -p 9300:9300 --stop-timeout 180 \
  -v /path/to/folder-with-the-zip:/ibm:ro \
  -v cognos-install:/opt/ibm/cognos/analytics \
  ghcr.io/ajmyers/cognos-server
```

**Docker needs at least 12 GB of memory** (Docker Desktop: Settings → Resources).
Cognos uses about 8 GB once it's running.

## What it needs

| Container path | What | Notes |
|----------------|------|-------|
| `/ibm` | A folder holding your `casrv-<version>-linuxi38664h.zip` | Installed on first start. Can be read-only (`:ro`). |
| `/opt/ibm/cognos/analytics` | The Cognos install (about 10 GB) | Mount a volume to keep it. Without one, every new container installs again. |
| `/cognos/postgres` | The embedded content store | Mount a volume to keep it. Without one, the content store goes away with the container. |

**The install belongs to one container.** Cognos writes into its install while it
runs: its configuration, data, temp files, logs, and about 2 GB of application
server work files. So unlike `tm1-server`, an install can't be mounted read-only
and shared. For several containers at once (parallel tests, CI), build a
[private image](#getting-cognos-into-the-container). Docker gives every container
its own copy-on-write copy of it.

## Getting Cognos into the container

The image contains no IBM software, so it needs your licensed Cognos Analytics
repository zip for Linux. That's the `casrv-<version>-linuxi38664h.zip` that IBM's
installer, `ca_instl`, reads. You don't need the installer itself.

| Source | How | Start time |
|--------|-----|------------|
| **A private image with Cognos built in** | see [`examples/private-image`](examples/private-image/Dockerfile) | ~2 min, nothing to install |
| **A volume already holding an install** | `-v cognos-install:/opt/ibm/cognos/analytics`, filled by any of the sources below | ~2 min |
| **The zip, mounted** | `-v /path/to/folder:/ibm:ro` (optionally `-e COGNOS_ZIP=<file name>`) | ~1 min to install, then ~2 min |
| **A download URL** | `-e COGNOS_ZIP_URL=https://...`, e.g. a pre-signed S3, R2 or GCS link | download + ~1 min, then ~2 min |
| **The zip piped in** | `... \| docker run -i --rm -v cognos-install:/opt/ibm/cognos/analytics cognos-server install -` | once filled, ~2 min |

Start times are from Apple Silicon under Rosetta, to `(healthy)`.

**How the zip is installed.** Rather than run IBM's installer, the container follows
the manifest in the zip and unpacks the `CASRVR_DataTier` and `CASRVR_AppTier`
components, reading each part straight out of the zip. Then it runs IBM's own
post-install steps. That's the same result as the installer, without the
installer's separate Java runtime. The gateway (`CASRVR_Gate`) isn't installed:
clients use the dispatcher on port 9300 directly. Set `COGNOS_INSTALLABLES` to
choose other components.

**Private images** are the fastest option for tests and CI. Build on top of
`cognos-server` with your zip, and push the result **only to a private registry**,
because it contains IBM software licensed to you:

```sh
docker build --platform linux/amd64 \
  --build-context ibm=/path/to/folder-with-just-the-zip \
  -t registry.example.com/private/cognos-server:12.1.1 examples/private-image
```

The `install` command installs from any source into `/opt/ibm/cognos/analytics`
(or `--target DIR`) and exits: `install [URL | file | -]`. Run
`docker run --rm ghcr.io/ajmyers/cognos-server install --help` for details.
Downloads are verified when `COGNOS_ZIP_SHA256` is set. Logs show URLs without
their query string, since pre-signed links carry credentials there.

Tested with Cognos Analytics 12.1.1.

## Content store

Cognos keeps its own data (users' content, settings, schedules) in a database,
the *content store*. This image uses **PostgreSQL**, which Cognos 12 supports.

- **Embedded (default):** with `COGNOS_CS_HOST` unset, the container runs its own
  PostgreSQL on `127.0.0.1`, with its data in `/cognos/postgres`. Nothing else to
  set up. Cognos creates its tables on first start.
- **External:** set `COGNOS_CS_HOST` (and the other `COGNOS_CS_*` settings below)
  to use a PostgreSQL elsewhere, e.g. the official `postgres` image on the same
  Docker network. The database must exist; Cognos creates the tables.

```sh
docker network create cognos
docker run -d --name cognos-db --network cognos \
  -e POSTGRES_USER=cognos -e POSTGRES_PASSWORD=cognos -e POSTGRES_DB=cm postgres:17
docker run -d --name cognos --network cognos -p 9300:9300 --stop-timeout 180 \
  -e COGNOS_CS_HOST=cognos-db \
  -v cognos-install:/opt/ibm/cognos/analytics ghcr.io/ajmyers/cognos-server
```

## Environment variables

| Variable | Default | |
|----------|---------|-|
| `COGNOS_ZIP_URL` | | URL of a repository zip to download and install |
| `COGNOS_ZIP` | newest in `/ibm` | Zip file name in `/ibm`, or an absolute path |
| `COGNOS_ZIP_SHA256` | | Expected SHA-256 of the zip. Verified before installing. |
| `COGNOS_ZIP_DIR` | `/ibm` | Where to look for the zip |
| `COGNOS_INSTALLABLES` | `CASRVR_DataTier CASRVR_AppTier` | Components to install from the manifest |
| `COGNOS_HOST` | the container's hostname | Host name in Cognos's dispatcher URIs. Other containers on the network reach Cognos by it, so keep it a name they can resolve. |
| `COGNOS_MAX_MEMORY` | `6144` | Dispatcher JVM heap, in MB. 2048 is too small: Cognos's apps fail to start. |
| `COGNOS_CS_HOST` | | External PostgreSQL host. Unset: the embedded one. |
| `COGNOS_CS_PORT` | `5432` | |
| `COGNOS_CS_NAME` | `cm` | Database |
| `COGNOS_CS_SCHEMA` | `public` | Schema |
| `COGNOS_CS_USER` / `COGNOS_CS_PASSWORD` | `cognos` / `cognos` | Database login. The embedded PostgreSQL creates this user. |
| `COGNOS_CS_WAIT` | `60` | Seconds to wait for an external PostgreSQL to accept connections |
| `COGNOS_PG_DATA` | `/cognos/postgres` | Where the embedded PostgreSQL keeps its data |

Each start writes Cognos's `configuration/cogstartup.xml` from a template made at
install time, with these settings. To change other Cognos settings for now, edit
`.cognos-server/cogstartup.xml` in the install (the template). It's a copy of the
file IBM's installer produced, with the install's host name replaced by
`cognos-install-host`.

## Commands

| Command | Runs | Healthy when |
|---------|------|--------------|
| `start` (default) | Cognos, and the embedded PostgreSQL unless `COGNOS_CS_HOST` is set | `GET /bi/v1/login` on 9300 returns 200 (no login needed) |
| `install [SOURCE]` | Installs Cognos from a zip and exits ([above](#getting-cognos-into-the-container)) | – |

Anything else is run as-is (e.g. `docker run --rm -it ... cognos-server bash`).

**Stopping.** `docker stop` asks Cognos to shut down and waits for it, which takes a
minute or two. Docker's default is only 10 seconds before it kills the container,
so give it longer: `docker stop --time 180`, `--stop-timeout 180` on `docker run`,
or `stop_grace_period` in compose. A killed Cognos leaves a configuration lock
behind; the next start removes it.

## Security

There's no namespace (login) configured yet, so Cognos allows **anonymous access**:
anyone who can reach port 9300 has full access. Keep it on your machine or a
private network. Namespaces (LDAP first, then OIDC) are coming, for testing TM1's
CAM security; see [#44](https://github.com/ajmyers/tm1-pa-developer-tools/issues/44).

## Image

Published to GitHub Container Registry by [`.github/workflows/docker.yml`](../../.github/workflows/docker.yml),
with the same tags as `tm1-server` (`:latest` and versions on release, `:edge` from
`master`), once the repository variable `PUBLISH_DOCKER_COGNOS_SERVER` is `true`.
Until then it's built and smoke-tested but not pushed.

To build it yourself, run `docker build --platform linux/amd64 -t cognos-server .`
from this folder. The image is `linux/amd64` only, because Cognos Analytics for
Linux is x86-64 software. On Apple Silicon it runs under Rosetta (Docker Desktop's
**Use Rosetta for x86_64/amd64 emulation** setting).

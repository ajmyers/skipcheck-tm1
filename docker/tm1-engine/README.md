# tm1-engine

A thin Docker runtime for **IBM TM1 v12**, the TM1 engine of Planning Analytics 3.x
("TM1 v12 Standalone", Linux x86-64), built for local development and integration tests.
For TM1 11 (Planning Analytics 2.x), use [`tm1-server`](../tm1-server/README.md).

> **No IBM software is included in this repository or the image**, and no MongoDB. The
> image is Ubuntu, a few system libraries, and an entrypoint. You bring your own licensed
> TM1 v12, and you are responsible for complying with your IBM license terms.

The shortest path: put IBM's `tm1-v<version>-standalone-linux.tgz` in
[`bundles/`](bundles/README.md), run `docker compose up -d`, and TM1 is at
http://localhost:4444, with a database named `tm1`:

```sh
docker compose exec tm1-engine tm1-engine credentials     # every address and credential
cookie=$(docker compose exec tm1-engine tm1-engine session)   # log in as admin
curl -b "$cookie" "http://localhost:4444/tm1/api/v1/Databases('tm1')/Cubes?\$select=Name"
```

## Keeping your data

Everything TM1 stores is in two places, and you can put each in **a folder on your
computer or a Docker volume**:

| What | In the container | With `compose.yaml` |
|---|---|---|
| TM1's configuration, databases (the cube files, `databases/<id>/*.cub`) and logs | `/tm1/deployment` | `./tm1-engine-files/deployment` |
| The root and application credentials | `/tm1/credentials` | `./tm1-engine-files/credentials` |
| MongoDB's data: TM1's list of instances, databases and applications, and each database's changes since its last save | `/data/db` in the MongoDB container | `./tm1-engine-files/datastore` |

So with compose, the one folder `tm1-engine-files` (or `TM1_ENGINE_FILES_DIR`) is all of it:
keep, copy or delete it as a whole. To put any of the three elsewhere, set
`TM1_ENGINE_DEPLOYMENT_DIR`, `TM1_ENGINE_CREDENTIALS_DIR` or `TM1_DATASTORE_DIR` in `.env` to
another folder, or to the Docker volume `compose.yaml` declares for it
(`tm1-engine-deployment`, `tm1-engine-credentials`, `tm1-engine-datastore`).

With plain `docker run`, mount a folder or a volume at those paths:

```sh
docker run -d -p 4444:4444 -v /path/to/ibm-downloads:/ibm:ro -v tm1-engine-cache:/tm1/cache \
  -v "$PWD/tm1-engine-files/deployment:/tm1/deployment" \
  -v "$PWD/tm1-engine-files/credentials:/tm1/credentials" \
  ghcr.io/ajmyers/tm1-engine
```

Here TM1 runs its own MongoDB ([below](#the-datastore-mongodb)), whose data is inside the
deployment (`third_party/datastore`), so that one mount holds everything.

**The deployment and the MongoDB data belong together.** A cube's values are in its `.cub`
file once saved; until then a change is in MongoDB (the database's transaction log), and
MongoDB is also where TM1 keeps the list of its databases. Keep or remove both. Anything
you don't mount lives in the container and goes with it, which is what tests want.

## How TM1 v12 differs from TM1 11

| | TM1 11 (`tm1-server`) | TM1 v12 (`tm1-engine`) |
|---|---|---|
| One container is | one TM1 server | one **engine**, holding **instances**, each holding **databases** |
| REST API | `:8010/api/v1/` | `:4444/<instance>/api/v1/Databases('<database>')/` |
| Creating a database | a new container | a request to the management API (`:4444/manage/v1/`), in seconds |
| Logging in | a user and password, or Cognos (CAM) | an **application's** client ID and secret, plus a user name ("service-to-service"); or OAuth 2.0; or pass-through to another service. No passwords of its own |
| Users and groups | per server | per database |
| Settings | `tm1s.cfg` | `config.internal.json` |
| Needs | nothing else | a MongoDB (its "datastore") |

An application belongs to an instance: its credentials log any user in to every database
of that instance, and give one session for all of them. A user has to exist in a database
to do anything there.

## What you mount

| Container path | What | Notes |
|---|---|---|
| `/ibm` | A folder holding IBM's `tm1-v*-standalone-linux.tgz` | Installed on first start. The same folder can hold your TM1 11, TM1 Web and Cognos downloads: each image picks its own. |
| `/tm1/cache` | Where package installs are kept | Mount a volume to reuse installs across containers |
| `/opt/ibm/tm1-engine` | A TM1 install made by this image | Instead of `/ibm`: see [Getting TM1 into the container](#getting-tm1-into-the-container) |
| `/tm1/deployment` | `config.internal.json`, the databases, and logs (`logs/*.jsonl`) | A new deployment is created if the folder is empty |
| `/tm1/credentials` | `root.json` and each application's `<instance>/<application>.json` | Mount it with `/tm1/deployment`: TM1 shows an application's secret only once |

Each can be a folder on your computer or a Docker volume ([Keeping your data](#keeping-your-data)).
**Anything you don't mount stays inside the container.** It survives `docker stop` /
`docker start`, and is deleted with the container: a disposable TM1, which is what tests
want.

## The datastore (MongoDB)

TM1 v12 keeps its metadata (instances, databases, applications) in MongoDB.

- **`TM1_DATASTORE_URL`** points it at one of yours, usually a `mongo:8.0` container beside
  it, as [`compose.yaml`](compose.yaml) does:
  `mongodb://root:password@mongo:27017`. The URL takes `authSource`, `authMechanism`,
  `replicaSet` and `tlsCAFile`, as IBM's installer does.
- **Without it,** TM1 runs its own: on first start the container downloads the MongoDB
  version IBM's installer would (8.0.32 for TM1 12.6.7) from mongodb.org into the install,
  checks it against the SHA-256 published beside it (with a warning if that can't be
  fetched), and holds its cache to `TM1_DATASTORE_CACHE_GB` (default 0.5; TM1 would start
  it with 16 GB). That needs internet on first start, and a writable install
  (the cache, or a volume).

  ```sh
  docker run -d -p 4444:4444 -v /path/to/ibm-downloads:/ibm:ro -v tm1-engine-cache:/tm1/cache ghcr.io/ajmyers/tm1-engine
  ```

TM1 creates a control database and one database per instance in MongoDB. Tested with a
MongoDB administrator (`root`), on MongoDB 8.0.

Keep the password in the URL to letters and digits: whether TM1 decodes `%40` and the like
hasn't been checked. A deployment that has used a MongoDB of yours keeps using it:
removing `TM1_DATASTORE_URL` later doesn't move it to a MongoDB of TM1's own.

The MongoDB and `/tm1/deployment` belong together ([Keeping your data](#keeping-your-data)).
With the deployment kept and the MongoDB data gone, TM1 starts empty and the container sets
it up again, leaving the old databases' files unused in `deployment/databases`. The other
way round, a new deployment on a MongoDB that others have used makes a new control
database there (`tm1-control-<id>`) and leaves the old ones: harmless, but they add up in
a MongoDB shared by engines that come and go. If TM1 can't reach or log in to MongoDB, the container stops and
shows what TM1 logged.

## What it creates

On every start the container makes sure these exist, and creates only what's missing:

| Variable | Default | |
|---|---|---|
| `TM1_DATABASES` | `tm1` | `[instance/]database,...`: databases, in instance `tm1` unless named. Empty for none. Names need at least 3 characters, and `Sales` and `sales` are two databases. |
| `TM1_APPLICATIONS` | `tm1` in each instance | `[instance/]application,...`: applications, whose credentials are saved in `/tm1/credentials` |
| `TM1_USERS` | | `user[:group[:group]],...`: users for every database, in exactly those groups (created if missing). `admin` always stays in `ADMIN`. |

A new database has one user, `Admin`, in the `ADMIN` group. If an application exists
but its secret isn't in `/tm1/credentials` (the folder was lost), the application is
replaced, since TM1 can't show the secret again.

The container is healthy once all of that is done and every database is ready: about
30 s, or 40 s when it installs first.

## Commands

Run with `docker exec <container> tm1-engine ...`:

| Command | |
|---|---|
| `credentials` | Every address and credential. `--json` for a program (it includes the secrets). |
| `session [instance] [user]` | Logs in (service-to-service) and prints the session cookie, `TM1SessionId=...`, for `curl -b`. Default: `tm1`, `admin`. |
| `database create [instance/]name` | Creates a database (about 10 s), with the users of `TM1_USERS`, and prints its REST path |
| `database drop [instance/]name` | Deletes a database |

And as the container's command: `install [URL | file | -]` installs TM1 into
`/opt/ibm/tm1-engine` (or `--target DIR`) and exits.

## Getting TM1 into the container

The image needs IBM's `tm1-v<version>-standalone-linux.tgz`. It looks in this order:

| # | Source | How |
|---|---|---|
| 1 | **An install made by this image** | a volume or folder at `/opt/ibm/tm1-engine`, or a private image built on this one |
| 2 | **A download URL** | `-e TM1_ENGINE_BUNDLE_URL=https://...`, e.g. a pre-signed S3, R2 or GCS link; `TM1_ENGINE_BUNDLE_SHA256` verifies it |
| 3 | **IBM's package, mounted** | `-v /path/to/ibm-downloads:/ibm:ro` (optionally `-e TM1_ENGINE_BUNDLE=<file name>`) |
| 4 | **A package piped in** | `... \| docker run -i --rm -v tm1-engine:/opt/ibm/tm1-engine ghcr.io/ajmyers/tm1-engine install -`, then mount the volume |

With 2 and 3, TM1 is installed into `/tm1/cache/<key>` (one folder per package), or into
an empty volume mounted at `/opt/ibm/tm1-engine`. Several containers can share one
cache; installs are locked, and one that was cut short is done again.

**IBM's installer isn't run.** It asks questions, registers a systemd service and
downloads MongoDB. The package's installer is a self-extracting archive, so the container
checks its checksum and unpacks it: `bin/`, and the configuration a new deployment starts
from. An install made by IBM's installer on another machine isn't supported as a source.

## Stopping

On `docker stop`, the container stops each database through TM1 first, which commits and
shuts it down (about 5 s), then TM1 itself. Left to itself, TM1's service would leave the
database processes running, to be killed with the container. **Give it time:**
`docker stop -t 120`, or `stop_grace_period: 2m` in compose. The databases it stopped come
back on the next start.

## Ports and settings

One port inside the container, always the same: **4444**, TM1's gateway, for the REST
API, logins and the management API. Map it to any host port with `-p`.

| Variable | Default | |
|---|---|---|
| `TM1_DATASTORE_URL` | | A MongoDB for TM1 to use ([above](#the-datastore-mongodb)) |
| `TM1_DATASTORE_CACHE_GB` | `0.5` | The cache of the MongoDB TM1 runs itself |
| `TM1_ENGINE_BUNDLE`, `TM1_ENGINE_BUNDLE_URL`, `TM1_ENGINE_BUNDLE_SHA256`, `TM1_ENGINE_BUNDLE_DIR` | newest in `/ibm` | Which package to install |
| `TM1_ENGINE_CACHE` | `/tm1/cache` | Where package installs are kept |
| `TM1_ENGINE_START_TIMEOUT` | `300` | Seconds to wait for TM1's gateway before giving up |
| `TM1_ENGINE_STOP_TIMEOUT` | `60` | Seconds to wait for the databases to stop |

The container sets two things in `config.internal.json`, and logs each change: the
gateway's port, and the datastore. Everything else in a `config.internal.json` you mount
is left as it is.

**Not yet in this image:** settings for OAuth 2.0 and pass-through logins, and TLS. You
can set the two logins in `config.internal.json` yourself, as IBM's guide describes. TLS
on the gateway isn't supported yet: the container talks to it over plain HTTP to set TM1
up, check its health and stop it.

## Image

Published to GitHub Container Registry by [`.github/workflows/docker.yml`](../../.github/workflows/docker.yml)
as `ghcr.io/ajmyers/tm1-engine`: `:edge` from each commit to `master`, and `:latest`,
`:1.2.3` with a release. Publishing is off until the repository variable
`PUBLISH_DOCKER_TM1_ENGINE` is `true`.

To build it yourself: `docker build --platform linux/amd64 -t tm1-engine:local .` from
this folder, and set `TM1_ENGINE_IMAGE=tm1-engine:local` in `.env` for compose. The image
is `linux/amd64` only, because TM1 is. On Apple Silicon it runs under Rosetta, which has
the AVX instructions TM1 needs.

Tested with TM1 12.6.7 and MongoDB 8.0, on Apple Silicon.

# tm1-server

A thin Docker runtime for **IBM Planning Analytics / TM1 Server** (Linux x86-64),
built for local development and integration tests of TM1 client libraries.

> **No IBM software is included in this repository or the image.** The image is
> Ubuntu, a few system libraries, and an entrypoint script. You bring your own
> licensed TM1 Server installation, and you are responsible for complying with
> your IBM license terms.

## What you mount

| Container path            | What                                              | Notes |
|---------------------------|---------------------------------------------------|-------|
| `/opt/ibm/cognos/tm1_64`  | Your TM1 Server install (the folder with `bin64/`) | Can be read-only (`:ro`) |
| `/tm1/config`             | `tm1s.cfg` and `tm1s-log.properties`              | Defaults are created if missing. See [Configuration](#configuration). |
| `/tm1/data`               | The TM1 database (cubes, dimensions, processes, ...) | A new database is created if the folder is empty |
| `/tm1/logs`               | `tm1server.log`, `tm1s.log`, `tm1event.log`, process error logs | Set as `LoggingDirectory` |

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

To build it yourself: `docker build -t tm1-server .` from this folder. The image is
`linux/amd64` only, because TM1 Server only runs on x86-64.

## Compose

`compose.yaml` builds the image from this folder by default. To use the published
image, set `TM1_SERVER_IMAGE=ghcr.io/ajmyers/tm1-server:latest` in `.env` and run
`docker compose pull`. Then `cp .env.example .env` and set `TM1_HOME_DIR`:

```sh
docker compose up -d                      # empty database "tm1"   -> https://localhost:8010
docker compose --profile samples up -d    # adds IBM's SData sample -> https://localhost:8011
docker compose logs -f tm1                # server message log
docker compose stop                       # clean shutdown (TM1 saves on SIGINT)
```

| Instance | REST API                       | Login                 |
|----------|--------------------------------|-----------------------|
| `tm1`    | https://localhost:8010/api/v1/ | `admin`, blank password |
| `SData`  | https://localhost:8011/api/v1/ | `admin` / `apple`     |
| Admin    | http://localhost:5895/api/v1/Servers, https://localhost:5898 | – |

The REST API uses IBM's built-in self-signed certificate, so either turn off
certificate verification in your client or set `TM1_USE_SSL=F` to use plain http.

```sh
curl -sk -u admin: 'https://localhost:8010/api/v1/Configuration/ProductVersion/$value'
```

## Getting a `tm1_64` directory

If you already have TM1 Server installed on Linux, mount its `tm1_64` directory.
It is usually `/opt/ibm/cognos/tm1_64`, and you can copy it to your machine.

If you only have the IBM download, `tm1server-<ver>-linuxi38664h_bundle.tar.gz`,
the image includes an optional one-off helper that unpacks it. It reads the
bundle's manifest and extracts the TM1 Server, Admin Server, Tools and Samples
components, which is the same result IBM's installer produces. This also works
on macOS, where IBM's installer won't run.

```sh
docker run --rm \
  -v /path/to/ibm-downloads:/ibm:ro \
  -v /path/to/tm1_64:/opt/ibm/cognos/tm1_64 \
  ghcr.io/ajmyers/tm1-server install
```

Set `-e TM1_BUNDLE=<file name>` to pick a specific bundle (by default it uses the
newest one). Tested with TM1 Server 2.1.19 (11.8.03500.6).

## Configuration

TM1 is started with `-z /tm1/config`, so the `tm1s.cfg` in your config folder is
the file that actually runs. TM1 also writes changes back to it, for example
when you update settings through the REST API's `StaticConfiguration`.

**No `tm1s.cfg` provided:** the container writes a short default one
([`docker/tm1s.cfg.default`](docker/tm1s.cfg.default)) into `/tm1/config`, using
the environment variables below. You can then edit it, and your changes are kept.
A default `tm1s-log.properties` is created the same way.

**Your own `tm1s.cfg` (for example, copied from a server):** it's used as-is. The
container only changes the settings it needs to run:

| Setting | Set to | Why |
|---------|--------|-----|
| `DataBaseDirectory` | `/tm1/data` | Must point to the mounted database |
| `LoggingDirectory` | `/tm1/logs` | Must point to the mounted logs folder |
| `RunningInBackground` | `T` | The server can't wait for console input in a container |
| `AdminHost` | `localhost` | Only when `TM1_ADMIN_SERVER=T` (the default) |

Each change is logged with its old value, and the file is only rewritten when
something actually changes. CRLF line endings are kept. If you mount the folder
read-only (`:ro`), startup fails with a list of the required changes rather than
modifying your file. Once your file has those values, a read-only mount works.

Environment variables:

| Variable | Default | |
|----------|---------|-|
| `TM1_SERVER_NAME` | `tm1` | `ServerName` |
| `TM1_HTTP_PORT` | `8010` | `HTTPPortNumber` (REST API) |
| `TM1_PORT` | `12345` | `PortNumber` |
| `TM1_USE_SSL` | `T` | `UseSSL` |
| `TM1_CFG_<Param>` | | Any `tm1s.cfg` parameter, e.g. `TM1_CFG_MaximumCubeLoadThreads=4` |
| `TM1_SEED` | `empty` | `empty`, `sdata` or `24retail`. Objects to copy into an **empty** `/tm1/data`, taken from the samples in your install. |
| `TM1_ADMIN_SERVER` | `T` | Also run `tm1admsrv` in the container |

The defaults only apply when the container creates `tm1s.cfg`. For an existing
file, these variables change it only if you set them. The compose file sets the
server name and ports so they match its port mappings.

Commands: `start` (default), `install` (see above), or anything else, which is
run as-is (e.g. `docker run --rm -it ... tm1-server bash`).

## Apple Silicon

TM1 is x86-64 only. Docker Desktop runs it under Rosetta, which is fast. The
server is ready in a few seconds. You need to:

1. Run `softwareupdate --install-rosetta --agree-to-license`.
2. In Docker Desktop, enable **Settings → General → Use Rosetta for x86_64/amd64
   emulation on Apple Silicon**.

## Notes and limitations

- Only TM1 Server and Admin Server are included. TM1 Web, PAW and Cognos (CAM)
  security are not. Security mode is 1 (native TM1 users).
- `tm1s.exe` daemonizes itself, so the entrypoint tracks the daemon's PID. On
  `docker stop`, it sends SIGINT, which makes TM1 save data and shut down cleanly.
- The Admin Server writes `tm1admsrv.log` into `bin64/` of the install. If the
  install is mounted read-only, that log isn't written.

# tm1-web

A Docker image that runs **IBM Planning Analytics Spreadsheet Services (TM1 Web)**
for local development and tests. Like [`tm1-server`](../tm1-server/README.md), it's a
thin runtime: Ubuntu, a few OS packages and an entrypoint. **It contains no IBM
software.** You supply your own licensed TM1 Web, as IBM's bundle or an installed
folder.

TM1 Web lists and logs on to the TM1 servers registered with an Admin Server, so
run it next to `tm1-server` on the same Docker network and point it at the Admin
Server with `TM1_ADMIN_HOST`.

```sh
docker network create tm1
docker run -d --name tm1 --network tm1 -e TM1_SEED=sdata -e TM1_SERVER_NAME=SData \
  -v /path/to/ibm-downloads:/ibm:ro ghcr.io/ajmyers/tm1-server
docker run -d --name tm1-web --network tm1 -p 9510:9510 -e TM1_ADMIN_HOST=tm1 \
  -v /path/to/ibm-downloads:/ibm:ro ghcr.io/ajmyers/tm1-web
```

Then open http://localhost:9510/tm1web/ and log on to `SData` as `admin` / `apple`.
The `tm1-server` container runs an embedded Admin Server unless told otherwise, so
`TM1_ADMIN_HOST` is simply that container's name.

With compose, the [`tm1-server` compose file](../tm1-server/compose.yaml) adds TM1 Web
when you ask for the `web` profile: `docker compose --profile web up -d`. It installs
TM1 Web from the same bundles folder as TM1.

With [`pa-setup`](../pa-setup/README.md), a `tm1Web` section in `pa-environment.yaml` runs
it next to any number of TM1 servers on a shared Admin Server, and next to PAW and Cognos.

## What you mount

| Path | What | Required |
|------|------|----------|
| `/opt/ibm/cognos/tm1web` | A TM1 Web install (the folder with `wlp/`, `jre/` and `webapps/`). Can be read-only. | One of these two |
| `/ibm` | The folder holding your `tm1web-*_bundle.tar.gz`, installed on first start | One of these two |
| `/tm1-web/cache` | Where bundle installs are kept, one folder per bundle. Mount a volume here to install only once. | No |
| `/tm1-web/config` | Your own `tm1web_config.xml`, used instead of the install's | No |
| `/tm1-web/logs` | `tm1web/tm1web.log`, and Liberty's and the evalservice's logs in `server/` | No |

## Image

Published to GitHub Container Registry by [`.github/workflows/docker.yml`](../../.github/workflows/docker.yml),
with the same tags as `tm1-server` (`:latest` and `:1.2.3` for releases, `:edge`
for `master`). Publishing is off until the repository variable
`PUBLISH_DOCKER_TM1_WEB` is `true`.

To build it yourself, run `docker build --platform linux/amd64 -t tm1-web:local .`
from this folder. The image is `linux/amd64` only, because TM1 Web's bundled Java is.
On Apple Silicon it runs under Rosetta.

## Getting TM1 Web into the container

The sources work exactly as in `tm1-server`, with `TM1_WEB_` settings:

| # | Source | How |
|---|--------|-----|
| 1 | **An installed `tm1web` folder** | `-v /path/to/tm1web:/opt/ibm/cognos/tm1web:ro` |
| 2 | **Your IBM bundle, mounted** | `-v /path/to/ibm-downloads:/ibm:ro` (optionally `-e TM1_WEB_BUNDLE=<file name>`) |
| 3 | **A download URL** | `-e TM1_WEB_BUNDLE_URL=https://...`, optionally with `TM1_WEB_BUNDLE_SHA256` |
| 4 | **A bundle piped in** | `... \| docker run -i --rm -v tm1web:/opt/ibm/cognos/tm1web ghcr.io/ajmyers/tm1-web install -`, then mount the `tm1web` volume |
| 5 | **A private image with TM1 Web built in** | build `FROM ghcr.io/ajmyers/tm1-web` and run `tm1-web-install`, as in [`tm1-server`'s example](../tm1-server/examples/private-image/Dockerfile); push it only to a private registry |
| 6 | **A named Docker volume** | mount an empty volume at `/opt/ibm/cognos/tm1web`; with a bundle (2 or 3) it's installed into on first start |

**How bundles are installed.** IBM's `tm1web-<ver>-<pa ver>-linuxi38664h_bundle.tar.gz`
holds IBM's installer and a repository zip. Rather than run the installer, the
container follows the zip's manifest and unpacks the `TM1_WebTier` installable:
IBM's Java 17, WebSphere Liberty, and the `tm1web` and `evalservice` apps (about
900 MB). That's what the installer does, and it also works where the installer can't
run. Logs never show a download URL's query string or credentials.

Start times on Apple Silicon (Rosetta): about 45 s for the first start from a
bundle, 15–20 s once installed.

Tested with TM1 Web 11.0.112 (Planning Analytics 2.1.19), against `tm1-server` with
TM1 Server 2.1.19.

## Configuration

The install is only ever read. On start, the container builds its own runtime folder
(`/tm1-web/runtime`) with Liberty's server config and a copy of the `tm1web` app,
and writes the settings below into it. So one install can be shared, read-only, by
any number of containers with different settings.

| Variable | Sets | Default |
|----------|------|---------|
| `TM1_ADMIN_HOST` | `AdminHostName`: the Admin Server TM1 Web lists servers from | unset: users type it on the login page |
| `TM1_ADMIN_PORT` | `AdminHostPort` | the Admin Server's default |
| `TM1_WEB_SERVER_NAME` | `TM1ServerName`: log on to this server without asking | unset |
| `TM1_WEB_CFG_<Key>` | any `tm1web_config.xml` setting, e.g. `TM1_WEB_CFG_HttpSessionTimeout=30` | – |
| `TM1_WEB_HEAP` | the maximum Java heap (`-Xmx`) | `1g` |
| `TM1_WEB_JAVA_OPTS` | extra JVM options, space-separated | – |

The settings are applied on top of your own `tm1web_config.xml` if you mount one at
`/tm1-web/config`, and on top of the install's otherwise. Each change is logged.

IBM's `jvm.options` reserves a 4 GB heap with a 2 GB nursery, which is more than a
test container should take. The container replaces those with `TM1_WEB_HEAP` and lets
the JVM size the nursery.

### Ports

| Port | What |
|------|------|
| 9510 | TM1 Web over HTTP: `/tm1web/` and `/evalservice/` |

The container port is fixed; map it to any host port with `-p <host>:9510`.

## Notes and limitations

- **Admin Server discovery.** TM1 servers register with the Admin Server under their
  container addresses, which other containers on the same Docker network can reach.
  So TM1 Web needs no extra mapping, as long as it's on that network.
- **Universal reports** run through the evalservice, which calls TM1's REST API at the
  `Host` each server registered: `http://<its hostname>:8010`. That name has to
  resolve inside the TM1 Web container. It does on a network you create
  (`docker network create`, or compose), but not on Docker's default bridge network;
  there, add `--add-host <tm1 hostname>:<tm1 ip>`. Checked by sending the evalservice
  an MDX request directly; rendering a universal report workbook in the browser
  hasn't been tested (it needs a workbook made with the Excel add-in).
- **Tested with native TM1 security only.** [`pa-setup`](../pa-setup/README.md) can run TM1
  Web next to TM1 servers in Cognos (CAM) security, and sets Cognos up to send browsers back
  to TM1 Web after logon, but logging on to TM1 Web through Cognos hasn't been tested.
- **HTTP only.** IBM's `server.xml` is used as-is, so HTTPS on 9513 isn't set up.
  Liberty logs `CWWKG0058E ... ssl ... missing the required attribute keyStoreRef`
  at start because of that; it's harmless.
- **Stopping** sends SIGTERM to Liberty, which stops both apps cleanly. The JVM then
  exits with code 143, which is normal for Java.

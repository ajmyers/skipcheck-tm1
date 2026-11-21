# paw

Runs **IBM Planning Analytics Workspace (PAW) Local** next to the
[`tm1-server`](../tm1-server/README.md) containers, for local development and
end-to-end tests.

> **No IBM software is included in this repository.** IBM already ships PAW
> Local as Docker images, with compose files and scripts, in
> `ipa_workspace_local_<release>.<build>.zip`. [`paw-local.sh`](paw-local.sh)
> uses those as they are: it never rebuilds, retags for publishing or
> redistributes them. You bring your own licensed zip, and you are responsible
> for complying with your IBM license terms.

## Quick start

Start TM1 first (see [tm1-server](../tm1-server/README.md)), so there's a
`tm1-admin` and a `tm1` container. Then, from this folder:

```sh
cp /path/to/ipa_workspace_local_2.1.19.1260.zip bundles/
PAW_ACCEPT_LICENSE=true ./paw-local.sh up
```

PAW is at <http://localhost:8800>. Log on as a user of the TM1 server
(`admin` with no password on a new `tm1-server` database).

| Command | What it does |
|---|---|
| `./paw-local.sh up` | Unpacks the zip and loads its images (first time only), writes the config, starts PAW and waits for it. The default. |
| `./paw-local.sh down` | Stops and removes the PAW containers. PAW's data (the `paw_mongo`, `paw_bss` and `paw_redis` volumes) is kept. |
| `./paw-local.sh reset` | Like `down`, and also deletes PAW's data. |
| `./paw-local.sh status` | Lists the PAW containers. |
| `./paw-local.sh logs [service...]` | Shows recent logs of all or the named services, e.g. `logs wa-proxy tm1proxy`. |

## The license

`up` refuses to start unless `PAW_ACCEPT_LICENSE=true` is set. IBM's own
setup accepts the license in its admin tool (a browser page on port 8888), and
this script skips that tool, so the variable is where you accept it. The text
is in the zip:

```sh
unzip -p bundles/ipa_workspace_local_*.zip licenses/LA_en | less
```

## Settings

All are environment variables; `./paw-local.sh help` lists them too.

| Variable | Default | What |
|---|---|---|
| `PAW_ACCEPT_LICENSE` | | `true` to accept IBM's license. Required by `up`. |
| `PAW_ZIP` | newest in `PAW_BUNDLE_DIR` | The PAW Local zip |
| `PAW_BUNDLE_DIR` | `./bundles` | Where to look for the zip |
| `PAW_HOME` | `~/.cache/tm1-paw` | Where the zip is unpacked, one folder per `<release>.<build>` |
| `PAW_NETWORK` | `tm1-admin`'s network, else `paw_default` | The Docker network PAW joins |
| `PAW_TM1_ADMIN_URL` | `http://tm1-admin:5895` | The TM1 Admin Server, as PAW sees it |
| `PAW_AUTH_MODE` | `tm1` | `tm1`: users log on to a TM1 server. `cam`: through Cognos. |
| `PAW_TM1_LOGIN_URL` | `http://tm1:8010` | The TM1 server users log on to (`tm1` mode) |
| `PAW_CAM_SERVLET` | | Cognos dispatcher, e.g. `http://cognos:9300/p2pd/servlet/dispatch` (`cam` mode, required) |
| `PAW_CAM_GATEWAY` | | Cognos gateway URL (`cam` mode, optional) |
| `PAW_CAM_NAMESPACE` | | Cognos namespace ID (`cam` mode) |
| `PAW_TM1WEB_URL` | | TM1 Web, as PAW sees it, e.g. `http://tm1-web:9510` |
| `PAW_HTTP_PORT` / `PAW_HTTPS_PORT` | `8800` / `8843` | Host ports |
| `PAW_JVM_ARGS` | IBM's `-Xmx4096m -Xms4096m` | JVM options of `prism-platform`, the largest service |
| `PAW_WAIT` | `600` | Seconds `up` waits for PAW to answer |

The URLs are as PAW's containers see them, so they use container names, not
`localhost`.

## How it works

1. **Unpack** everything in the zip except `images/images.tar` into
   `PAW_HOME/<release>.<build>`, once.
2. **Load images** by streaming `images/images.tar` from the zip into
   `docker load`, with no 8 GB copy on disk. This is skipped when every image in
   `images/images.env` is already loaded. It takes about 80 s.
3. **Write secrets.** IBM's `scripts/paw.sh` makes PAW's database password and
   cookie secret with `tr` on `/dev/urandom`. On macOS, `tr` fails on binary
   input, so the files come out **empty**, Mongo starts with no root user, and
   PAW shows "Planning Analytics Workspace is unavailable" forever. This script
   writes them first, with `LC_ALL=C`.
4. **Write `config/paw.env`** from the settings above, on every `up`.
5. **Run IBM's `scripts/paw.sh`** as it is, so IBM's compose files decide
   what runs. It calls `software/docker-compose`, a Linux x86-64 binary, so
   that file is replaced by a two-line script that runs `docker compose`.
6. **Wait** until `/login` answers.

### Why PAW joins TM1's network

The Admin Server tells PAW each TM1 server's **IP address** on the network where
that server registered, and three PAW services (`wa-proxy`, `prism-platform`,
`tm1proxy`) connect to that address directly. On a different network than TM1,
PAW logs on fine, lists the servers, and then times out on every TM1 request. So
PAW goes on the network of the running `tm1-admin` container, or on
`PAW_NETWORK`.

## Limits

- **One PAW per Docker daemon.** IBM's compose files give every container a fixed
  name (`pa-gateway`, `mongo`, ...), under the compose project `paw`.
- **Heavy.** About 30 containers, using around 15 GB of RAM in total. Give Docker
  Desktop well over that. From `up` to a working PAW takes about a minute
  (more on the first start).
- **linux/amd64 only.** On Apple Silicon, IBM's images run under Rosetta.
- **No TLS.** PAW is served over http, and talks to TM1 over http.
- Tested with PAW Local 2.1.19 build 1260 and TM1 11.8.03500.6.

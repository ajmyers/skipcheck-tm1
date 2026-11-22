# pa-setup

One command for a whole local **IBM Planning Analytics** environment in Docker: TM1
servers (with a shared Admin Server), TM1 Web, Planning Analytics Workspace, and Cognos
Analytics security with test users through LDAP and OpenID Connect. You describe what
you want in **`pa-environment.yaml`**, and `pa-setup` generates a compose project, starts
it, waits until everything is healthy, and prints every address and login.

> **No IBM software is included.** The images are thin runtimes; you bring your own
> licensed IBM downloads, and you are responsible for complying with your IBM license
> terms.

```sh
mkdir pa && cd pa
curl -fsSL https://ajmyers.net/pa/setup.sh | sh -s -- init    # writes pa-environment.yaml
# edit it: point ibm at your downloads, choose the parts
curl -fsSL https://ajmyers.net/pa/setup.sh | sh -s -- up
```

Windows (PowerShell):

```powershell
& ([scriptblock]::Create((irm https://ajmyers.net/pa/setup.ps1))) init
& ([scriptblock]::Create((irm https://ajmyers.net/pa/setup.ps1))) up
```

Or save [`setup.sh`](setup.sh) / [`setup.ps1`](setup.ps1) next to your file and run it
from there. **Docker is all you need**: the scripts only check it and run the setup tool
in a container (`ghcr.io/ajmyers/pa-setup`), with the Docker socket and the current
folder mounted. `PA_IMAGE_TAG` picks the images' tag: `edge` (built from `master`) until
the first release.

## Commands

| Command | What it does |
|---|---|
| `init` | Writes a starting `pa-environment.yaml` |
| `up` (default) | Writes `compose.yaml`, starts everything, waits until it's healthy, prints the URLs. Safe to rerun after changing the file. |
| `urls` | Prints every service's address and login |
| `status` | Lists the containers and their health |
| `logs [service...]` | Recent logs, e.g. `logs planning cognos` |
| `down` | Stops and removes the containers. Data (TM1 databases, installs, the Cognos content store) is kept. |
| `generate` | Only writes `compose.yaml` |

`-f other.yaml` uses another file. The generated `compose.yaml` works with plain
`docker compose` too, but change `pa-environment.yaml` and rerun `up` rather than editing it.

## pa-environment.yaml

Every part is optional. A file with everything ([`examples/everything.yaml`](examples/everything.yaml)):

```yaml
version: 1
ibm: ~/Downloads/ibm            # your IBM downloads: tm1server and tm1web bundles, the Cognos casrv zip, the PAW Local zip
tm1:
  servers:
    planning: { port: 8010, seed: sdata }
    reporting: { port: 8011 }
tm1Web: { port: 9510 }
cognos:
  port: 9300
  users:
    - admin:adminpw:Admins
    - analyst:analystpw:Analysts,Readers
    - viewer:viewerpw:Readers
  oidc: { keycloakPort: 8180 }   # adds Keycloak, and an OIDC namespace next to LDAP
  cam: { namespace: ldap, admins: [admin] }
# paw: { port: 8800, acceptLicense: true }
```

| Setting | Default | |
|---|---|---|
| `version` | (required) | `1` |
| `name` | the folder's name | The compose project name |
| `ibm` | | The folder holding your IBM downloads. Relative to this file, or starting with `~`. |
| `files` | `./pa-files` | Where each TM1 server keeps `config`, `data` and `logs`, in a folder per server |
| `images` | the published images, `:edge` until the first release (`PA_IMAGE_TAG` picks a release) | `tm1Server`, `tm1Web`, `cognosServer`, `keycloak`: other images, e.g. local builds or **private images with IBM software built in** |
| `tm1.servers.<name>` | | A TM1 server. `port` (REST API; default 8010, 8011, ...), `seed` (`empty`, `sdata`, `24retail`), `cfg` (any `tm1s.cfg` parameter) |
| `tm1.adminServer` | automatic | A shared Admin Server: on with more than one server, where every server registers. `adminPort` defaults to 5895. |
| `tm1Web` | off | `true`, or `port` (9510) and `heap` (`1g`). Lists every server. |
| `paw` | off | `acceptLicense: true` (required: IBM's PAW license), `port` (8800), `zip` (default: the newest in `ibm`) |
| `cognos` | off | `port` (9300), `users` (`user:password[:group,group]`), `oidc` (`true` or `keycloakPort`, 8180), `cam`, `memory` (dispatcher heap MB, 6144) |
| `cognos.cam` | LDAP, the first user | With Cognos, every TM1 server runs in CAM security: `namespace` (`ldap` or `oidc`) and `admins`, the test users who are TM1 administrators. Every user's groups become TM1 groups. |

The file is checked strictly: an unknown setting, a port used twice, or a part that
needs another one fails with a message saying what to fix. Editors that understand JSON
Schema can check it as you type with [`pa-environment.schema.json`](pa-environment.schema.json)
(in VS Code with the YAML extension, add `# yaml-language-server: $schema=<its raw URL>`).

**Secrets go in `.env`**, which `up` creates next to the file: download links (e.g.
pre-signed S3 URLs) instead of files in `ibm` (`TM1_BUNDLE_URL`, `TM1_WEB_BUNDLE_URL`,
`COGNOS_ZIP_URL`). Test users' passwords are in the YAML: they're throwaway, for tests.

## What you get

`up` ends with every address and login, e.g.:

```
  TM1 planning      http://localhost:8010/api/v1/   (CAM, namespace LDAP: admin, analyst, viewer)
  TM1 reporting     http://localhost:8011/api/v1/   (CAM, namespace LDAP: admin, analyst, viewer)
  TM1 Admin Server  http://localhost:5895/api/v1/Servers
  TM1 Web           http://localhost:9510/tm1web/   (as for TM1)
  Cognos            http://localhost:9300/bi/   (namespaces LDAP and OIDC; passwords in pa-environment.yaml)
  Keycloak          http://localhost:8180/   (console: admin / admin)
```

Each part is the project's own image, documented in its README:
[`tm1-server`](../tm1-server/README.md) (CAM security: [here](../tm1-server/README.md#cognos-cam-security)),
[`tm1-web`](../tm1-web/README.md), [`paw`](../paw/README.md) and
[`cognos-server`](../cognos-server/README.md) (users, LDAP and [OIDC](../cognos-server/README.md#oidc)).
For tests, `tm1-testcontainers`'s `PlanningAnalyticsEnvironment` runs the same parts.

**Memory:** about 0.5 GB per TM1 server, 1 GB for TM1 Web, 8 GB for Cognos and 15 GB for
PAW. `up` warns when Docker has less than the parts need; raise it in Docker Desktop
(Settings > Resources).

## How it works

1. **Check and fill in** `pa-environment.yaml`: defaults, ports, and what needs what.
2. **Write `compose.yaml`**:
   - TM1 servers named by their key, sharing one TM1 install volume (installed from the
     bundle by the first container), registered with `tm1-admin` when there are several;
   - TM1 Web pointed at that Admin Server;
   - Cognos with the users, plus Keycloak for OIDC;
   - TM1 in CAM security pointed at Cognos (`TM1_CAM_*`).
   - Startup order uses healthchecks: Keycloak, then Cognos, then the TM1 servers, then
     TM1 Web.
3. **`docker compose up --wait`.** Paths in the file are your machine's (Windows paths
   work); the Docker daemon resolves them.
4. **PAW** runs IBM's own compose files through [`paw-local.sh`](../paw/README.md), in a
   container like the one `tm1-testcontainers` uses, joined to the project's network.
   With Cognos, PAW uses CAM (`PAW_AUTH_MODE=cam`). **PAW through pa-setup hasn't been
   tested yet.**

Tested on Apple Silicon with TM1 2.1.19, TM1 Web 2.1.19, Cognos 12.1.1 and Keycloak 26.4:
`examples/everything.yaml` (on other ports) came up healthy in about 4 minutes, with CAM
logons on both servers and an OIDC token logon through Cognos.

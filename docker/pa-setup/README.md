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
curl -fsSL https://skipcheck.io/pa/setup.sh | sh -s -- init    # writes pa-environment.yaml
# edit it: point ibm at your downloads, choose the parts
curl -fsSL https://skipcheck.io/pa/setup.sh | sh -s -- up
```

Windows (PowerShell):

```powershell
& ([scriptblock]::Create((irm https://skipcheck.io/pa/setup.ps1))) init
& ([scriptblock]::Create((irm https://skipcheck.io/pa/setup.ps1))) up
```

Or save [`setup.sh`](setup.sh) / [`setup.ps1`](setup.ps1) next to your file and run it
from there. A coding agent can do all of this for you with the
[`pa-setup` skill](../../agent-plugin/README.md). **Docker is all you need**: the scripts only check it and run the setup tool
in a container (`ghcr.io/ajmyers/pa-setup`), with the Docker socket and the current
folder mounted. `PA_IMAGE_TAG` picks the images' tag: `edge` (built from `master`) until
the first release.

## Commands

| Command | What it does |
|---|---|
| `init` | Writes a starting `pa-environment.yaml` |
| `up` (default) | Writes `compose.yaml`, starts everything, waits until it's healthy, prints the URLs. Safe to rerun after changing the file: a part you took out is stopped and removed (PAW isn't: run `down` before taking `paw` out). |
| `urls` | Prints every service's address and login |
| `status` | Lists the containers and their health |
| `logs [service...]` | Recent logs, e.g. `logs planning cognos` |
| `down` | Stops and removes the containers. Data (TM1 databases, installs, the Cognos content store) is kept. |
| `generate` | Only writes `compose.yaml` |
| `credentials` | With a TM1 v12 engine: every address of the engine as it is now, and where each credential is, as seen from your computer. `--json` for a program (it includes the secrets). |
| `session [database [user]]` | With an engine: logs in and prints the session cookie, for `curl -b`. Default: the file's first database, as `admin`. |
| `token [user]` | With `tm1Engine.oauth2`: prints an access token for a user, for `curl -H "Authorization: Bearer ..."`. Default: the first user with a password. |
| `database create <name>`, `database drop <name>` | With an engine: creates a database (about 13 s, with the file's users) or deletes one. `instance/name` for another instance than `tm1`. A database the file names isn't dropped: `up` would make it again, so take it out of the file first. |

`-f other.yaml` uses another file in the same folder; give each file its own `name`, or one's `up` and `down` remove the other's containers. The generated `compose.yaml` works with plain
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
| `files` | `./pa-files` | Where each TM1 server keeps `config`, `data` and `logs`, in a folder per server, and the engine everything of its own, in `tm1-engine` |
| `images` | the published images, `:edge` until the first release (`PA_IMAGE_TAG` picks a release) | `tm1Server`, `tm1Web`, `cognosServer`, `keycloak`, `tm1Engine`, `mongo`: other images, e.g. local builds or **private images with IBM software built in** |
| `tm1.servers.<name>` | | A TM1 server. `port` (REST API; default 8010, 8011, ...), `seed` (`empty`, `sdata`, `24retail`), `cfg` (any `tm1s.cfg` parameter) |
| `tm1.adminServer`, `tm1.adminPort` | automatic, 5895 | A shared Admin Server: on with more than one server, where every server registers |
| `tm1Web` | off | `true`, or `port` (9510) and `heap` (`1g`). Lists every server. |
| `paw` | off | `acceptLicense: true` (required: IBM's PAW license), `port` (8800), `zip` (default: the newest in `ibm`) |
| `cognos` | off | `port` (9300), `users` (`user:password[:group,group]`), `oidc` (`true` or `keycloakPort`, 8180), `cam`, `memory` (dispatcher heap MB, 6144) |
| `tm1Engine` | off | A **TM1 v12 engine** (Planning Analytics 3.x) and its MongoDB: `true`, or `port` (4444), `databases`, `users`, `datastore`. [Below](#tm1-v12). |
| `cognos.cam` | LDAP, the first user | With Cognos, every TM1 server runs in CAM security: `namespace` (`ldap` or `oidc`) and `admins`, the test users who are TM1 administrators. Every user's groups become TM1 groups. |

The file is checked strictly: an unknown setting, a port used twice, or a part that
needs another one fails with a message saying what to fix. Editors that understand JSON
Schema can check it as you type with [`pa-environment.schema.json`](pa-environment.schema.json)
(in VS Code with the YAML extension, add `# yaml-language-server: $schema=<its raw URL>`).

**Secrets go in `.env`**, which `up` creates next to the file: download links (e.g.
pre-signed S3 URLs) instead of files in `ibm` (`TM1_BUNDLE_URL`, `TM1_WEB_BUNDLE_URL`,
`COGNOS_ZIP_URL`, `TM1_ENGINE_BUNDLE_URL`). Test users' passwords are in the YAML: they're throwaway, for tests.

## TM1 v12

`tm1Engine` runs a TM1 v12 engine, from IBM's `tm1-v<version>-standalone-linux.tgz` in `ibm`.
It sits beside `tm1`, so one environment can run both versions. The smallest file:

```yaml
version: 1
ibm: ~/Downloads/ibm
tm1Engine: true            # one database, tm1; log in as admin
```

[`examples/tm1-engine.yaml`](examples/tm1-engine.yaml) has every setting.

| Setting | Default | |
|---|---|---|
| `tm1Engine.port` | 4444 | TM1's gateway on your computer: the REST API, logins and the management API |
| `tm1Engine.databases.<name>` | one, `tm1` | A database. Names need at least 3 characters, and `Sales` and `sales` are two. `instance` puts it in another instance than `tm1`: an instance has its own credentials, sessions and URL prefix. |
| `tm1Engine.users` | | Users for every database: `user`, `user::group,group`, or a mapping with `name` and `groups`. `admin` is always there. The middle part is a password, as in `cognos.users`, and may be empty; with `oauth2` it's what the user logs in with (`user:password` or `user:password:group,group`). |
| `tm1Engine.datastore.port` | not published | Publishes the engine's MongoDB on your computer only (`127.0.0.1`). `up` prints its login. |
| `tm1Engine.oauth2` | off | **OAuth 2.0 logins:** `true`, or `keycloakPort` (8181). Adds a Keycloak for the engine, with the users who have a password. |
| `tm1Engine.passthrough` | off | **Pass-through logins:** `server`, the name of one of `tm1.servers`, whose users log in to TM1 v12 with their name and password there. Not with `oauth2`. |

- **One engine holds every database.** Unlike TM1 11, a database isn't a container: `database
  create` adds one to the running engine in about 13 seconds.
- **TM1 v12 keeps no passwords.** A program logs a user in by name, with the client ID and secret
  of the instance's application. `up` prints where those are, and `session` does it for you:

  ```sh
  curl -b "$(./setup.sh session planning analyst)" "http://localhost:4444/tm1/api/v1/Databases('planning')/Cubes"
  ```

  A user's password in the file is for OAuth 2.0 logins, below. Without them it's accepted
  and not used.
- **OAuth 2.0 logins** (`oauth2: true`) add a Keycloak that the engine takes tokens from. It has
  a realm `tm1`, a client `tm1`, and each user of the file who has a password. A program gets a
  token from Keycloak with the user's password and sends it to TM1; `token` does the first
  half for you:

  ```sh
  curl -H "Authorization: Bearer $(./setup.sh token analyst)" "http://localhost:4444/tm1/api/v1/Databases('planning')/Cubes"
  ```

  - The name and password logins above still work beside it.
  - Tokens name the issuer `http://tm1-engine-keycloak:8080/realms/tm1`, Keycloak's address
    inside the environment, whichever way you fetch them: TM1 has to reach the issuer a token
    names. `up` prints Keycloak's address on your computer (`http://localhost:8181/realms/tm1`).
  - It's a Keycloak of the engine's own. `cognos.oidc` has another: Cognos needs its issuer
    to be an address your browser reaches, and the engine one it reaches itself.
  - `oauth2: false` turns it off again on the next `up`, and keeps the passwords in the file
    for next time. Taken out altogether, a user written `user:password` is refused: without
    `oauth2` in the section that form is ambiguous.
  - Changing a user or a password makes a new Keycloak on the next `up`, and restarts the
    engine with it: the new realm signs with new keys.
  - Keycloak is published on your computer only (`127.0.0.1`): its console's login is
    `admin` / `admin`.
  - pa-setup owns the engine's OAuth 2.0 setting: one you set by hand in the kept
    `config.internal.json` is removed on the next `up` without `oauth2`.
- **Pass-through logins** (`passthrough: { server: legacy }`) have TM1 v12 ask a TM1 11 server of
  the same file who a name and password are. That server's users then log in to TM1 v12 with
  what they have there. Without `cognos` in the file, that's Basic authentication on any
  request (with it, see "With `cognos` in the file" below):

  ```sh
  curl -u admin:apple "http://localhost:4444/tm1/api/v1/Databases('planning')/Cubes"
  ```

  - A user has to be in the v12 database too, to do anything there: list them in
    `tm1Engine.users`, by name alone. `admin` always is. pa-setup doesn't create users on the
    TM1 11 server, and a name there with a space or another character `users` doesn't take
    can't be listed.
  - **An empty TM1 11 database's `admin` has no password,** and logs in to TM1 v12 the same
    way (`curl -u admin:`): anyone who reaches the engine's port is its administrator, as on
    the TM1 11 server itself. `seed: sdata` gives `admin` / `apple`.
  - The server has to answer plain HTTP on its REST port, as it does unless its `cfg` or a
    kept `tm1s.cfg` turns `UseSSL` on. `cfg: { UseSSL: T }` is refused with `passthrough`.
  - The engine starts once that server is healthy, and not without it.
  - It can't be on with `oauth2`: TM1 would take tokens and refuse a name and password.
  - Logins through the instance's application (`setup.sh session`) still work beside it.
  - pa-setup owns the engine's pass-through setting, as its OAuth 2.0 one: one you set by
    hand in the kept `config.internal.json`, to a server outside the file say, is removed on
    the next `up` without `passthrough`.
  - **With `cognos` in the file** the server is in CAM security, and Cognos's users log in
    to TM1 v12 as they do to it: `Authorization: CAMNamespace <base64 of
    user:password:namespace>`, or `CAMPassport` with a passport from a Cognos logon. Basic
    gets 401. (A server whose `cfg` sets another `IntegratedSecurityMode` than 4 or 5 isn't
    in CAM security, and is treated as one without `cognos`.)
    - The engine's users are then Cognos's, each under its CAMID: `tm1Engine.users` names
      some of `cognos.users`, with the groups they have in TM1 v12 (it learns none from
      Cognos). `cognos.cam.admins` are administrators here too.
    - `setup.sh session` still logs in as the database's own `admin`. A Cognos user is
      there under its CAMID, not its short name:
      `./setup.sh session planning 'CAMID("LDAP:u:uid=analyst,ou=people")'` (with the OIDC
      namespace, `'CAMID("OIDC:u:analyst")'`). Not tried through `setup.ps1`.
    - **A user taken out of `cognos.cam.admins` stays an administrator of TM1 v12,** as of
      TM1 11: users and groups are added, never removed. Take the user out of `ADMIN` by
      hand, or start again from an empty `pa-files/tm1-engine`.
    - It needs a `tm1-engine` image that knows `TM1_CAM_NAMESPACE`. One pulled earlier
      makes the users under their short names, and every Cognos login gets 403:
      `docker pull ghcr.io/ajmyers/tm1-engine:edge`.
    - An earlier version of this page said TM1 v12 refused the header CAM logins use. It
      passes it on.
  - Tried against a TM1 11 server with users of its own, and in CAM security with the LDAP
    and the OIDC namespace.
  - `PlanningAnalyticsEnvironment.fromConfig` doesn't read `passthrough` yet: a file with it
    is refused there as an unknown setting.
- **Everything the engine stores is in `pa-files/tm1-engine`:** `deployment` (settings, the
  databases and logs), `credentials`, and `datastore` (MongoDB's data). Keep or delete the three
  together: a database's changes since its last save are in MongoDB.
- **On Windows, MongoDB's data is in a Docker volume instead** (`<name>_tm1-engine-datastore`):
  MongoDB needs a Linux filesystem, which a Windows folder shared into Docker isn't. Remove it with
  the folder: `docker volume rm`.
- **The credential files are written by the engine, as root.** On Linux you may not be able to read
  them: `credentials --json` prints what they hold.
- **Memory:** about 1 GB for the engine and MongoDB with two databases (0.7 GB just started, 1.1 GB
  after a day's use, with TM1 12.6.7), and about 0.7 GB more for Keycloak with `oauth2`.
- **TM1 Web, PAW and Cognos security are TM1 11's** here: they need `tm1.servers`.

The engine is the project's [`tm1-engine`](../tm1-engine/README.md) image, which says more about
instances, logins and what's stored where.

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
For tests, `tm1-testcontainers`'s `PlanningAnalyticsEnvironment` runs the same parts, and
`PlanningAnalyticsEnvironment.fromConfig` reads this same file (in Java, Python, .NET and TypeScript).

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
   container like the one `tm1-testcontainers` uses, joined to the project's network, and
   `up` waits until its logon page answers there. With Cognos, PAW uses CAM security:
   browsers log on at Cognos (and through the OIDC namespace, at Keycloak), and come back
   to PAW through IBM's `pmhub.html`, which Cognos installs from your TM1 bundle.

**Browser cookies:** everything is on `localhost`, and browsers share cookies across ports.
After Cognos is reinstalled, an old cookie can make Cognos answer `DPR-ERR-2107 The User
Capabilities Cache cookie cannot be decoded`: clear `localhost`'s cookies, or use a private
window.

Tested on Apple Silicon with TM1 2.1.19, TM1 Web 2.1.19, Cognos 12.1.1, Keycloak 26.4 and
PAW Local 2.1.19: everything, PAW included, came up in about 4 minutes. That covered CAM
logons on both servers, an OIDC token logon, and PAW's browser logon through Cognos and
Keycloak.

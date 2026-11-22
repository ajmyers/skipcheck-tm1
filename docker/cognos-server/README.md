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
| `/cognos/postgres` | The embedded content store | Mount a volume to keep it. Without one, the content store goes away with the container. Never share it between containers. |

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
(or `--target DIR`) and exits: `install [URL | file | -]`. It installs only into an
empty directory or volume. If an install is interrupted, the next start (or `install`)
removes the unfinished one and starts over, as long as the zip is still available. Run
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

## Users

List test users, and the container gives Cognos an **LDAP namespace** with them.
An LDAP directory (OpenLDAP) runs inside the container and is rebuilt from your
list on every start, so the list is the only thing to maintain:

```sh
docker run -d --name cognos -p 9300:9300 --stop-timeout 180 \
  -e COGNOS_USERS="admin:adminpw:Admins analyst:analystpw:Analysts,Readers viewer:viewerpw:Readers" \
  -v cognos-install:/opt/ibm/cognos/analytics ghcr.io/ajmyers/cognos-server
```

Each entry is `user:password[:group,group,...]`, separated by spaces or newlines.
Groups are created from the entries that name them. For a longer list, mount a
file and set `COGNOS_USERS_FILE` (same format, one per line; `#` at the start of a
line or after a space starts a comment).
Names are letters, digits, `.`, `_` and `-`. A password can't contain `:` or
spaces.

With users, **anonymous access is off** (`COGNOS_ANONYMOUS=T` turns it back on),
and the namespace is called `LDAP` (`COGNOS_NAMESPACE`). That name is the
`CAMNamespace` clients log on with:

```sh
curl -X PUT http://localhost:9300/api/v1/session -H 'Content-Type: application/json' \
  -d '{"parameters":[{"name":"CAMNamespace","value":"LDAP"},
                     {"name":"CAMUsername","value":"analyst"},{"name":"CAMPassword","value":"analystpw"}]}'
```

Cognos sees users as `CAMID("LDAP:u:uid=analyst,ou=people")` and groups as
`CAMID("LDAP:g:cn=analysts,ou=groups")`, which is what TM1 shows in CAM security
mode. Different groups give you different TM1 security profiles to test: see
[Cognos (CAM) security](../tm1-server/README.md#cognos-cam-security) in `tm1-server`.

**Every user is a Cognos administrator.** A new content store puts `Everyone` in
Cognos's System Administrators role. That's convenient for tests, and it doesn't
affect TM1, whose security comes from the groups.

The directory listens only inside the container (127.0.0.1:389), under
`dc=cognos,dc=local` (`COGNOS_LDAP_BASE`): users in `ou=people`, groups in
`ou=groups`.

## OIDC

Cognos can also log users on through an OpenID Connect provider. For tests, run
[Keycloak](https://www.keycloak.org) next to it and point `COGNOS_KEYCLOAK_URL` at it:
the container builds a Keycloak realm from the same [`COGNOS_USERS`](#users) list (users,
groups, clients, a `groups` claim) on every start, and adds an `OIDC` namespace for it,
next to `LDAP`.

```sh
docker network create pa
docker run -d --name keycloak --network pa -p 8180:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin -e KC_HTTP_ENABLED=true \
  -e KC_HOSTNAME=http://localhost:8180 -e KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true \
  quay.io/keycloak/keycloak:26.4 start-dev
docker run -d --name cognos --hostname cognos --network pa -p 9300:9300 --stop-timeout 180 \
  -e COGNOS_USERS="admin:adminpw:Admins analyst:analystpw:Analysts,Readers viewer:viewerpw:Readers" \
  -e COGNOS_KEYCLOAK_URL=http://keycloak:8080 \
  -v cognos-install:/opt/ibm/cognos/analytics ghcr.io/ajmyers/cognos-server
```

**Keycloak's two addresses.** Browsers reach Keycloak at `http://localhost:8180` and
Cognos at `http://keycloak:8080`, but both must see the same token issuer.
`KC_HOSTNAME` fixes the browser address, which is also the issuer, and
`KC_HOSTNAME_BACKCHANNEL_DYNAMIC` lets Cognos use the in-network one. `COGNOS_PUBLIC_URL`
(default `http://localhost:9300`) is Cognos's address for browsers, where Keycloak sends
them back to.

Three ways to log on, all ending in a CAM passport that TM1 accepts
([`tm1-server`'s CAM security](../tm1-server/README.md#cognos-cam-security)):

| Logon | How | |
|---|---|---|
| **Interactive** | A browser opens http://localhost:9300/bi/, picks `OIDC`, and logs on at Keycloak | The authorization-code flow with PKCE |
| **Password** | `CAMNamespace` `OIDC`, `CAMUsername`, `CAMPassword`, e.g. TM1's `Authorization: CAMNamespace <base64 user:password:OIDC>` | Cognos does a password grant at Keycloak |
| **Token** | An app that already has a user's ID token logs on to Cognos with `CAMNamespace` `OIDC` and `CAMOIDCIdToken`, then sends TM1 `Authorization: CAMPassport <passport>` | No browser and no password |

The token route, from the command line:

```sh
id_token=$(curl -s -d grant_type=password -d client_id=tm1-app -d username=viewer -d password=viewerpw \
  -d scope=openid http://localhost:8180/realms/cognos/protocol/openid-connect/token | jq -r .id_token)
curl -s -c jar -X PUT http://localhost:9300/api/v1/session -H 'Content-Type: application/json' \
  -d "{\"parameters\":[{\"name\":\"CAMNamespace\",\"value\":\"OIDC\"},{\"name\":\"CAMOIDCIdToken\",\"value\":\"$id_token\"}]}"
passport=$(awk '/cam_passport/ {print $7}' jar)      # TM1: Authorization: CAMPassport $passport
```

**Cognos checks a token's audience** against its own client (`cognos`), and refuses others
("Audience in ID token does not match configured client ID"). The realm's `tm1-app` client
is for apps: a public client whose tokens carry `cognos` in their audience. A real
provider needs the same: an audience mapper, or tokens issued to Cognos's client. Tokens
are also checked against the provider's signing keys (JWKS) and issuer.

Users are `CAMID("OIDC:u:<username>")` and groups `CAMID("OIDC:g:<group, lower case>")`.
Cognos only knows a user's groups from their token, so it can't list or search groups.

**PAW and TM1 Web through Cognos.** With Cognos (CAM) security, PAW and TM1 Web send
browsers to Cognos to log on, and Cognos sends them back through IBM's `pmhub.html`, which
only returns to addresses on its list. Set `COGNOS_PMHUB_URLS` to their addresses in the
browser (e.g. `http://localhost:8800 http://localhost:9510`; `pa-setup` does this), and the
container installs IBM's interoperability files from `bi_interop.zip`. It finds that zip in
`/ibm`, on its own or inside the TM1 Server bundle (the `biinterop` part of `PAA_Agent`),
since it's IBM software the image can't carry. Through the OIDC namespace, a PAW logon then
goes PAW, Cognos (pick OIDC), Keycloak, Cognos, PAW.

**Another OIDC provider:** set `COGNOS_OIDC_DISCOVERY_URL`, `COGNOS_OIDC_CLIENT_ID` and
`COGNOS_OIDC_CLIENT_SECRET` instead of `COGNOS_KEYCLOAK_URL`. Register
`<COGNOS_PUBLIC_URL>/bi/completeAuth.jsp` as a redirect URI, allow the password grant if
you want password logons, and send the groups in a claim (`COGNOS_OIDC_GROUPS_CLAIM`,
default `groups`). Users are named by `COGNOS_OIDC_CAMID_CLAIM` (default
`preferred_username`). This has only been tested with Keycloak.

## Environment variables

| Variable | Default | |
|----------|---------|-|
| `COGNOS_ZIP_URL` | | URL of a repository zip to download and install |
| `COGNOS_ZIP` | newest in `/ibm` | Zip file name in `/ibm`, or an absolute path |
| `COGNOS_ZIP_SHA256` | | Expected SHA-256 of the zip. Verified before installing. |
| `COGNOS_ZIP_DIR` | `/ibm` | Where to look for the zip |
| `COGNOS_INSTALLABLES` | `CASRVR_DataTier CASRVR_AppTier` | Components to install from the manifest |
| `COGNOS_USERS` | | Test users, `user:password[:group,group]` separated by spaces ([Users](#users)) |
| `COGNOS_USERS_FILE` | | A file with more users, one per line |
| `COGNOS_NAMESPACE` | `LDAP` | The namespace's ID, the `CAMNamespace` users log on with |
| `COGNOS_ANONYMOUS` | `F` with users, else `T` | Allow anonymous access |
| `COGNOS_LDAP_BASE` | `dc=cognos,dc=local` | Base DN of the embedded directory |
| `COGNOS_KEYCLOAK_URL` | | A Keycloak, as the container reaches it: builds a realm from the users and an `OIDC` namespace ([OIDC](#oidc)) |
| `COGNOS_KEYCLOAK_ADMIN` / `COGNOS_KEYCLOAK_ADMIN_PASSWORD` | `admin` / `admin` | Keycloak's admin login |
| `COGNOS_KEYCLOAK_REALM` | `cognos` | The realm, rebuilt on every start |
| `COGNOS_OIDC_DISCOVERY_URL`, `COGNOS_OIDC_CLIENT_ID`, `COGNOS_OIDC_CLIENT_SECRET` | | Any other OIDC provider, by hand |
| `COGNOS_OIDC_NAMESPACE` | `OIDC` | The OIDC namespace's ID |
| `COGNOS_OIDC_SCOPE` | `openid profile email` | Scopes Cognos asks for |
| `COGNOS_OIDC_CAMID_CLAIM` / `COGNOS_OIDC_GROUPS_CLAIM` | `preferred_username` / `groups` | The claims users are named by, and their groups come from |
| `COGNOS_PUBLIC_URL` | `http://localhost:9300` | Cognos as browsers reach it: OIDC providers send them back to `/bi/completeAuth.jsp` there |
| `COGNOS_PMHUB_URLS` | | PAW and TM1 Web, as browsers reach them, e.g. `http://localhost:8800`: installs IBM's `bi_interop.zip` files so logons through Cognos go back to them |
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
| `start` (default) | Cognos, the embedded PostgreSQL unless `COGNOS_CS_HOST` is set, and the LDAP directory when users are given | `GET /bi/v1/login` on 9300 returns 200 (anonymous session) or 441 (logon prompt) |
| `install [SOURCE]` | Installs Cognos from a zip and exits ([above](#getting-cognos-into-the-container)) | – |

Anything else is run as-is (e.g. `docker run --rm -it ... cognos-server bash`).

**Stopping.** `docker stop` asks Cognos to shut down and waits for it, which takes a
minute or two. Docker's default is only 10 seconds before it kills the container,
so give it longer: `docker stop --time 180`, `--stop-timeout 180` on `docker run`,
or `stop_grace_period: 3m` in compose. A killed Cognos leaves a configuration lock
behind; the next start removes it.

## Security

Without [users](#users), Cognos allows **anonymous access**: anyone who can reach
port 9300 has full access. With users, logons are required, but every user is a
Cognos administrator, and the passwords are whatever you listed. Keycloak runs in
development mode with an `admin`/`admin` login. This is a test setup: keep it on your
machine or a private network.

## Image

Published to GitHub Container Registry by [`.github/workflows/docker.yml`](../../.github/workflows/docker.yml),
with the same tags as `tm1-server` (`:latest` and versions on release, `:edge` from
`master`), once the repository variable `PUBLISH_DOCKER_COGNOS_SERVER` is `true`.
Until then it's built and smoke-tested but not pushed.

To build it yourself, run `docker build --platform linux/amd64 -t cognos-server .`
from this folder. The image is `linux/amd64` only, because Cognos Analytics for
Linux is x86-64 software. On Apple Silicon it runs under Rosetta (Docker Desktop's
**Use Rosetta for x86_64/amd64 emulation** setting).

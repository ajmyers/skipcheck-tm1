# skipcheck-tm1-testcontainers (Python)

> **Pre-1.0:** the API may change between minor versions (0.1 → 0.2) until 1.0.0. See [Stability](https://github.com/ajmyers/skipcheck-tm1#stability).

Run tests against a real IBM Planning Analytics (TM1) server in Docker, with
[testcontainers-python](https://testcontainers-python.readthedocs.io). The Python port of the
Java [`tm1-testcontainers`](../../java/tm1-testcontainers/README.md) module: the same classes, sources,
settings, images, mounts and messages, named the Python way. Built on the
[`tm1-server`](../../docker/tm1-server) image, and on [`tm1-engine`](../../docker/tm1-engine) for
[TM1 v12](#tm1-v12).

> **No IBM software is included.** You supply your own licensed TM1 Server for Linux, and
> you're responsible for following your IBM license terms. Without it, tests that need TM1
> are skipped.

```sh
pip install -e ./python/tm1-client -e ./python/tm1-testcontainers
```

Requires Python 3.11+, testcontainers 4.15+ and [`skipcheck-tm1`](..).

To run the same parts by hand, outside tests (to develop against, or to look at in a browser), use
[`pa-setup`](../../docker/pa-setup/README.md). It starts the same images from one file, `pa-environment.yaml`.

## Writing a test

Installing the package adds a pytest plugin, so a test needs only the fixture:

```python
def test_lists_cubes(tm1_server):
    url = tm1_server.rest_url  # http://localhost:54321/api/v1/
    user = tm1_server.username  # admin, with no password
    ...
```

`tm1_server`, like Java's `@RequiresTm1`:

- **Skips** the test when Docker isn't running or no TM1 source is configured, so builds
  without IBM software (such as GitHub CI) stay green. A source that's configured but wrong
  fails instead of skipping.
- **Is** `Tm1ServerContainer.shared()`: one server per test process, started on first use and
  shared by every test, removed when the session ends.

`@pytest.mark.requires_tm1` skips the same way without the fixture. `requires_cognos`,
`requires_tm1_web` and `requires_paw`, with the fixtures `cognos` (the configured
`CognosSource`), `tm1_web` and `paw`, are Java's `@RequiresCognos`, `@RequiresTm1Web` and
`@RequiresPaw`.

For a container of your own:

```python
from skipcheck_tm1 import Tm1Version
from skipcheck_tm1_testcontainers import Tm1Seed, Tm1ServerContainer, Tm1Source

with Tm1ServerContainer(Tm1Source.InstallDir("/opt/ibm/tm1_64")).with_seed(Tm1Seed.SDATA).with_expected_version(Tm1Version.parse("11.8.03500.6")) as tm1:
    ...
```

## Where TM1 comes from

The same settings as Java, in the same order (`Tm1Source.discover()`), as environment variables:

| Setting | Source |
|---|---|
| `TM1_PRIVATE_IMAGE` | `Tm1Source.PrivateImage`: an image with TM1 built in |
| `TM1_HOME_DIR` | `Tm1Source.InstallDir`: an installed `tm1_64` folder, mounted read-only |
| `TM1_INSTALL_VOLUME` | `Tm1Source.InstallVolume`: a Docker volume holding an install |
| `TM1_BUNDLE_DIR` (`TM1_BUNDLE`) | `Tm1Source.Bundle`: a folder holding IBM's bundle (the newest, or the one named) |
| `TM1_BUNDLE_URL` (`TM1_BUNDLE_SHA256`) | `Tm1Source.BundleUrl`: a link to the bundle |

A bundle is unpacked into the `tm1-testcontainers-cache` volume once, shared with the Java and
.NET tests. `TM1_SERVER_IMAGE` replaces the `tm1-server` image (default
`ghcr.io/ajmyers/tm1-server`, tagged `edge` for development versions). Cognos (`COGNOS_*`), TM1
Web (`TM1_WEB_*`) and PAW (`PAW_ZIP`, `PAW_BUNDLE_DIR`, `PAW_ACCEPT_LICENSE`) read Java's
settings too.

## TM1 v12

TM1 v12, the TM1 engine of Planning Analytics 3.x, runs from the
[`tm1-engine`](../../docker/tm1-engine) image, beside the MongoDB it keeps its metadata in. One
engine holds instances, each holding databases, and has no passwords of its own: a test logs in by
user name, through the instance's application.

```python
import httpx2
import pytest

pytestmark = pytest.mark.requires_tm1_engine


def test_lists_cubes(tm1_database):
    url = tm1_database.rest_url  # http://localhost:54321/tm1/api/v1/Databases('testcubes_k3f9')/
    headers = tm1_database.login().authorize({})  # the session cookie of admin
    assert httpx2.get(url + "Cubes?$select=Name", headers=headers).status_code == 200
```

`requires_tm1_engine` and the fixtures are Java's `@RequiresTm1Engine`:

- **Skipped** without Docker or a TM1 v12 source; a source that's configured but wrong fails.
- **`tm1_engine`** is `Tm1EngineContainer.shared()`: one engine per test process, started on first
  use (about 40 seconds) and removed when the session ends. It's never kept for reuse: an engine
  and its datastore belong together.
- **`tm1_database`** is a database of the **test module's own**, created when a test of the module
  first asks for it (about 10 seconds) and dropped when the module is done. Java gives each test
  class a database; the module, a test file, is that unit in pytest. It's named after the module,
  as `testcubes_k3f9` for `test_cubes.py`.
- **`@pytest.mark.requires_tm1_engine(share_database=True)`** gives the tests it marks the engine's
  one shared database, `tm1`, instead, at no cost: give their objects unique names and delete
  them. The marker nearest the test counts: its own, then its class's, then its module's.
- **`min_version="12.6"`** skips the tests on an older engine. Checking it starts the engine.

`db.login("analyst")` is a `Tm1Login` as that user: `cookie()` and `authorize(headers)` log in for
you, `token()` gets the application's bearer token, and `client_id`, `client_secret` and `user` are
for a client that logs in itself. An engine of your own has the databases and users you name:

```python
from skipcheck_tm1_testcontainers import Tm1EngineContainer, Tm1EngineSource

with Tm1EngineContainer(Tm1EngineSource.Bundle("/path/to/ibm-downloads")).with_database("planning").with_user("analyst", "Analysts") as engine:
    planning = engine.database("planning")
    scratch = engine.create_database("scratch")  # and engine.drop_database(scratch)
```

`Tm1EngineSource.discover()` reads these, in this order:

| Setting | Source |
|---|---|
| `TM1_ENGINE_PRIVATE_IMAGE` | `Tm1EngineSource.PrivateImage`: an image with TM1 v12 built in |
| `TM1_ENGINE_HOME_DIR` | `Tm1EngineSource.InstallDir`: an install the image made (`bin/tm1sd.exe`), mounted read-only |
| `TM1_ENGINE_INSTALL_VOLUME` | `Tm1EngineSource.InstallVolume`: a Docker volume holding such an install |
| `TM1_ENGINE_BUNDLE_DIR` (`TM1_ENGINE_BUNDLE`) | `Tm1EngineSource.Bundle`: a folder holding IBM's `tm1-v<version>-standalone-linux.tgz` (the newest, or the one named) |
| `TM1_ENGINE_BUNDLE_URL` (`TM1_ENGINE_BUNDLE_SHA256`) | `Tm1EngineSource.BundleUrl`: a link to the package |
| `TM1_BUNDLE_DIR` | TM1 11's downloads folder, when it holds a TM1 v12 package too: one folder serves both |

A package is installed into the `tm1-engine-testcontainers-cache` volume once. `TM1_ENGINE_IMAGE`
replaces the `tm1-engine` image (default `ghcr.io/ajmyers/tm1-engine`, tagged `edge` for development
versions), e.g. `tm1-engine:local` for one you built.

## More than one server

```python
env = PlanningAnalyticsEnvironment.builder().tm1("planning").tm1("reporting").tm1_web().cognos(lambda c: c.with_user("admin", "adminpw", "Admins")).build()
with env:
    env.tm1("planning").rest_url
```

Several servers share an Admin Server (`Tm1AdminServerContainer`); Cognos puts every server in
CAM security, and `keycloak()` with `cam_security(Namespace.OIDC, ...)` uses OIDC.

`tm1_engine()` adds a [TM1 v12](#tm1-v12) engine, beside the TM1 servers or alone: an environment
needs a server or an engine.

```python
env = PlanningAnalyticsEnvironment.builder().tm1("legacy").tm1_engine(lambda e: e.with_database("planning").with_user("analyst", "Analysts")).build()
with env:
    env.tm1_database("planning").rest_url  # and env.tm1_engine(), the container
```

- The engine and its MongoDB are on the environment's network, and start with TM1 11's parts,
  before PAW. `tm1_engine_source(...)` sets where TM1 v12 comes from, instead of
  `Tm1EngineSource.discover()`.
- The Admin Server, TM1 Web, PAW and Cognos security are TM1 11's: they need a TM1 server.
- An environment with an engine can't be reused: `reuse(True)` is refused at `build()`, whatever
  your reuse switch says. An engine and its datastore belong together, and a kept one keeps every
  database tests left.

### From pa-environment.yaml

`from_config` reads the [`pa-environment.yaml`](../../docker/pa-setup/README.md#pa-environmentyaml)
that `pa-setup` runs, so tests run the environment developers run, from one file:

```python
with PlanningAnalyticsEnvironment.from_config("pa-environment.yaml").build() as env:
    env.tm1("planning").rest_url
```

It returns the builder, configured, so you can still change it before `build()`. The file is
checked as `pa-setup` checks it, with the same defaults and messages.

- **Each part** maps onto the builder: `tm1.servers` (with `seed` and `cfg`), `tm1.adminServer`,
  `tm1Web` (with `heap`), `cognos` (`users`, `oidc` as `keycloak()`, `cam` as `cam_security`,
  `memory`), `paw` (`acceptLicense`, `zip`) and `tm1Engine` (`databases` with their `instance`, and
  `users` with their groups; a user's password is checked and not used, as TM1 v12 keeps none).
  `cognos.oidc: true` turns OIDC on, as a mapping does.
- **Sources:** a part's source set on the builder wins; then `ibm`, the folder of IBM downloads;
  then the settings above (pa-setup's `.env` sets the same `*_URL` names); then the part's
  `images` entry, as a private image with IBM's software built in. With another source, an
  `images` entry is the image the part runs.
- **Ignored:** `name`, every port, `tm1Engine.datastore` and `images.mongo` (the engine's MongoDB
  is its own here), and `files`. Testcontainers gives the environment its own
  network, random host ports and a new database each time.
- **Refused** with a `ValueError` naming the setting: `cognos` without `tm1.servers`, and
  `seed: 24retail`, which the environment can't run yet.

### Reusing a whole environment

Cognos takes minutes to start and PAW longer. A `PlanningAnalyticsEnvironment` keeps its
containers between runs when you ask for it with `reuse(True)` and have turned on Testcontainers
reuse: `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`, or
`TESTCONTAINERS_REUSE_ENABLE=true`. Without the switch, `reuse(True)` has no effect.

```python
env = PlanningAnalyticsEnvironment.builder().tm1("planning").cognos(lambda c: c.with_user("admin", "adminpw", "Admins")).reuse(True).build()
env.start()  # seconds, once a run with this configuration has started it
```

- **Each configuration has its own containers.** The environment's identity is a hash of its
  configuration: every part's image, source, environment variables, command, network aliases,
  labels, exposed ports, copied files and `docker create` arguments (`with_kwargs`, volumes), the
  Cognos users and the CAM settings. Its containers carry it as the label
  `io.skipcheck.tm1.environment`, and its network is named `tm1-environment-<identity>`, so the
  reused containers still reach each other by name. A different configuration gets new
  containers and never picks up another's.
- **`stop()` and `close()` leave everything running**, PAW too, and so does leaving a `with`
  block.
- **What tests leave behind stays:** objects in TM1, sessions, Cognos's content store. Give
  test objects unique names and delete them.
- **Old configurations keep running.** Changing the configuration starts new containers; the
  old ones stay until you remove them, and Cognos alone holds about 8 GB. To start clean:

  ```sh
  docker rm -f $(docker ps -aq --filter label=io.skipcheck.tm1.environment)
  docker network rm $(docker network ls -q --filter label=io.skipcheck.tm1.environment)
  ```

- **PAW is one per Docker daemon** whatever the identity: IBM's compose files give its containers
  fixed names. Each start runs IBM's compose files again, which takes about a minute.
- **With your own `network(...)`,** reuse works only if it's the same network each run: a
  container is found by everything it was created with, its network's name too.

## From the Java module

| Java | Python |
|---|---|
| `tm1.getRestUrl()`, `getUsername()`, `getTm1Version()`… | `tm1.rest_url`, `username`, `tm1_version`… (getters are properties) |
| `withSeed`, `withCamSecurity(cognos, Namespace.OIDC, "admin")`… | `with_seed`, `with_cam_security(cognos, Namespace.OIDC, "admin")`… |
| `CognosContainer.camAuthorization(user, password, namespace)` (static) | `CognosContainer.cam_authorization_for(...)` |
| `Optional<Tm1Source> discover()` | `discover()` returning the source or `None`; it also takes a lookup (`settings.get`) |
| `IllegalArgumentException`, `IllegalStateException` | `ValueError`, `RuntimeError` |
| `Network.newNetwork()` | `Network.new_network()`: created when a container first starts on it |
| `@RequiresTm1` and its `Tm1ServerContainer` parameter | `@pytest.mark.requires_tm1` and the `tm1_server` fixture |
| `@RequiresTm1Engine(shareDatabase = true, minVersion = "12.6")` and its `Tm1EngineContainer` and `Tm1Database` parameters | `@pytest.mark.requires_tm1_engine(share_database=True, min_version="12.6")` and the `tm1_engine` and `tm1_database` fixtures |
| A database per test class | A database per test module (file) |
| `withDatabase(name)`, `withDatabase(instance, name)`; the same for `database` and `createDatabase` | `with_database(name)`, `with_database(instance, name)`, in Java's order |
| `login.authorize(HttpRequest.Builder)` | `login.authorize(headers)`: a dict of headers, or an `httpx2.Request` |
| `equals`, `hashCode`, `toString` | `==`, `hash()`, `repr()` |
| `withReuse(true)` | `with_reuse(True)`, honoured when `testcontainers.reuse.enable=true` (testcontainers-python has no reuse of its own) |

Containers are set up without Docker, as Java's are, so wiring is tested anywhere; they connect
when started. Java also reads each setting as a system property; Python reads environment
variables only.

## Tests

```sh
pytest python/tm1-testcontainers
```

- `test_java_parity.py` reads the Java sources and requires every public class, constant and
  member here, listing the few Java-only ones with the reason.
- The other tests port the Java tests. The configuration ones run without Docker; the ones that
  start containers are skipped without a source, as in Java.

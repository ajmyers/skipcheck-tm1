# skipcheck-tm1-testcontainers (Python)

Run tests against a real IBM Planning Analytics (TM1) server in Docker, with
[testcontainers-python](https://testcontainers-python.readthedocs.io). The Python port of the
Java [`tm1-testcontainers`](../../tm1-testcontainers/README.md) module: the same classes, sources,
settings, images, mounts and messages, named the Python way. Built on the
[`tm1-server`](../../docker/tm1-server) image.

> **No IBM software is included.** You supply your own licensed TM1 Server for Linux, and
> you're responsible for following your IBM license terms. Without it, tests that need TM1
> are skipped.

```sh
pip install -e ./tm1-python -e ./tm1-python/testcontainers
```

Requires Python 3.10+, testcontainers 4.15+ and [`skipcheck-tm1`](..).

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

## More than one server

```python
env = PlanningAnalyticsEnvironment.builder().tm1("planning").tm1("reporting").tm1_web().cognos(lambda c: c.with_user("admin", "adminpw", "Admins")).build()
with env:
    env.tm1("planning").rest_url
```

Several servers share an Admin Server (`Tm1AdminServerContainer`); Cognos puts every server in
CAM security, and `keycloak()` with `cam_security(Namespace.OIDC, ...)` uses OIDC.

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
| `withReuse(true)` | `with_reuse(True)`, honoured when `testcontainers.reuse.enable=true` (testcontainers-python has no reuse of its own) |

Containers are set up without Docker, as Java's are, so wiring is tested anywhere; they connect
when started. Java also reads each setting as a system property; Python reads environment
variables only.

## Tests

```sh
pytest tm1-python/testcontainers
```

- `test_java_parity.py` reads the Java sources and requires every public class, constant and
  member here, listing the few Java-only ones with the reason.
- The other tests port the Java tests. The configuration ones run without Docker; the ones that
  start containers are skipped without a source, as in Java.

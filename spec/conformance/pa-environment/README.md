# pa-environment.yaml fixtures

Files for the tests of `PlanningAnalyticsEnvironment.fromConfig`, which every client's
testcontainers library runs under the same names, reading these same files. The format is
pa-setup's: [`docker/pa-setup`](../../../docker/pa-setup/README.md).

| File | What the tests check |
|---|---|
| `settings.yaml` | Every setting the environment maps, with values other than the defaults |
| `paw.yaml` | PAW, its license, and its zip in the `ibm` folder |
| `paw-cognos.yaml` | PAW with Cognos: PAW logs users on through Cognos |
| `tm1-engine.yaml` | Every `tm1Engine` setting (TM1 v12), with values other than the defaults, beside a TM1 11 server |
| `engine-image-only.yaml` | `images.tm1Engine` without a `tm1Engine` section: the image of an engine added in code |
| `engine-oauth2.yaml` | `tm1Engine.oauth2`: OAuth 2.0 logins, and the users with a password |
| `engine-oauth2-off.yaml` | `oauth2: false` keeps a user's password readable with OAuth 2.0 off |
| `oidc-true.yaml` | `cognos.oidc: true` turns OIDC on, as a mapping does |
| `ibm/` | The `ibm` folder of those: empty stand-ins for the PAW Local zip and IBM's TM1 v12 package |
| `invalid/*.yaml` | Files `fromConfig` refuses. The first line is `# error: <part of the message>` |

`docker/pa-setup/examples/everything.yaml` is read too. `scripts/tests/test_pa_setup.py` checks
that `pa_setup.py` accepts the valid files and refuses the invalid ones with the same message,
except those marked `# pa-setup: accepts`: what pa-setup runs and the environment can't.

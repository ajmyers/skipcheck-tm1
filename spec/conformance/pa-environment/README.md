# pa-environment.yaml fixtures

Files for the tests of `PlanningAnalyticsEnvironment.fromConfig`, which every client's
testcontainers library runs under the same names, reading these same files. The format is
pa-setup's: [`docker/pa-setup`](../../../docker/pa-setup/README.md).

| File | What the tests check |
|---|---|
| `settings.yaml` | Every setting the environment maps, with values other than the defaults |
| `paw.yaml` | PAW, its license, and its zip in the `ibm` folder |
| `paw-cognos.yaml` | PAW with Cognos: PAW logs users on through Cognos |
| `ibm/` | The `ibm` folder of both: an empty stand-in for the PAW Local zip |
| `invalid/*.yaml` | Files `fromConfig` refuses. The first line is `# error: <part of the message>` |

`docker/pa-setup/examples/everything.yaml` is read too. `scripts/tests/test_pa_setup.py` checks
that `pa_setup.py` accepts the valid files and refuses the invalid ones with the same message,
except those marked `# pa-setup: accepts`: what pa-setup runs and the environment can't.

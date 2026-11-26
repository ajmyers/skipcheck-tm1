# pa-environment.yaml fixtures for TM1 v12

Files for the tests of `pa-environment.yaml`'s `tm1Engine` section, which `pa_setup.py` and every
language's `PlanningAnalyticsEnvironment.fromConfig` read under the same rules. They work as the
files in [`spec/conformance/pa-environment`](../../spec/conformance/pa-environment/README.md) do,
and are tested by the same tests.

| File | What the tests check |
|---|---|
| `tm1-engine.yaml` | Every `tm1Engine` setting, with values other than the defaults, beside a TM1 11 server |
| `oidc-true.yaml` | `cognos.oidc: true` turns OIDC on, as a mapping does |
| `ibm/` | The `ibm` folder of both: an empty stand-in for IBM's TM1 v12 package |
| `invalid/*.yaml` | Files that are refused. The first line is `# error: <part of the message>` |

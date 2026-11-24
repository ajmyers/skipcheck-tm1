# skipcheck-tm1 (Python)

A Python client for the **IBM Planning Analytics (TM1) REST API**, with the same API as the Java client (`tm1-client`): the same services, methods, parameters, queries, patches, session views and errors, named the Python way. Its model is generated from the same language-neutral spec ([`spec/tm1-api.json`](../spec)).

It comes in two flavors with one API: sync (`skipcheck_tm1`) and asyncio (`skipcheck_tm1.aio`). Requires Python 3.11+, [httpx2](https://github.com/pydantic/httpx2) and [pydantic](https://docs.pydantic.dev/) 2.

```python
from skipcheck_tm1 import Filter, ODataPath, ODataQuery, Tm1Client
from skipcheck_tm1.model import Cube, CubeCreate, CubePatch

with Tm1Client.builder().base_url("http://localhost:8010/api/v1/").basic_auth("admin", "apple").build() as tm1:
    for cube in tm1.cubes.model_cubes(ODataQuery.none().select(Cube.NAME).filter(Filter.starts_with(Cube.NAME, "Sales"))):
        print(cube.name)

    dimensions = ODataPath.of("Dimensions")
    tm1.cubes.create(CubeCreate.builder().name("Plan").bind_dimensions(dimensions.key("Region"), dimensions.key("Year")).build())

    rules = "SKIPCHECK;\n['Margin'] = N: ['Revenue'] - ['Cost'];"
    if not tm1.cubes.check_rules("Plan", rules):
        tm1.cubes.update("Plan", CubePatch.builder().rules(rules).build())

    # A long process, run asynchronously by TM1 and polled; interrupting the wait stops it on TM1
    tm1.with_async(2).processes.execute_with_return("Rebuild Forecast")
```

The same with asyncio:

```python
from skipcheck_tm1.aio import AsyncTm1Client

async with AsyncTm1Client.builder().base_url("http://localhost:8010/api/v1/").basic_auth("admin", "apple").build() as tm1:
    cubes = await tm1.cubes.model_cubes(ODataQuery.none().select(Cube.NAME))
```

## From the Java client

| Java | Python |
|---|---|
| `tm1.cubes().modelCubes(query)` | `tm1.cubes.model_cubes(query)`; services are properties, methods snake case |
| `Mono<T>` | `T` (asyncio: a coroutine of `T`) |
| `Flux<T>` | `list[T]`; log tails and cellset streams are iterators |
| `ODataQuery query` parameter | `query: ODataQuery | None = None`, and keyword shortcuts (`select=…`, `top=…`) |
| Overloads (`update(name, patch)`, `update(name, patch, etag)`) | Optional parameters (`update(name, patch, etag=None)`) |
| `CubeCreate.builder().name("x").build()` | the same, in snake case; a model instance (`Cube(name="x")`) works too |
| `Cube.NAME`, `Filter.and(…)` | `Cube.NAME`, `Filter.and_(…)` (`and`, `or`, `not` are keywords) |
| `Tm1Client.builder()…build()`, `withSandbox`, `withAsync`, `batch()` | `Tm1Client.builder()…build()`, `with_sandbox`, `with_async`, `batch()` |
| `Tm1SessionPool`, `Tm1Identity`, `Tm1Timeouts`, `Tm1ErrorClassifier` | the same names |
| `Tm1NotFoundException` | `Tm1NotFoundError` |
| Micrometer observations | the `skipcheck_tm1` logger |

`tm1.api` adds what Java doesn't have: a generated member for every endpoint (`tm1.api.cubes["Sales"].views.list()`). It honours `$metadata`'s containment: through a reference, such as `users["u"].groups["g"]`, it reads only, since on TM1 a delete through it deletes the group itself.

## How it's made

| Part | Where | How |
|---|---|---|
| Model: an enum or pydantic model per CSDL type, patch builders, `Tm1Operations` | `skipcheck_tm1.model` | Generated from the spec at build time |
| Generated API (`tm1.api`) | `skipcheck_tm1._generated.api`, `aio_api` | Generated from the spec at build time |
| Services, session, pool, client | `skipcheck_tm1/aio/` | Hand-written, asyncio: the source of truth |
| The same, sync | `skipcheck_tm1/_sync/` | Generated from `aio/` by `tm1_pygen.unasync` at build time |
| Queries, requests, errors, versions, value types | `skipcheck_tm1/*.py` | Hand-written, shared by both flavors |

Nothing generated is committed: the build (`hatch_build.py`) runs [`codegen/tm1_pygen`](codegen/tm1_pygen), which needs only the standard library. To regenerate by hand:

```sh
PYTHONPATH=codegen python -m tm1_pygen ../spec/tm1-api.json src/skipcheck_tm1
```

## Tests

```sh
pip install -e '.[test]' -e './testcontainers[test]'
pytest
```

- `test_format.py` runs Ruff ([`ruff.toml`](../ruff.toml)): layout, lint, and a docstring on every public module, class, method and function of the two packages, as the Java build requires Javadoc.
- `test_types.py` runs Pyright ([`pyrightconfig.json`](../pyrightconfig.json)) over both packages' sources, the generated modules included, as the Java build runs NullAway and Error Prone.
- `test_architecture.py` keeps the rules of Java's `ArchitectureTest`: the asyncio flavor never blocks or starts work nobody awaits, request URLs come from `ODataPath` and `ODataQuery`, logging goes through `logging` only, `odata.py` knows nothing of TM1, and services are stateless, async and declare their endpoints.
- `test_java_examples_parity.py` keeps the guide's examples ([`examples/`](examples), and tm1-testcontainers' [`examples/`](../tm1-testcontainers/examples)) the Java ones: a file for each Java examples class, with the same regions. They aren't run; `test_types.py` type-checks them and `test_format.py` lints them.
- `test_java_parity.py` and `runtime/test_java_parity_runtime.py` read the Java sources and require every public class and method, with the same `@Tm1Endpoint` IDs and parameters.
- `test_coverage_gate.py` holds each flavor to the spec's 242 endpoints, by the Java gate's rules.
- `test_contract.py` calls every endpoint through the generated API, in both flavors, against a fake TM1, and checks the request.
- `services/` and `runtime/` port the Java client's unit tests, in both flavors.
- `test_model.py` round-trips the payloads the Java client captured from a real TM1; `conformance/` shares OData encoding cases with Java and .NET.
- `live/` ports the Java live tests (`*Tm1Test` and `*BehaviorTest`), one file per Java class and each test under its Java name: `CubeServiceTm1Test` is `live/test_cube_service.py`, `Tm1SessionBehaviorTest` is `live/test_tm1_session_behavior.py`, and `reordersTheStorageOrder` is `test_reorders_the_storage_order`. `test_java_live_parity.py` reads the Java sources and fails unless each class and test is there, or [`coverage/live-excluded.txt`](coverage/live-excluded.txt) says why not; what only this client tests is in `live/test_python_only.py`. The CAM tests start Cognos (and Keycloak) themselves and skip without a Cognos source. Like the Java ones, they start TM1 in Docker themselves, with [`tm1-testcontainers`](testcontainers) (the Python port of Java's), from the same settings the Java tests read: `TM1_PRIVATE_IMAGE`, `TM1_HOME_DIR`, `TM1_INSTALL_VOLUME`, `TM1_BUNDLE_DIR` or `TM1_BUNDLE_URL`. `TM1_URL` (with `TM1_USER`, `TM1_PASSWORD`, `TM1_NAMESPACE`, `TM1_VERIFY=false`) uses a server that's already running instead. Without either they're skipped. `./gradlew liveTest --continue` (or [`scripts/live-tests.sh`](../../scripts/live-tests.sh)) runs every client's live tests together.

The live tests pass against TM1 11.8.03500.6 (Planning Analytics 2.1.19), started in Docker by tm1-testcontainers from an installed `tm1_64` folder (`TM1_HOME_DIR`): 203 tests, with relational drillthrough skipped for want of an ODBC source. The five CAM tests need a Cognos source and pass on the Cognos test container (12.1.1). tm1-testcontainers' own tests pass for the TM1 server; the TM1 Web and Planning Analytics Workspace ones haven't run, for want of those products. They also pass against a server that is already running, named with `TM1_URL`.

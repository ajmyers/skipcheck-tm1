# tm1-client (Python), a prototype

A Python client for the **IBM Planning Analytics (TM1) REST API**. It's generated from the same language-neutral spec as the Java client ([`spec/tm1-api.json`](../spec)), so it covers every endpoint the spec lists: 242 of 242 for schema 11.8.35.

Requires Python 3.10+, [httpx](https://www.python-httpx.org/) and [pydantic](https://docs.pydantic.dev/) 2.

```python
from tm1_client import Bind, Tm1Client
from tm1_client.model import Cube

with Tm1Client.connect("https://tm1:8010/api/v1", user="admin", password="apple") as tm1:
    print(tm1.product_version())

    for cube in tm1.cubes.list(select=["Name"], filter="startswith(Name,'Sales')"):
        print(cube.name)

    tm1.cubes.create(Cube(name="Plan", dimensions=[Bind("Dimensions('Region')"), Bind("Dimensions('Year')")]))
    sales = tm1.cubes["Sales"]
    sales.update(Cube(rules="SKIPCHECK;"), if_match=sales.get().etag)
    view = sales.views["Default"].get()            # a NativeView or an MDXView, by @odata.type
    sales.reserve(release_existing=True)           # POST Cubes('Sales')/tm1.Reserve
    print(tm1.mdx_values("SELECT {[Year].[2026]} ON 0 FROM [Sales]"))
```

For CAM security, pass `namespace=`, or build a `Tm1Session` with `auth.cam_passport(...)`. `Tm1Session(..., sandbox="What if")` runs every request in a sandbox.

## How it's made

| Part | Where | How |
|---|---|---|
| Model: an enum or pydantic model per CSDL type | `tm1_client._generated.model` (also `tm1_client.model`) | Generated |
| API: a resource class per entity type, and the root `Tm1Api`, with a member per endpoint | `tm1_client._generated.api` | Generated |
| Runtime: session, auth, errors, OData encoding, resource base classes | `tm1_client/*.py` | Hand-written |
| Conveniences (`Tm1Client`) | `tm1_client/client.py` | Hand-written, on top of the generated API |

The generator ([`codegen/tm1_pygen`](codegen/tm1_pygen)) needs only the standard library. The build runs it (`hatch_build.py`) into `src/tm1_client/_generated/`, which is never committed, the same way Gradle generates the Java model. To regenerate by hand:

```sh
PYTHONPATH=codegen python -m tm1_pygen ../spec/tm1-api.json src/tm1_client
```

### Rules the generated code follows

- **Reads are lenient.** Every field is optional, since `$select` can leave any out. Unknown properties are ignored, an enum value a newer server added reads as `UNKNOWN`, and a subtype is chosen by `@odata.type`. Departures listed in the overlay (`CAMSettings.ClientPingCAMPassport`, `MessageLogEntry.TimeStamp`) read leniently.
- **Writes send only what was set**, an explicit `None` included, so one model class serves for reading and for `PATCH`/`POST`. Don't send a model read from TM1 back as a write: it carries server-computed fields.
- **References are read-only.** A navigation that refers to entities kept elsewhere (`Cube/Dimensions`, `User/Groups`) only reads, so `tm1.users["u"].groups["g"].delete()` raises instead of deleting the group, which is what TM1 would do. Change those entities through their own collection.

## Tests

```sh
pip install -e '.[test]'
pytest
```

- `test_coverage_gate.py`: every endpoint of the spec is implemented, excluded or pending, by the same rules as the Java client's gate.
- `test_contract.py`: works out a route to every endpoint from the spec alone, calls it through the generated API against a fake TM1, and checks the method, path, parameters and body.
- `test_model.py`: reads and writes back the payloads the Java client captured from a real TM1, unchanged.
- `test_conformance.py`: the OData encoding cases the Java client also passes (`spec/conformance`).
- `test_session.py`: login and re-login, cookies, paging, sandboxes, `If-Match`, error classification, and refusing what TM1 would get wrong.

## Not yet

- Async (`httpx.AsyncClient`): the emitter can produce an async API from the same spec.
- Hand-written services with TM1's quirks (cellset paging, async operations with `Prefer: respond-async`, `$batch`, change sets, impersonation), as the Java client has.
- Tests against a real TM1 (the repository's Docker image and Testcontainers).
- Version gating: the spec carries `since` and `deprecated` for everything, but the client doesn't check the server has an operation before calling it.

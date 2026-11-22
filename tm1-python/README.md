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

For CAM security, pass `namespace=`, or build a `Tm1Session` with `auth.cam_passport(...)`.

### asyncio

The same API, generated from the same spec, with `await` on everything that sends a request:

```python
from tm1_client.aio import AsyncTm1Client

async with AsyncTm1Client.connect("https://tm1:8010/api/v1", user="admin", password="apple") as tm1:
    cubes = await tm1.cubes.list(select="Name")
    async for element in tm1.dimensions["Year"].hierarchies["Year"].elements.iter():
        ...
```

### Sandboxes, change sets, long operations and batches

Each is a *view* of the client, sharing its login, as in the Java client:

```python
whatif = tm1.with_sandbox("What if")                 # every request gets !sandbox
with tm1.change_set() as recorded:                   # writes get !ChangeSet; ended either way
    recorded.cubes["Sales"].update_cells(updates=[CubeUpdate(tuple=[odata.element("Year", "2026"), odata.element("Region", "North")], value=42)])
    change_set = recorded.session.change_set
tm1.undo_change_set(change_set_id=change_set)

# Prefer: respond-async: TM1 answers at once with a monitor, polled every 2 s until done.
# Interrupting the wait deletes the monitor, which stops the process on TM1.
result = tm1.with_async(2).processes["Load"].execute_with_return()

# JSON $batch: calls through batch.client are queued, and return Deferred results
with tm1.batch() as batch:
    a = batch.client.cubes["A"].get()
    b = batch.client.cubes["B"].get()
print(a.result().name, b.result().name)              # each has its own value or error
```

### Capability checks

Before calling an operation, the session checks the server's own `$metadata` (read once) has it, and refuses one it doesn't, saying which schema added it: `Action Cube/Reserve isn't in this server's $metadata (schema 11.8.20); TM1 added it in schema 11.8.34`. Reading entities isn't checked. Pass `check_capabilities=False` to skip it.

## How it's made

| Part | Where | How |
|---|---|---|
| Model: an enum or pydantic model per CSDL type | `tm1_client._generated.model` (also `tm1_client.model`) | Generated |
| API: a resource class per entity type, and the root `Tm1Api`, with a member per endpoint | `tm1_client._generated.api`, and `aio_api` for asyncio | Generated |
| Runtime: requests as values (`_protocol`, `_calls`), sessions and their views, auth, errors, OData encoding, resource base classes | `tm1_client/*.py`, `tm1_client/aio/` | Hand-written |
| Conveniences (`Tm1Client`) | `tm1_client/client.py` | Hand-written, on top of the generated API |

The generator ([`codegen/tm1_pygen`](codegen/tm1_pygen)) needs only the standard library. The build runs it (`hatch_build.py`) into `src/tm1_client/_generated/`, which is never committed, the same way Gradle generates the Java model. To regenerate by hand:

```sh
PYTHONPATH=codegen python -m tm1_pygen ../spec/tm1-api.json src/tm1_client
```

### Rules the generated code follows

- **Reads are lenient.** Every field is optional, since `$select` can leave any out. Unknown properties are ignored, an enum value a newer server added reads as `UNKNOWN`, and a subtype is chosen by `@odata.type`. Departures listed in the overlay (`CAMSettings.ClientPingCAMPassport`, `MessageLogEntry.TimeStamp`) read leniently.
- **Writes send only what was set**, an explicit `None` included, so one model class serves for reading and for `PATCH`/`POST`. Don't send a model read from TM1 back as a write: it carries server-computed fields.
- **Names follow TM1's**, in snake case. Where an operation's name is taken by a generic method it gets its kind: the bound action `Cube/Update` is `update_action`, since `update` is the generic PATCH.
- **References are read-only.** A navigation that refers to entities kept elsewhere (`Cube/Dimensions`, `User/Groups`) only reads, so `tm1.users["u"].groups["g"].delete()` raises instead of deleting the group, which is what TM1 would do. Change those entities through their own collection.

## Tests

```sh
pip install -e '.[test]'
pytest
```

- `test_coverage_gate.py`: every endpoint of the spec is implemented, excluded or pending, by the same rules as the Java client's gate, for the sync and the asyncio API separately.
- `test_contract.py`: works out a route to every endpoint from the spec alone, calls it through the generated API (both flavors) against a fake TM1, and checks the method, path, parameters and body.
- `test_views.py`: sandboxes, change sets, async operations (the monitor, gzip, deleting it when interrupted), batches, and capability checks; also that the `$metadata` reader makes exactly the spec's inventory.
- `test_generator.py`: the generator is deterministic, and the asyncio API matches the sync one member for member.
- `test_model.py`: reads and writes back the payloads the Java client captured from a real TM1, unchanged.
- `test_conformance.py`: the OData encoding cases the Java client also passes (`spec/conformance`).
- `test_session.py`: login and re-login, cookies, paging, sandboxes, `If-Match`, error classification, and refusing what TM1 would get wrong.

- `tests/live/`: against a real TM1, skipped unless `TM1_URL` is set (with `TM1_USER`, `TM1_PASSWORD`, `TM1_NAMESPACE`, `TM1_VERIFY=false` for a self-signed certificate). Start one with [`docker/tm1-server`](../docker/tm1-server). They check the server's `$metadata` against the spec, read every entity set and singleton, and round-trip a cube, a cell, a batch and a sandbox.

## Not yet

- The live tests haven't run against a real server yet; nothing else here has either, beyond the captured payloads.
- More hand-written services for TM1's quirks, as the Java client has: cellset paging by rows, impersonation, session pools, process debugging.
- Deprecation warnings: the spec carries `deprecated` for everything, but calling a deprecated endpoint doesn't warn.

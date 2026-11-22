# The API spec: one source for every client

Every client in this repository (Java, Python, and any added later) is generated from the same files, and has its coverage counted against the same list of endpoints.

```
tm1-client/src/model/metadata/tm1-schema-<v>.xml   TM1's $metadata, as captured (the source of truth)
spec/tm1-overlay.json                              what TM1 does differently from its $metadata (hand-written)
        │  tm1-codegen  (./gradlew :tm1-codegen:exportSpec)
        ▼
spec/tm1-api.json                                  the language-neutral spec (generated, committed)
        ├── Java:   tm1-codegen's ModelGenerator  → tm1-client's model (at build time)
        └── Python: tm1-python/codegen (tm1_pygen) → tm1_client._generated (at build time)
spec/conformance/*.json                            cases every client's tests must pass
```

## Files

| File | What it is | Edited by |
|---|---|---|
| `tm1-overlay.json` | TM1's departures from `$metadata` (plan §16.5): properties sent in another type (`departures`), dates TM1 sometimes garbles (`lenientDates`), and notes about endpoints, with generic requests a client mustn't offer (`forbid`). Notes are Markdown. | Hand |
| `tm1-api.json` | The snapshot as plain JSON, with the overlay merged in: `enums`, `types`, `operations`, `container`, and `endpoints`, the coverage inventory. Sorted and versioned (`specVersion`), so a TM1 upgrade shows up as a readable diff. | `./gradlew :tm1-codegen:exportSpec` |
| `conformance/odata-encoding.json` | Paths and literals every client must build identically. | Hand |

`tm1-api.json` is committed so clients in other languages build without a JVM. The `build` job fails when it's out of date (`ApiSpecTest`).

## Endpoints and coverage

`endpoints` lists every endpoint as an ID, such as `EntitySet Cubes`, `Singleton Server`, `ActionImport ExecuteMDX`, `Action Cube/Reserve` or `Navigation Cube/Views`. It's the inventory the Java client's `CoverageGateTest` counts (242 for schema 11.8.35), and `ApiSpecInventoryTest` checks the two agree.

Every client has a coverage gate over this list. An endpoint is implemented, excluded with a reason, or pending, and the pending list only shrinks:

| Client | Gate | Lists | Implemented |
|---|---|---|---|
| Java | `tm1-client` `CoverageGateTest` | `tm1-client/src/test/resources/metadata/coverage-*.txt` | 240 (2 excluded as duplicates) |
| Python | `tm1-python/tests/test_coverage_gate.py` | `tm1-python/coverage/*.txt` | 242 |

Python also runs `test_contract.py`, which works out a route from the root to every endpoint from the spec alone, calls it through the generated API against a fake server, and checks the request it sends.

## Adding a language

1. Write a generator that reads `tm1-api.json`. `tm1-python/codegen/tm1_pygen/spec.py` has the lookups any generator needs and no Python-specific code, so it ports easily. Refuse a `specVersion` you don't know.
2. Generate the model: an enum per `enums` entry (with a fallback member for values a newer server adds), and a type per `types` entry. Every property is optional, a hierarchy reads by `@odata.type`, open types keep undeclared properties, writes send only what was set, and properties with a `departure` or `lenientDate` read leniently.
3. Generate a member per endpoint, and honour `forbid`. Through a navigation that doesn't contain its targets (`containsTarget: false`), offer reads only: on TM1, deleting through one deletes the target itself.
4. Hand-write the runtime: session (login, `TM1SessionId` cookie, re-login on 401, `ActiveSession/tm1.Close`), errors (the Java `Tm1ErrorClassifier` rules), and OData encoding.
5. Add the gate and `conformance/` tests to CI.

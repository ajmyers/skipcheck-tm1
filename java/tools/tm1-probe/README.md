# tm1-probe

Asks a running TM1 what its `$metadata` doesn't say, and writes the answers to a file beside
the `$metadata` snapshot, one file per schema version:

```
java/tm1-client/src/model/metadata/tm1-schema-<v>.xml   TM1's $metadata, as captured
java/tm1-client/src/model/metadata/tm1-probe-<v>.json   what the probe found (this tool)
```

A build tool: not published, and nothing at run time depends on it. It's still growing ([#312]).
It finds **undeclared properties**, and **what each type does with each property in a POST and a
PATCH**. `tm1-codegen` adds the undeclared properties to every client's model from it, each tagged as a departure from `$metadata` (`@Tm1Departure` in Java). The writes give each probed type a create type (`ProcessCreate`) with only what a POST applies, and every write type a `builder(read)` copying what TM1 applies from a read entity (#311).

[#312]: https://github.com/ajmyers/skipcheck-tm1/issues/312

## Run it

```sh
TM1_HOME_DIR=~/ibm/tm1_64 ./gradlew probeTm1
```

Any TM1 source the TM1 tests take works (`TM1_PRIVATE_IMAGE`, `TM1_HOME_DIR`,
`TM1_INSTALL_VOLUME`, `TM1_BUNDLE_DIR`, `TM1_BUNDLE_URL`: see [tm1-testcontainers]), but not
`TM1_URL`. The probe reads the server's binary out of the container it starts, so it needs a
container.

| Setting | Default | What it does |
|---|---|---|
| `TM1_PROBE_SERVERS` | `4` | How many identical TM1 containers share the work. One TM1 (11.8) answers about 35,000 names a second, however many requests it's sent at once, so more servers are the way to go faster. Each uses about 2.5 cores. |
| `TM1_PROBE_ONLY` | unset | `writes` probes the writes alone, on one server, with the committed `tm1-probe-<schema>.json`'s undeclared properties, and keeps that file's other findings. For working on `write-cases.json`: the names take far longer and don't change with the cases. A new TM1 version needs a full run first. |

It takes about 24 minutes with one server and about a quarter of that with four, plus a minute or
two to start the containers. Then review and commit the file it wrote.

## When to run it

- **For a new TM1 version**, with the `$metadata` snapshot (`MetadataSnapshotUpdateTest`). A new
  schema version writes a new file; diff it against the last version's file to see what changed.
- **When the probe changes.** Run it on the same version: the file shouldn't change unless the
  probe now finds more. A change in the probe's output format bumps `probeVersion`.

## Versions

One file per schema version, as the `$metadata` snapshots are. For a new TM1 version:

1. Capture its `$metadata` (`MetadataSnapshotUpdateTest`) and run `./gradlew probeTm1` against it:
   it writes `tm1-probe-<new schema>.json`.
2. Diff that against the last version's file: undeclared properties added or gone, write outcomes and
   collection behaviours that changed.
3. Commit both. Today the generators read the file for the snapshot's own version only; merging the
   versions' files into the spec, and checking a server's version at run time where behaviour
   differs, is planned ([#318](https://github.com/ajmyers/skipcheck-tm1/issues/318), plan §9). So is
   a live drift test that compares a server with the committed file, as `MetadataDriftTest` does for
   `$metadata`.

## What it does

1. **Candidates.** Every identifier-like name in the server's binary (`bin64/tm1s.exe`, copied out
   of the container): each run of ASCII letters and digits, as single bytes and as UTF-16LE, split
   into camel-case words, keeping every span of words that starts upper case and is 2 to 48
   characters long. The same binary gives the same names on any platform. About 813,000 for
   11.8.035. Add the names earlier probe files found, and every name `$metadata` declares.
2. **Fixtures.** On each server, objects named `SkipcheckProbe…`, so every type has an entity to
   walk through and read: a dimension with a consolidation, attributes, an alias, an alternate
   hierarchy and subsets; a cube with rules and both kinds of view; processes with an ASCII and a
   view data source; a chore with a task; a group, a user, attributes, a sandbox, an annotation, a
   cellset. They're deleted afterwards.
3. **Paths.** From the entity sets and singletons, breadth first through the navigation properties
   `$metadata` declares, in name order, a path to each entity type. Through a collection it picks a
   fixture first, then any object but a control object.
4. **The check.** TM1 refuses a `$select` naming a property its type doesn't have (400, code 278,
   `"X" is not a valid property of type "ibm.tm1.api.v1.Process"`), and accepts one it has,
   declared or not. So every candidate goes in a `$select` of its own at each type's path, a
   thousand to a `$batch`. What TM1 accepts that the type doesn't declare is undeclared. A path
   counts only if TM1 accepts a declared name there and refuses one no type has.
5. **Values.** Each undeclared property is read from an entity, for the kind of JSON value it
   holds.
6. **Writes**, on the first server, as [`write-cases.json`](src/main/resources/io/skipcheck/tm1/probe/write-cases.json)
   describes them: per case, where to create the type, the least a create needs (`base`), a
   fixture to read every property from (declared and undeclared, selected by name), and values for
   properties none can be made up for. For each case:
   - `postRead`: the fixture's read body POSTed back as a create;
   - `minimal` and `required`: the base alone, then without each of its properties;
   - `post` and `patch`: per property, the base plus that property in a create, and that
     property in a PATCH of a fresh object, each read back;
   - `patchNull`: per property, a PATCH setting it to `null`;
   - `rename`: a PATCH of `Name`.

   The value sent is the case's, else the fixture's where it differs from the default, else one
   made up from the property's type (another enum member, a flipped boolean, a number plus one).
   Collection cases create an object with some members, PATCH the collection, and read it back.

With several servers, each one creates the same fixtures and finds its own paths, then takes
every Nth type in name order. Identical servers give identical answers, so the file doesn't
depend on how many there were.

### Nested values

A structured value a type carries (a complex property, `Parameters` or `DataSource`, or a nested navigation in the case's base, an annotation's `ApplicationContext`) is probed field by field: from the base's value (else the fixture's, else the case's `values`), one field of the first member is changed, sent in a create and a PATCH, read back and compared, and recorded by path (`Parameters/Prompt`). A field that's a navigation is bound and nested, and read back expanded. A property whose value carries navigations (a view's axes) is compared by what they point at, as it reads back without them otherwise.

### Coverage

Every setter the clients' create and patch types offer must be verified by the probe or listed in [`spec/conformance/probe-coverage.json`](../../../spec/conformance/probe-coverage.json), with why (`ProbeCoverageTest`). For a new TM1 version, a failure there names each writable property or type the cases don't reach yet: add a case, or list it with a reason.

### Write cases

A case in [`write-cases.json`](src/main/resources/io/skipcheck/tm1/probe/write-cases.json) names its `type`, where it's made (`collection`), a fixture to read a full set of properties from (`source`, an entity or a list whose first member is read), the least a create needs (`base`), and `values` for properties no value can be made up for. The rest is for types that aren't made and found by a name:

| Field | For |
|---|---|
| `named` | `false` when the body carries no `Name` (an edge, a task, an annotation) |
| `key` | key properties, which aren't probed (`["ParentName", "ComponentName"]`) |
| `address` | where a try's object is found, with `{name}` (an edge: `Edges(ParentName='…',ComponentName='{name}')`); else `collection('name')` |
| `assigned` | the key TM1 assigns, read from the create's answer: a task's `Step`, an annotation's or a link's `ID` (a link's is its name and `.extr`) |
| `setup`, `teardown` | requests before each create and after each delete, with `{name}` (an edge's component element) |
| `names` | the names to use in turn, where TM1 takes only some (locales, for translations) |
| `targets` | the case's own binding targets, over the cases' `targets`. A target is a path, or `{"from", "key", "path"}` for a key TM1 assigns, found at run time (a session's `ID`; with no `key`, the first member's `@odata.id`, a level's) |
| `secrets` | properties TM1 never reads back, checked by signing in |
| `singleton`, `skip` | one object, patched setting by setting (nested ones by path, `Administration/Language`), read back and set back; `skip` leaves paths alone, such as what the probe's own connection depends on |
| (cases') `nest` | per entity type, how a nested entity of it is made: a case's id, or a body with `{name}` for a type without a case, or one not found by a name (a member, a task, an edge, a translation) |
| `values` by path | a nested value's field (`"DataSource/asciiDelimiterChar": ";"`), a target for a navigation in it (`"Rows/Subset@odata.bind"`), a nested entity for one (`"Titles/Subset"`), or `{"$member": {…}}` for a field that only changes with others, which replaces the whole member (a data source's `Type`) |
| `numbered` | a key the caller picks as a number (a breakpoint's `ID`): a fresh one each try, and the object found by it |
| `prepare`, `finish` | steps run once before the case and once after, whatever happens: what its objects live in, such as a debug session. A step can `capture` a field of its answer (`{"field": "ID", "as": "context"}`, used as `{context}`), and a finishing step can repeat `until` a field has a value (`Status` is `Complete`) |
| `freeSource` | the source is the case's own (made by `prepare`): it's deleted before the read is posted back, where TM1 takes one object in a place (a breakpoint per line, variable or lock) |
| `selectType` | the type whose properties are selected, where the one posted reads as another (a document reads as a `DocumentReference`); a property outside it is recorded `unread` |
| `notRequired` | what only the case's own fixture needs, left out of what a create requires (a native view's titles, on a cube with a third dimension) |
| `generate` | `false` while the generators don't use the case yet ([#338](https://github.com/ajmyers/skipcheck-tm1/issues/338)): its writes are recorded, and `tm1-codegen` skips them |

## The file

| Key | What it holds |
|---|---|
| `server` | `productVersion` and `schemaVersion` |
| `binary` | the binary's SHA-256, and how many candidates were tried |
| `undeclared` | per entity type, the properties TM1 accepts that `$metadata` doesn't declare, each with the kind of value read (`string`, `number`, `boolean`, `array`, `object`, `null`, `navigation`, or `unread`). `null` says only that the fixture's value was null. |
| `declaredRefused` | per entity type, declared properties TM1 refused: `$metadata` declares more than the server has |
| `unverifiable` | types TM1 doesn't check names for, such as `View` (any type with subtypes, in any form, casts included) and `Annotation`: undeclared properties there are invisible to this probe |
| `writes` | per case: `postRead`, `minimal`, `required`, and per property `post`, `patch` and `patchNull`, then `rename`. An outcome is `applied` (read back as sent), `ignored` (2xx, read back as the default), `transformed` (2xx, read back as neither: `UniqueName` follows the name, line ends are normalised), `rejected` (with `status` and TM1's error `code`), `accepted` (2xx, nothing to read back), or `untested` (no value to send). A case's `secrets` (`"Password": ["one", "two"]`) are properties TM1 never reads back: a create with the first and a PATCH to the second are `applied`, `verifiedBy` `signIn`, when the user then signs in with it and not with the other. A `transformed` write is logged with what was sent and what was read back, and a refused one with what was sent and TM1's message, for a person to judge: whether TM1 refused the write or the probe sent a value it shouldn't have. The file keeps outcomes, statuses and codes only |
| `writes` → `navigations` | per case, every navigation of its type in a create (`post`) and a PATCH (`patch`), each `bind` (to the fixture `write-cases.json`'s `targets` names for the navigation's type, added to what the case's base binds) and `nested` (an entity made from its type's case, under a fresh name, or the case `nest` names; deleted after). Read back with `GET …/Navigation`: `applied`, `appends`, `replaces`, `ignored`, `transformed`, `mixed`, `rejected`, `unread` (TM1 took it but won't list the navigation), or `untested` (no target or case to send). |
| `collections` | per collection case, what a PATCH of it does: `replaces`, `appends`, `reorders`, `ignored`, `rejected` or `mixed` |
| `unreached` | types the walk found no path to. It follows declared navigation types, so a subtype (`NativeView`, `ProcessReference`) is reached only as its base type and is listed here. So are types nothing on the server holds (`GitPlan` without Git). |

Everything is sorted and nothing depends on when or where it ran: probing the same server twice
writes the same file. The writes keep outcomes, statuses and error codes only, never TM1's
messages or the values read, which can hold the names of objects the run made.

## What it can't see

- **Writes of a debug context's breakpoints**, which need a running debug session, and of the encryption providers and Git, which need infrastructure the probe server lacks (a key store, a Git remote).
  or two-part keys): add a case to `write-cases.json`.
- **What depends on other properties**, beyond the fixture each case reads: a process's
  data source fields for another `Type`, say.

- **Properties inside complex values.** `$select=DataSource/x` isn't checked; TM1 answers 200
  for any name.
- **Types TM1 doesn't check** (`unverifiable`) and **subtypes' own properties.**
- **Names the binary doesn't hold** in a form the tokenizer reads, such as compressed or
  computed ones. Names an earlier probe found are tried again whatever the binary holds.

Improvements, such as reaching those types and reporting subtypes apart from types with no path,
are [#313](https://github.com/ajmyers/skipcheck-tm1/issues/313).

## Developing it

`./gradlew :tools:tm1-probe:build` runs its unit tests against a fake server (`FakeTm1`); CI runs
them. The run itself is `ProbeRunTm1Test`, which only the `probe` task runs (`TM1_PROBE=true`).

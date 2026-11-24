# tm1-probe

Asks a running TM1 what its `$metadata` doesn't say, and writes the answers to a file beside
the `$metadata` snapshot, one file per schema version:

```
java/tm1-client/src/model/metadata/tm1-schema-<v>.xml   TM1's $metadata, as captured
java/tm1-client/src/model/metadata/tm1-probe-<v>.json   what the probe found (this tool)
```

A build tool: not published, and nothing at run time depends on it. It's still growing ([#312]).
It finds **undeclared properties**, and **what each type does with each property in a POST and a
PATCH**. Until the generators read the file, it's a record and a diff, not an input.

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

It takes about 24 minutes with one server and about a quarter of that with four, plus a minute or
two to start the containers. Then review and commit the file it wrote.

## When to run it

- **For a new TM1 version**, with the `$metadata` snapshot (`MetadataSnapshotUpdateTest`). A new
  schema version writes a new file; diff it against the last version's file to see what changed.
- **When the probe changes.** Run it on the same version: the file shouldn't change unless the
  probe now finds more. A change in the probe's output format bumps `probeVersion`.

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

## The file

| Key | What it holds |
|---|---|
| `server` | `productVersion` and `schemaVersion` |
| `binary` | the binary's SHA-256, and how many candidates were tried |
| `undeclared` | per entity type, the properties TM1 accepts that `$metadata` doesn't declare, each with the kind of value read (`string`, `number`, `boolean`, `array`, `object`, `null`, `navigation`, or `unread`). `null` says only that the fixture's value was null. |
| `declaredRefused` | per entity type, declared properties TM1 refused: `$metadata` declares more than the server has |
| `unverifiable` | types TM1 doesn't check names for, such as `View` (any type with subtypes, in any form, casts included) and `Annotation`: undeclared properties there are invisible to this probe |
| `writes` | per case: `postRead`, `minimal`, `required`, and per property `post`, `patch` and `patchNull`, then `rename`. An outcome is `applied` (read back as sent), `ignored` (2xx, read back as the default), `transformed` (2xx, read back as neither: `UniqueName` follows the name, line ends are normalised), `rejected` (with `status` and TM1's error `code`), `accepted` (2xx, nothing to read back), or `untested` (no value to send) |
| `collections` | per collection case, what a PATCH of it does: `replaces`, `appends`, `reorders`, `ignored`, `rejected` or `mixed` |
| `unreached` | types the walk found no path to. It follows declared navigation types, so a subtype (`NativeView`, `ProcessReference`) is reached only as its base type and is listed here. So are types nothing on the server holds (`GitPlan` without Git). |

Everything is sorted and nothing depends on when or where it ran: probing the same server twice
writes the same file. The writes keep outcomes, statuses and error codes only, never TM1's
messages or the values read, which can hold the names of objects the run made.

## What it can't see

- **Writes of types without a case**, such as `ChoreTask` and `Edge` (keys the server assigns,
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

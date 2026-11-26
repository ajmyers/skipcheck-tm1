# Container conformance

Tests that every language's `tm1-testcontainers` asks Docker for the same containers, given the same
configuration.

The parity gates hold the four packages to the same methods and the same tests. This holds them to
the same result: each language starts a scenario, Docker is asked what it got, and the four answers
must be equal.

## Running it

It needs Docker and a TM1 source, as the live tests do (`TM1_BUNDLE_DIR` and the like: see
[`scripts/live-tests.sh`](../scripts/live-tests.sh)). CI has no TM1, so it doesn't run there.

```sh
TM1_BUNDLE_DIR=~/Downloads ./gradlew containerConformance --continue
```

- Each language's `ContainerConformanceTest` runs in turn: Java, Python, .NET, TypeScript.
- A scenario that needs TM1 Web or Cognos is skipped without a source for it
  (`TM1_WEB_BUNDLE_DIR`, `COGNOS_ZIP_DIR`).
- Then [`scripts/compare-containers.py`](../scripts/compare-containers.py) compares what each wrote
  to `build/container-conformance/<language>/<scenario>.json`, and prints each difference.

The tests are ordinary tests too: a language's own test run starts the scenarios when it has a TM1
source. Only the comparison needs all four.

## Scenarios

Each is a `pa-environment.yaml`, read with `PlanningAnalyticsEnvironment.fromConfig`. It names no
images and no `ibm` folder, so each part's source is what the machine is set up with.

| Scenario | What it starts | Needs |
|---|---|---|
| [`one-server`](scenarios/one-server.yaml) | One TM1 server with the sample database and a `tm1s.cfg` setting | TM1 |
| [`servers-and-web`](scenarios/servers-and-web.yaml) | Two TM1 servers, their Admin Server, TM1 Web | TM1, TM1 Web |
| [`cam-security`](scenarios/cam-security.yaml) | Two TM1 servers in CAM security, Cognos, Keycloak | TM1, Cognos |

A line `# unordered: <container> <container>` names containers that don't depend on each other: a
language may start them at once or one after the other.

## What's compared

From `docker inspect` of each container, and `docker network inspect` of the environment's network:

- **Per container:** the image and its ID (so the platform too), entrypoint, command, environment,
  labels, exposed and published ports, hostname, user, working directory, binds, mounts, tmpfs, extra
  hosts, the network mode, **every network it's on and its aliases there**, and whether it's
  privileged, its capabilities, memory, shared memory and restart policy.
- **The network:** its driver, options, labels, and which containers are on it.
- **The order:** which container started before which.

The run's own values are replaced by placeholders: a container's ID and address, the network's name
and ID. A host port Docker picked reads as `random`.

Every container carries the label `io.skipcheck.tm1.testcontainers`, whose value is the language
that started it. The comparison requires it to name the language whose log it's in.

**Not compared**, because `docker inspect` doesn't show them: the readiness check and its timeout,
and files copied into a container before it starts.

## Known differences

[`differences.txt`](differences.txt) lists what's left out of every reading, each line with its
reason: a label, a network alias or a network option that one Testcontainers library adds on its
own. A line that matches nothing fails the comparison once all four languages have run, so the list
can't go stale. "Not fixed yet" isn't a reason: a real difference is fixed in the package.

## Adding a scenario

1. Add `scenarios/<name>.yaml` and a row above.
2. Add a test for it to Java's `ContainerConformanceTest`, then the same test in Python
   (`tests/test_container_conformance.py`), .NET (`ContainerConformanceTests.cs`) and TypeScript
   (`test/container-conformance.test.ts`). The test-parity gates fail until all four have it.
3. Run `./gradlew containerConformance --continue`.

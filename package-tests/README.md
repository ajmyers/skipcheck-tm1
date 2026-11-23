# Package tests

The tests of each client run against its sources. These run against its **packages**: each ecosystem's packages are built as a release builds them, installed somewhere clean, and used by a small program, the way someone who got them from Maven Central, PyPI, NuGet or npm would use them. They catch what the other tests can't see: a package without its generated code, a missing file or dependency, an export that points at nothing.

```sh
./gradlew packageTest            # all four
./gradlew packageTestPython      # one: packageTestJava, packageTestPython, packageTestDotnet, packageTestTypescript
```

CI runs them on every pull request, and the release workflow runs them on the packages it's about to publish.

| | Built into `build/packages/` | Installed into | Program |
|---|---|---|---|
| Java | `maven`: every module, published to a Maven repository there | An application of its own build ([`java/`](java)), which takes them by the BOM and compiles for Java 17 | [`Smoke.java`](java/src/main/java/Smoke.java) |
| Python | `python`: a wheel and an sdist of each package | Two empty virtual environments: one gets the wheels, the other the sdists, which pip builds | [`smoke.py`](python/smoke.py) |
| .NET | `dotnet`: the two nupkg files | An application ([`dotnet/`](dotnet)) that references them, with a package cache of its own | [`Program.cs`](dotnet/Program.cs) |
| TypeScript | `npm`: the two tarballs `npm pack` makes | An empty project ([`typescript/`](typescript)) | [`smoke.mjs`](typescript/smoke.mjs), and [`types.ts`](typescript/types.ts), compiled against the packages' declarations |

Each program starts a fake TM1 on localhost that answers a login, the version, `$metadata` and the cubes, runs the guide's Hello World against it, and checks what came back. Then it checks what else the packages should bring: the testcontainers package and the `paw-local.sh` it ships, the license and notice, and for Java a class of every module.

No TM1 and no Docker are needed. The other libraries each package depends on come from the public registries, as they would for a user.

Things that are easy to get wrong here:

- **The packages under test come only from `build/packages`.** Installed by name, pip or NuGet would take a newer release from the public registry instead and test that. So pip gets the files themselves, [`nuget.config`](dotnet/nuget.config) maps `Skipcheck.*` to the folder, and the Java build takes `io.skipcheck` from the folder's repository only.
- **Nothing comes from the sources.** The Python program runs from outside the repository and checks where its imports came from; the others check that too.
- **`-PprebuiltPackages` tests what's already in `build/packages`** without building it. The release workflow builds the packages with the release's version, tests those files, and publishes those files.

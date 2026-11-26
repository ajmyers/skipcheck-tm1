// SPDX-License-Identifier: Apache-2.0

// The whole repository from one place, on macOS, Linux and Windows. The Java modules build as
// Gradle projects (./gradlew build). The Python, .NET and TypeScript clients keep their own tools,
// and these tasks run them, the same commands CI runs:
//
//   ./gradlew checkPython          pytest, with the layout, lint and type checks it includes
//   ./gradlew checkDotnet          dotnet test
//   ./gradlew checkTypescript      npm test (with prettier, eslint and tsc), then the build
//   ./gradlew checkLicense         every source file starts with its license line
//   ./gradlew checkScripts         the tests of scripts/ and the git hooks, and ShellCheck
//   ./gradlew checkApiSite         the tests of the API docs site's Worker
//   ./gradlew checkAll             all of the above, and ./gradlew build
//
//   ./gradlew packageTest          builds every package as a release does, installs each somewhere
//                                  clean and runs it (package-tests/README.md)
//
//   ./gradlew liveTest --continue  every client's tests against a real TM1, then the comparison of
//                                  what each sent (needs a TM1 source: see scripts/live-tests.sh)
//
//   ./gradlew containerConformance --continue
//                                  every language's testcontainers starts the same scenarios, then
//                                  the comparison of what Docker got (needs Docker and a TM1 source:
//                                  see testcontainers-conformance/README.md)
//   ./gradlew probeTm1             probes a TM1 for what its $metadata doesn't say, and writes it
//                                  beside the snapshot (java/tools/tm1-probe/README.md)
//
// They're opt-in: ./gradlew build stays the Java build, which needs only a JDK.

plugins {
	id("tm1.tools")
}

val root = layout.projectDirectory
val pythonVenv = layout.buildDirectory.dir("python-venv")
// Where the other clients' suites write their reports, for scripts/test-summary.py (Java's are Gradle's own)
val testResults = layout.buildDirectory.dir("test-results").get()

// One virtual environment for every Python task, with both packages installed for editing
val pythonEnvironment = tasks.register<PythonVenv>("pythonEnvironment") {
	description = "Makes the build's Python virtual environment and installs the client and testcontainers into it."
	venv = pythonVenv
	interpreter = providers.gradleProperty("python").orElse(providers.environmentVariable("PYTHON"))
	// build: what packagePython makes the wheels and sdists with
	install = listOf("-e", root.dir("python/tm1-client").asFile.path + "[test]", "-e", root.dir("python/tm1-testcontainers").asFile.path + "[test]", "build")
	directory = root
}

fun PythonTask.python() {
	venv = pythonVenv
	dependsOn(pythonEnvironment)
}

val npmInstall = tasks.register<NpmInstall>("npmInstall") {
	description = "Installs the TypeScript workspace's dependencies (npm ci), when they're missing or the lock file changed."
	directory = root.dir("typescript")
}

// A machine with only a newer .NET SDK than the projects target (net10.0) runs them on the runtime it has
val dotnetEnvironment = mapOf("DOTNET_ROLL_FORWARD" to "LatestMajor")

val checkPython = tasks.register<PythonTask>("checkPython") {
	group = "verification"
	description = "Runs the Python client's and testcontainers' tests (pytest), with their layout, lint and type checks."
	python()
	directory = root
	commands = listOf(listOf("python", "-m", "pytest", "python", "--junitxml=" + testResults.file("python/pytest.xml").asFile.path))
}

val checkDotnet = tasks.register<ToolTask>("checkDotnet") {
	group = "verification"
	description = "Builds and tests the .NET client and testcontainers (dotnet test)."
	directory = root
	// A report per test project, named by the time: the folder is emptied first, so they're this run's
	fresh = listOf(testResults.dir("dotnet").asFile.path)
	commands = listOf(listOf("dotnet", "test", "dotnet/Tm1.slnx", "--logger", "trx", "--results-directory", testResults.dir("dotnet").asFile.path))
	defaultEnvironment = dotnetEnvironment
}

val checkTypescript = tasks.register<ToolTask>("checkTypescript") {
	dependsOn(npmInstall)
	group = "verification"
	description = "Tests the TypeScript client and testcontainers (npm test, with prettier, eslint and tsc), then builds them."
	directory = root.dir("typescript")
	commands = listOf(listOf("npm", "test"), listOf("npm", "run", "build"))
	// Each vitest.config.ts writes a JUnit report there, when this is set
	environment = mapOf("TEST_RESULTS" to testResults.dir("typescript").asFile.path)
}

val checkLicense = tasks.register<PythonTask>("checkLicense") {
	group = "verification"
	description = "Checks that every source file starts with its license line (scripts/license-headers.py)."
	python()
	directory = root
	commands = listOf(listOf("python", "scripts/license-headers.py"))
}

val checkScripts = tasks.register<PythonTask>("checkScripts") {
	group = "verification"
	description = "Tests the repository's own tooling (scripts/tests): the scripts, the git hooks, and ShellCheck over every shell script."
	python()
	directory = root
	commands = listOf(listOf("python", "-m", "pytest", "scripts/tests", "--junitxml=" + testResults.file("scripts/pytest.xml").asFile.path))
}

// The page builders need their dependencies (marked, for the Docker pages)
val apiSiteNpmInstall = tasks.register<NpmInstall>("apiSiteNpmInstall") {
	description = "Installs the docs site's dependencies (npm ci), when they're missing or the lock file changed."
	directory = root.dir("api-site")
}

val checkApiSite = tasks.register<ToolTask>("checkApiSite") {
	group = "verification"
	description = "Tests the docs site's Worker and page builders (api-site/test)."
	dependsOn(apiSiteNpmInstall)
	directory = root.dir("api-site")
	commands = listOf(listOf("npm", "test"))
}

tasks.register("checkAll") {
	group = "verification"
	description = "Checks every codebase: the Java build, the Python, .NET and TypeScript clients, the scripts and hooks, the API docs site, and the license lines."
	dependsOn(checkPython, checkDotnet, checkTypescript, checkLicense, checkScripts, checkApiSite)
	dependsOn(provider { subprojects.mapNotNull { it.tasks.findByName("build") } })
}

// The live tests: each client's tests against a real TM1, which CI doesn't have. Each suite starts
// its own tm1-server container, so they run one after the other, never two at once.
val traffic = layout.buildDirectory.dir("conformance-live")
val liveSources = listOf("TM1_PRIVATE_IMAGE", "TM1_HOME_DIR", "TM1_INSTALL_VOLUME", "TM1_BUNDLE_DIR", "TM1_BUNDLE_URL", "TM1_URL")
// Where the Python, .NET and TypeScript live suites write their reports, under build/test-results, for
// scripts/test-summary.py and scripts/live-test-versions.py (Java's are Gradle's own)
val liveReports = listOf("python-live", "dotnet-live", "typescript-live")

val liveTestPrepare = tasks.register("liveTestPrepare") {
	description = "Checks a TM1 is configured for the live tests, and clears the run before's traffic and the Python, .NET and TypeScript live reports."
	val configured = liveSources.map { providers.environmentVariable(it) }
	val names = liveSources
	val directory = traffic
	val reports = liveReports.map { testResults.dir(it).asFile }
	outputs.upToDateWhen { false }
	doLast {
		if (configured.none { it.isPresent }) {
			throw GradleException("No TM1 configured: set one of " + names.dropLast(1).joinToString(", ") + " (or TM1_URL). See scripts/live-tests.sh.")
		}
		directory.get().asFile.deleteRecursively()
		reports.forEach { it.deleteRecursively() }
	}
}

val liveTestJava = ":tm1-client:liveTest"

val liveTestPython = tasks.register<PythonTask>("liveTestPython") {
	group = "verification"
	description = "Runs the Python client's tests against a real TM1 (tests/live)."
	python()
	directory = root.dir("python/tm1-client")
	commands = listOf(listOf("python", "-m", "pytest", "tests/live", "-rs", "--junitxml=" + testResults.file("python-live/pytest.xml").asFile.path))
	dependsOn(liveTestPrepare)
	mustRunAfter(liveTestJava)
}

val liveTestDotnet = tasks.register<ToolTask>("liveTestDotnet") {
	group = "verification"
	description = "Runs the .NET client's tests against a real TM1 (the Live tests)."
	directory = root
	commands = listOf(listOf("dotnet", "test", "dotnet/Tm1.slnx", "--filter", "FullyQualifiedName~.Live.", "--logger", "trx", "--results-directory", testResults.dir("dotnet-live").asFile.path))
	defaultEnvironment = dotnetEnvironment
	dependsOn(liveTestPrepare)
	mustRunAfter(liveTestJava, liveTestPython)
}

val liveTestTypescript = tasks.register<ToolTask>("liveTestTypescript") {
	dependsOn(npmInstall)
	group = "verification"
	description = "Runs the TypeScript client's tests against a real TM1 (test/live)."
	directory = root.dir("typescript")
	// test:live, unlike npm test, doesn't generate the model first, and it isn't committed: in a
	// fresh checkout the live tests would find no src/generated (#321)
	commands = listOf(
		listOf("npm", "run", "generate", "-w", "tm1-client"),
		listOf("npm", "run", "test:live", "-w", "tm1-client", "--", "--reporter=default", "--reporter=junit", "--outputFile.junit=" + testResults.file("typescript-live/vitest.xml").asFile.path),
	)
	dependsOn(liveTestPrepare)
	mustRunAfter(liveTestJava, liveTestPython, liveTestDotnet)
}

// A task of its own, not a command after the client's: a task stops at its first failing command,
// so a failing client live test left these unrun and missing from the report (#437). With
// --continue one task's failure doesn't stop the other.
val liveTestTypescriptContainers = tasks.register<ToolTask>("liveTestTypescriptContainers") {
	dependsOn(npmInstall)
	group = "verification"
	description = "Runs the TypeScript tm1-testcontainers' tests against a real TM1."
	directory = root.dir("typescript")
	commands = listOf(
		listOf("npm", "test", "-w", "tm1-testcontainers", "--", "--reporter=default", "--reporter=junit", "--outputFile.junit=" + testResults.file("typescript-live/tm1-testcontainers.xml").asFile.path),
	)
	dependsOn(liveTestPrepare)
	mustRunAfter(liveTestJava, liveTestPython, liveTestDotnet, liveTestTypescript)
}

// Not a dependency of the suites, only after them: with --continue it still compares what the
// suites that passed sent, when another failed
val compareLiveTraffic = tasks.register<PythonTask>("compareLiveTraffic") {
	group = "verification"
	description = "Requires every client to have sent TM1 the same requests in the live conformance scenarios."
	python()
	directory = root
	commands = listOf(listOf("python", "scripts/compare-live-traffic.py", traffic.get().asFile.path))
	mustRunAfter(liveTestJava, liveTestPython, liveTestDotnet, liveTestTypescript, liveTestTypescriptContainers)
}

tasks.register("liveTest") {
	group = "verification"
	description = "Runs every client's tests against a real TM1, then compares what each sent. Use --continue to run them all when one fails."
	dependsOn(liveTestJava, liveTestPython, liveTestDotnet, liveTestTypescript, liveTestTypescriptContainers, compareLiveTraffic)
}

// The container conformance run: each language's tm1-testcontainers starts the same scenarios
// (testcontainers-conformance/scenarios), one language after the other, and writes what Docker got;
// then all four must have got the same containers. It needs Docker and a TM1 source, as the live tests.
val containerLogs = layout.buildDirectory.dir("container-conformance")

val containerConformancePrepare = tasks.register("containerConformancePrepare") {
	description = "Checks a TM1 is configured for the container conformance run, and clears the run before's logs."
	// Not TM1_URL: a server that's already running has no container to compare
	val names = liveSources - "TM1_URL"
	val configured = names.map { providers.environmentVariable(it) }
	val directory = containerLogs
	outputs.upToDateWhen { false }
	doLast {
		// First, whatever comes next: the comparison mustn't find an earlier run's logs
		directory.get().asFile.deleteRecursively()
		if (configured.none { it.isPresent }) {
			throw GradleException("No TM1 configured: set one of " + names.joinToString(", ") + ". See testcontainers-conformance/README.md.")
		}
	}
}

val containerConformanceJava = ":tm1-testcontainers:containerConformance"

val containerConformancePython = tasks.register<PythonTask>("containerConformancePython") {
	group = "verification"
	description = "Starts the container conformance scenarios with the Python testcontainers and records what Docker got."
	python()
	directory = root.dir("python/tm1-testcontainers")
	commands = listOf(listOf("python", "-m", "pytest", "tests/test_container_conformance.py", "-rs"))
	dependsOn(containerConformancePrepare)
	mustRunAfter(containerConformanceJava)
}

val containerConformanceDotnet = tasks.register<ToolTask>("containerConformanceDotnet") {
	group = "verification"
	description = "Starts the container conformance scenarios with the .NET testcontainers and records what Docker got."
	directory = root
	commands = listOf(listOf("dotnet", "test", "dotnet/tests/Tm1.Testcontainers.Tests", "--filter", "FullyQualifiedName~.ContainerConformanceTests."))
	defaultEnvironment = dotnetEnvironment
	dependsOn(containerConformancePrepare)
	mustRunAfter(containerConformanceJava, containerConformancePython)
}

val containerConformanceTypescript = tasks.register<ToolTask>("containerConformanceTypescript") {
	dependsOn(npmInstall)
	group = "verification"
	description = "Starts the container conformance scenarios with the TypeScript testcontainers and records what Docker got."
	directory = root.dir("typescript")
	// The testcontainers import the client, whose generated model isn't committed
	commands = listOf(
		listOf("npm", "run", "generate", "-w", "tm1-client"),
		listOf("npx", "-w", "tm1-testcontainers", "vitest", "run", "test/container-conformance.test.ts"),
	)
	dependsOn(containerConformancePrepare)
	mustRunAfter(containerConformanceJava, containerConformancePython, containerConformanceDotnet)
}

// Not a dependency of the suites, only after them: with --continue it still compares what the
// languages that passed got, when another failed
val compareContainers = tasks.register<PythonTask>("compareContainers") {
	group = "verification"
	description = "Requires every language's testcontainers to have got the same containers from Docker in the conformance scenarios."
	python()
	directory = root
	// --require-all: a language that was skipped (no Docker, say) mustn't pass for one that agreed
	commands = listOf(listOf("python", "scripts/compare-containers.py", "--require-all", containerLogs.get().asFile.path))
	mustRunAfter(containerConformanceJava, containerConformancePython, containerConformanceDotnet, containerConformanceTypescript)
}

tasks.register("containerConformance") {
	group = "verification"
	description = "Starts the same scenarios with every language's testcontainers, then compares what Docker got. Use --continue to run them all when one fails."
	dependsOn(containerConformanceJava, containerConformancePython, containerConformanceDotnet, containerConformanceTypescript, compareContainers)
}

// The probe: what a TM1 version does that its $metadata doesn't say (java/tools/tm1-probe/README.md).
// It reads the server's binary out of the container it starts, so it needs a TM1 source the
// container mounts: TM1_URL, a server already running, won't do.
val probeSources = liveSources - "TM1_URL"
val probeTm1Prepare = tasks.register("probeTm1Prepare") {
	description = "Checks a TM1 source is configured for the probe."
	val configured = probeSources.map { providers.environmentVariable(it) }
	val names = probeSources
	outputs.upToDateWhen { false }
	doLast {
		if (configured.none { it.isPresent }) {
			throw GradleException("No TM1 source configured: set one of " + names.joinToString(", ") + ". See java/tools/tm1-probe/README.md.")
		}
	}
}
tasks.register("probeTm1") {
	group = "verification"
	description = "Probes a TM1 for what its \$metadata doesn't declare, and writes java/tm1-client/src/model/metadata/tm1-probe-<schema>.json."
	dependsOn(probeTm1Prepare, ":tools:tm1-probe:probe")
}

// The package tests: each ecosystem's packages built as a release builds them, installed
// somewhere clean, and run (package-tests/README.md). The tests of the sources can't see a
// package that lacks a file, a dependency or its generated code.
//
// -PprebuiltPackages tests what is already in build/packages instead of building it: the release
// workflow builds the packages with the release's version, tests those, and publishes those.
val windows = System.getProperty("os.name").lowercase().contains("windows")
val packages = layout.buildDirectory.dir("packages").get()
val packageTests = layout.buildDirectory.dir("package-test").get()
val prebuilt = providers.gradleProperty("prebuiltPackages").isPresent
val rootPath = root.asFile.path
fun Task.unlessPrebuilt() {
	val build = !prebuilt
	onlyIf { build }
}
fun venvPython(venv: Directory): String = venv.file(if (windows) "Scripts/python.exe" else "bin/python").asFile.path

val packagePython = tasks.register<PythonTask>("packagePython") {
	description = "Builds the Python packages' wheels and sdists into build/packages/python."
	python()
	directory = root
	fresh = listOf(packages.dir("python").asFile.path)
	commands = listOf("tm1-client", "tm1-testcontainers").map { listOf("python", "-m", "build", "--outdir", packages.dir("python").asFile.path, "python/$it") }
	unlessPrebuilt()
}

val packageTestPython = tasks.register<PythonTask>("packageTestPython") {
	group = "verification"
	description = "Installs the Python wheels, then the sdists, each into an empty environment, and runs them."
	python()
	dependsOn(packagePython)
	directory = packageTests
	val smoke = root.file("package-tests/python/smoke.py").asFile.path
	val built = packages.dir("python").asFile.path
	val environments = mapOf("whl" to packageTests.dir("python-wheel"), "tar.gz" to packageTests.dir("python-sdist"))
	fresh = environments.values.map { it.asFile.path }
	// The packages by file, not by name: by name pip would take a newer release from PyPI instead.
	// Run from outside the repository, so nothing is imported from the sources
	commands = environments.flatMap { (extension, venv) ->
		listOf(
			listOf("python", "-m", "venv", venv.asFile.path),
			listOf(venvPython(venv), "-m", "pip", "install", "--quiet", "--disable-pip-version-check", "$built/*.$extension"),
			listOf(venvPython(venv), smoke),
		)
	}
}

val packageDotnet = tasks.register<ToolTask>("packageDotnet") {
	description = "Packs the .NET packages into build/packages/dotnet."
	directory = root
	fresh = listOf(packages.dir("dotnet").asFile.path)
	commands = listOf(listOf("dotnet", "pack", "dotnet/Tm1.slnx", "-c", "Release", "-o", packages.dir("dotnet").asFile.path))
	defaultEnvironment = dotnetEnvironment
	unlessPrebuilt()
}

val packageTestDotnet = tasks.register<ToolTask>("packageTestDotnet") {
	group = "verification"
	description = "Builds an application against the packed .NET packages and runs it."
	dependsOn(packageDotnet)
	directory = root
	val work = packageTests.dir("dotnet")
	fresh = listOf(work.asFile.path)
	commands = listOf(listOf("dotnet", "run", "--project", "package-tests/dotnet/Smoke.csproj", "--artifacts-path", work.dir("artifacts").asFile.path, "--", rootPath))
	defaultEnvironment = dotnetEnvironment
	// A package cache of its own: the machine's would keep an earlier build of the same version
	environment = mapOf("NUGET_PACKAGES" to work.dir("packages").asFile.path)
}

val packageTypescript = tasks.register<ToolTask>("packageTypescript") {
	description = "Packs the npm packages into build/packages/npm."
	dependsOn(npmInstall)
	directory = root.dir("typescript")
	fresh = listOf(packages.dir("npm").asFile.path)
	commands = listOf(listOf("npm", "pack", "-w", "tm1-client", "-w", "tm1-testcontainers", "--pack-destination", packages.dir("npm").asFile.path))
	unlessPrebuilt()
}

// An empty project to install the tarballs into, made anew each time
val packageTestTypescriptProject = tasks.register<Sync>("packageTestTypescriptProject") {
	from(root.dir("package-tests/typescript"))
	into(packageTests.dir("typescript"))
}

val packageTestTypescript = tasks.register<ToolTask>("packageTestTypescript") {
	group = "verification"
	description = "Installs the packed npm packages into an empty project, runs them and compiles against their types."
	dependsOn(packageTypescript, packageTestTypescriptProject, npmInstall)
	directory = packageTests.dir("typescript")
	commands = listOf(
		listOf("npm", "install", "--no-audit", "--no-fund", packages.dir("npm").asFile.path + "/*.tgz"),
		listOf("node", "smoke.mjs", rootPath),
		// The workspace's compiler, against the installed packages' declarations
		listOf("node", root.file("typescript/node_modules/typescript/bin/tsc").asFile.path, "-p", "."),
	)
}

val cleanPackageTestRepository = tasks.register<Delete>("cleanPackageTestRepository") {
	delete(packages.dir("maven"))
	unlessPrebuilt()
}

val packageJava = tasks.register("packageJava") {
	description = "Publishes the Java libraries to a Maven repository in build/packages/maven."
	dependsOn(provider { subprojects.mapNotNull { it.tasks.findByName("publishAllPublicationsToPackageTestRepository") } })
}
subprojects {
	tasks.matching { it.name.endsWith("ToPackageTestRepository") }.configureEach {
		mustRunAfter(cleanPackageTestRepository)
		unlessPrebuilt()
	}
}

val packageTestJava = tasks.register<ToolTask>("packageTestJava") {
	group = "verification"
	description = "Builds an application against the published Java libraries, by their BOM, and runs it."
	dependsOn(cleanPackageTestRepository, packageJava)
	directory = root
	// A build of its own (package-tests/java), which takes the libraries from the repository above
	commands = listOf(
		listOf(
			root.file(if (windows) "gradlew.bat" else "gradlew").asFile.path, "-p", "package-tests/java", "--project-cache-dir",
			packageTests.dir("java-gradle").asFile.path, "--quiet", "run", "-PskipcheckVersion=$version",
		),
	)
}

tasks.register("packageTest") {
	group = "verification"
	description = "Builds every package as a release does, installs each somewhere clean, and runs it."
	dependsOn(packageTestJava, packageTestPython, packageTestDotnet, packageTestTypescript)
}

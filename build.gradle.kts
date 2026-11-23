// SPDX-License-Identifier: Apache-2.0

// The whole repository from one place, on macOS, Linux and Windows. The Java modules build as
// Gradle projects (./gradlew build). The Python, .NET and TypeScript clients keep their own tools,
// and these tasks run them, the same commands CI runs:
//
//   ./gradlew checkPython          pytest, with the layout, lint and type checks it includes
//   ./gradlew checkDotnet          dotnet test
//   ./gradlew checkTypescript      npm test (with prettier, eslint and tsc), then the build
//   ./gradlew checkLicense         every source file starts with its license line
//   ./gradlew checkAll             all of the above, and ./gradlew build
//
//   ./gradlew liveTest --continue  every client's tests against a real TM1, then the comparison of
//                                  what each sent (needs a TM1 source: see scripts/live-tests.sh)
//
// They're opt-in: ./gradlew build stays the Java build, which needs only a JDK.

plugins {
	id("tm1.tools")
}

val root = layout.projectDirectory
val pythonVenv = layout.buildDirectory.dir("python-venv")

// One virtual environment for every Python task, with both packages installed for editing
val pythonEnvironment = tasks.register<PythonVenv>("pythonEnvironment") {
	description = "Makes the build's Python virtual environment and installs the client and testcontainers into it."
	venv = pythonVenv
	interpreter = providers.gradleProperty("python").orElse(providers.environmentVariable("PYTHON"))
	install = listOf("-e", root.dir("python/tm1-client").asFile.path + "[test]", "-e", root.dir("python/tm1-testcontainers").asFile.path + "[test]")
	directory = root
	commands = listOf<List<String>>()
	defaultEnvironment = mapOf<String, String>()
}

fun PythonTask.python() {
	venv = pythonVenv
	defaultEnvironment = mapOf<String, String>()
	dependsOn(pythonEnvironment)
}

val npmInstall = tasks.register<NpmInstall>("npmInstall") {
	description = "Installs the TypeScript workspace's dependencies (npm ci), when they're missing or the lock file changed."
	directory = root.dir("typescript")
	commands = listOf<List<String>>()
	defaultEnvironment = mapOf<String, String>()
}

// A machine with only a newer .NET SDK than the projects target (net10.0) runs them on the runtime it has
val dotnetEnvironment = mapOf("DOTNET_ROLL_FORWARD" to "LatestMajor")

val checkPython = tasks.register<PythonTask>("checkPython") {
	group = "verification"
	description = "Runs the Python client's and testcontainers' tests (pytest), with their layout, lint and type checks."
	python()
	directory = root
	commands = listOf(listOf("python", "-m", "pytest", "python"))
}

val checkDotnet = tasks.register<ToolTask>("checkDotnet") {
	group = "verification"
	description = "Builds and tests the .NET client and testcontainers (dotnet test)."
	directory = root
	commands = listOf(listOf("dotnet", "test", "dotnet/Tm1.slnx"))
	defaultEnvironment = dotnetEnvironment
}

val checkTypescript = tasks.register<ToolTask>("checkTypescript") {
	dependsOn(npmInstall)
	group = "verification"
	description = "Tests the TypeScript client and testcontainers (npm test, with prettier, eslint and tsc), then builds them."
	directory = root.dir("typescript")
	commands = listOf(listOf("npm", "test"), listOf("npm", "run", "build"))
	defaultEnvironment = mapOf<String, String>()
}

val checkLicense = tasks.register<PythonTask>("checkLicense") {
	group = "verification"
	description = "Checks that every source file starts with its license line (scripts/license-headers.py)."
	python()
	directory = root
	commands = listOf(listOf("python", "scripts/license-headers.py"))
}

tasks.register("checkAll") {
	group = "verification"
	description = "Checks every codebase: the Java build, the Python, .NET and TypeScript clients, and the license lines."
	dependsOn(checkPython, checkDotnet, checkTypescript, checkLicense)
	dependsOn(provider { subprojects.mapNotNull { it.tasks.findByName("build") } })
}

// The live tests: each client's tests against a real TM1, which CI doesn't have. Each suite starts
// its own tm1-server container, so they run one after the other, never two at once.
val traffic = layout.buildDirectory.dir("conformance-live")
val liveSources = listOf("TM1_PRIVATE_IMAGE", "TM1_HOME_DIR", "TM1_INSTALL_VOLUME", "TM1_BUNDLE_DIR", "TM1_BUNDLE_URL", "TM1_URL")

val liveTestPrepare = tasks.register("liveTestPrepare") {
	description = "Checks a TM1 is configured for the live tests, and clears the traffic of the run before."
	val configured = liveSources.map { providers.environmentVariable(it) }
	val names = liveSources
	val directory = traffic
	outputs.upToDateWhen { false }
	doLast {
		if (configured.none { it.isPresent }) {
			throw GradleException("No TM1 configured: set one of " + names.dropLast(1).joinToString(", ") + " (or TM1_URL). See scripts/live-tests.sh.")
		}
		directory.get().asFile.deleteRecursively()
	}
}

val liveTestJava = ":tm1-client:liveTest"

val liveTestPython = tasks.register<PythonTask>("liveTestPython") {
	group = "verification"
	description = "Runs the Python client's tests against a real TM1 (tests/live)."
	python()
	directory = root.dir("python/tm1-client")
	commands = listOf(listOf("python", "-m", "pytest", "tests/live", "-rs"))
	dependsOn(liveTestPrepare)
	mustRunAfter(liveTestJava)
}

val liveTestDotnet = tasks.register<ToolTask>("liveTestDotnet") {
	group = "verification"
	description = "Runs the .NET client's tests against a real TM1 (the Live tests)."
	directory = root
	commands = listOf(listOf("dotnet", "test", "dotnet/Tm1.slnx", "--filter", "FullyQualifiedName~.Live."))
	defaultEnvironment = dotnetEnvironment
	dependsOn(liveTestPrepare)
	mustRunAfter(liveTestJava, liveTestPython)
}

val liveTestTypescript = tasks.register<ToolTask>("liveTestTypescript") {
	dependsOn(npmInstall)
	group = "verification"
	description = "Runs the TypeScript client's tests against a real TM1 (test/live), and the testcontainers' tests."
	directory = root.dir("typescript")
	commands = listOf(listOf("npm", "run", "test:live", "-w", "tm1-client"), listOf("npm", "test", "-w", "tm1-testcontainers"))
	defaultEnvironment = mapOf<String, String>()
	dependsOn(liveTestPrepare)
	mustRunAfter(liveTestJava, liveTestPython, liveTestDotnet)
}

// Not a dependency of the suites, only after them: with --continue it still compares what the
// suites that passed sent, when another failed
val compareLiveTraffic = tasks.register<PythonTask>("compareLiveTraffic") {
	group = "verification"
	description = "Requires every client to have sent TM1 the same requests in the live conformance scenarios."
	python()
	directory = root
	commands = listOf(listOf("python", "scripts/compare-live-traffic.py", traffic.get().asFile.path))
	mustRunAfter(liveTestJava, liveTestPython, liveTestDotnet, liveTestTypescript)
}

tasks.register("liveTest") {
	group = "verification"
	description = "Runs every client's tests against a real TM1, then compares what each sent. Use --continue to run them all when one fails."
	dependsOn(liveTestJava, liveTestPython, liveTestDotnet, liveTestTypescript, compareLiveTraffic)
}

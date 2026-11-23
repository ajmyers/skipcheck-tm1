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

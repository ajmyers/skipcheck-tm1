// SPDX-License-Identifier: Apache-2.0

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

private val windows = System.getProperty("os.name").lowercase().contains("windows")

/**
 * Runs the commands of another language's own build tool (pytest, npm, dotnet), one after the other,
 * failing at the first that fails. Gradle is only the way in, the same on macOS, Linux and Windows:
 * the tool decides what's up to date, so the task always runs, and it sees the environment Gradle
 * was started with (the TM1_* settings of the live tests among it).
 */
@DisableCachingByDefault(because = "Runs another build tool, which tracks its own inputs")
abstract class ToolTask : DefaultTask() {

	@get:Inject
	protected abstract val execOperations: ExecOperations

	/** Where the commands run. */
	@get:Internal
	abstract val directory: DirectoryProperty

	/** The command lines, each a program and its arguments. */
	@get:Input
	abstract val commands: ListProperty<List<String>>

	/** Environment variables for the commands, used only where the caller hasn't set them. */
	@get:Input
	abstract val defaultEnvironment: MapProperty<String, String>

	/** Environment variables for the commands, whatever the caller has set. */
	@get:Input
	abstract val environment: MapProperty<String, String>

	/** Folders emptied before the commands run (and made, if they aren't there), so nothing of an earlier run is left in them. */
	@get:Input
	abstract val fresh: ListProperty<String>

	init {
		outputs.upToDateWhen { false }
		defaultEnvironment.convention(mapOf())
		environment.convention(mapOf())
		fresh.convention(listOf())
		commands.convention(listOf())
	}

	@TaskAction
	fun runCommands() {
		fresh.get().map(::File).forEach {
			it.deleteRecursively()
			it.mkdirs()
		}
		prepare()
		commands.get().forEach { run(commandLine(expand(it)), directory.get().asFile) }
	}

	/** An argument whose file name has a * stands for the files that match it, as a shell would have it: build/packages/python/&#42;.whl. */
	private fun expand(command: List<String>): List<String> = command.flatMap { argument ->
		val pattern = File(argument)
		if (!pattern.name.contains('*')) {
			listOf(argument)
		}
		else {
			val name = Regex(pattern.name.split('*').joinToString(".*") { Regex.escape(it) })
			val matches = pattern.parentFile.listFiles { file -> name.matches(file.name) }.orEmpty().map { it.path }.sorted()
			if (matches.isEmpty()) {
				throw GradleException("No file matches $argument")
			}
			matches
		}
	}

	/** What has to be there before the commands run: a virtual environment, node_modules. */
	protected open fun prepare() {
	}

	/** The command line as this machine runs it. On Windows, npm, npx and batch files are scripts, which need cmd. */
	protected open fun commandLine(command: List<String>): List<String> =
		if (windows && (command.first() in setOf("npm", "npx") || command.first().endsWith(".bat"))) listOf("cmd", "/c") + command else command

	protected fun run(commandLine: List<String>, workingDirectory: File) {
		logger.lifecycle("> {}", commandLine.joinToString(" "))
		val variables = defaultEnvironment.get().filterKeys { System.getenv(it) == null } + environment.get()
		execOperations.exec {
			workingDir = workingDirectory
			commandLine(commandLine)
			environment(variables)
		}
	}

	/** What a command prints, or null if it can't be started or fails. */
	protected fun output(commandLine: List<String>): String? {
		val out = ByteArrayOutputStream()
		return try {
			val result = execOperations.exec {
				commandLine(commandLine)
				standardOutput = out
				errorOutput = ByteArrayOutputStream()
				isIgnoreExitValue = true
			}
			if (result.exitValue == 0) out.toString().trim() else null
		}
		catch (e: Exception) {
			null
		}
	}
}

/** The interpreter of a virtual environment. */
private fun venvPython(venv: File): File = venv.resolve(if (windows) "Scripts/python.exe" else "bin/python")

/**
 * Makes the build's own Python virtual environment on first use, and installs the packages into
 * it, so nothing is installed into the machine's Python and nothing has to be activated first.
 * One task, which every Python task depends on: Gradle runs tasks side by side, and two of them
 * making the same environment at once would break it.
 *
 * The interpreter the environment is made from is the first of these that is Python 3.10 or later:
 * -Ppython=... or the PYTHON environment variable, then python3, python, python3.14 down to
 * python3.10, and on Windows the py launcher. So CI's setup-python decides there, and a machine
 * whose python3 is too old (macOS's is 3.9) still finds a newer one beside it.
 */
@DisableCachingByDefault(because = "Runs another build tool, which tracks its own inputs")
abstract class PythonVenv : ToolTask() {

	/** The virtual environment. */
	@get:Internal
	abstract val venv: DirectoryProperty

	/** Arguments of "pip install", such as -e and a project folder. */
	@get:Input
	abstract val install: ListProperty<String>

	/** The interpreter to make the environment from, instead of looking for one. */
	@get:Input
	@get:Optional
	abstract val interpreter: Property<String>

	override fun prepare() {
		val python = venvPython(venv.get().asFile)
		if (!python.isFile) {
			run(find() + listOf("-m", "venv", venv.get().asFile.path), directory.get().asFile)
		}
		// Every time: an editable install is also what generates the client's code from the spec
		run(listOf(python.path, "-m", "pip", "install", "--quiet", "--disable-pip-version-check") + install.get(), directory.get().asFile)
	}

	private fun find(): List<String> {
		val named = interpreter.orNull
		val candidates = if (named != null) listOf(listOf(named))
		else listOf(listOf("python3"), listOf("python")) + (14 downTo 10).map { listOf("python3.$it") } + (if (windows) listOf(listOf("py", "-3")) else listOf())
		val recentEnough = listOf("-c", "import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)")
		return candidates.firstOrNull { output(it + recentEnough) != null }
			?: throw GradleException(
				if (named != null) "$named isn't Python 3.10 or later"
				else "No Python 3.10 or later found (tried python3, python, python3.14 to python3.10). Install one, or name it: -Ppython=/path/to/python",
			)
	}
}

/** Runs Python commands in the build's virtual environment (PythonVenv): a command that starts with "python" runs its interpreter. */
@DisableCachingByDefault(because = "Runs another build tool, which tracks its own inputs")
abstract class PythonTask : ToolTask() {

	/** The virtual environment. */
	@get:Internal
	abstract val venv: DirectoryProperty

	override fun commandLine(command: List<String>): List<String> =
		if (command.first() == "python") listOf(venvPython(venv.get().asFile).path) + command.drop(1) else super.commandLine(command)
}

/**
 * Installs a workspace's dependencies with "npm ci" when node_modules is missing or older than
 * package-lock.json: they're the lock file's, as in CI, without reinstalling them for every run.
 * One task, which every npm task depends on, so two never install at once.
 */
@DisableCachingByDefault(because = "Runs another build tool, which tracks its own inputs")
abstract class NpmInstall : ToolTask() {

	override fun prepare() {
		val workspace = directory.get().asFile
		val lock = workspace.resolve("package-lock.json")
		val installed = workspace.resolve("node_modules/.package-lock.json")
		if (!installed.isFile || installed.lastModified() < lock.lastModified()) {
			run(commandLine(listOf("npm", "ci")), workspace)
		}
	}
}

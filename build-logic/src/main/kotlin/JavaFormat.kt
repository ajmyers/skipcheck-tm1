// SPDX-License-Identifier: Apache-2.0

import io.spring.javaformat.formatter.FileFormatter
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.charset.StandardCharsets

/** The first line of every Java source: its license, as scripts/license-headers.py writes it for every language. */
const val LICENSE_LINE = "// SPDX-License-Identifier: Apache-2.0"

/** Whether a source starts with the license line. */
fun hasLicenseLine(source: String): Boolean = source.lineSequence().firstOrNull() == LICENSE_LINE

/**
 * Fails when a Java file isn't laid out the way Spring Java Format lays it out, or has an unused
 * import, imports out of order, or a fully qualified type name in code (see JavaImports), or doesn't
 * start with the license line. Cacheable, and only reruns when a source file changes.
 * `./gradlew format` fixes the layout, the imports (except fully qualified names) and the license line.
 *
 * Our own task around Spring Java Format's formatter, rather than its Gradle plugin, because the
 * plugin's tasks don't support the configuration cache (0.0.48 calls Task.project while running).
 */
@CacheableTask
abstract class CheckJavaFormat : DefaultTask() {

	@get:InputFiles
	@get:SkipWhenEmpty
	@get:IgnoreEmptyDirectories
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val source: ConfigurableFileCollection

	/** Lists the files checked; an output, so Gradle can cache and skip the check. */
	@get:OutputFile
	abstract val report: RegularFileProperty

	@TaskAction
	fun check() {
		val files = source.files.sorted()
		val unformatted = FileFormatter().formatFiles(files, StandardCharsets.UTF_8).filter { it.hasEdits() }.map { it.file }.toList()
		val imports = files.flatMap { file -> JavaImports.problems(file.readText()).map { "  ${file.path}:${it.line}: ${it.message}" } }
		val reportFile = report.get().asFile
		reportFile.parentFile.mkdirs()
		reportFile.writeText(files.joinToString("\n", postfix = "\n") { it.path })
		val unlicensed = files.filter { !hasLicenseLine(it.readText()) }
		val failures = mutableListOf<String>()
		if (unlicensed.isNotEmpty()) {
			failures += "These files don't start with \"$LICENSE_LINE\" (run ./gradlew format):\n" + unlicensed.joinToString("\n") { "  " + it.path }
		}
		if (unformatted.isNotEmpty()) {
			failures += "These files aren't formatted (run ./gradlew format):\n" + unformatted.joinToString("\n") { "  " + it.path }
		}
		if (imports.isNotEmpty()) {
			failures += "Import problems (./gradlew format removes unused imports and orders the rest):\n" + imports.joinToString("\n")
		}
		if (failures.isNotEmpty()) {
			throw GradleException(failures.joinToString("\n\n"))
		}
	}
}

/**
 * Formats Java files in place with Spring Java Format, after adding the license line where it's missing,
 * removing unused imports and ordering the rest.
 */
@DisableCachingByDefault(because = "Changes the source files in place")
abstract class FormatJava : DefaultTask() {

	@get:InputFiles
	@get:SkipWhenEmpty
	@get:IgnoreEmptyDirectories
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val source: ConfigurableFileCollection

	@TaskAction
	fun format() {
		for (file in source.files) {
			val text = file.readText()
			val licensed = if (hasLicenseLine(text) || text.isBlank()) text else LICENSE_LINE + "\n\n" + text
			val cleaned = JavaImports.sortImports(JavaImports.removeUnusedImports(licensed))
			if (cleaned != text) {
				file.writeText(cleaned)
			}
		}
		FileFormatter().formatFiles(source.files.sorted(), StandardCharsets.UTF_8).filter { it.hasEdits() }.forEach {
			it.save()
			logger.lifecycle("Formatted {}", it.file)
		}
	}
}

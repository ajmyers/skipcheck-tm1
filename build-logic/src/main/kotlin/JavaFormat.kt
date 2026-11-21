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

/**
 * Fails when a Java file isn't laid out the way Spring Java Format lays it out. Cacheable, and only
 * reruns when a source file changes. Run `./gradlew format` to fix what it reports.
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
		val reportFile = report.get().asFile
		reportFile.parentFile.mkdirs()
		reportFile.writeText(files.joinToString("\n", postfix = "\n") { it.path })
		if (unformatted.isNotEmpty()) {
			throw GradleException(
				"These files aren't formatted (run ./gradlew format):\n" + unformatted.joinToString("\n") { "  " + it.path })
		}
	}
}

/** Formats Java files in place with Spring Java Format. */
@DisableCachingByDefault(because = "Changes the source files in place")
abstract class FormatJava : DefaultTask() {

	@get:InputFiles
	@get:SkipWhenEmpty
	@get:IgnoreEmptyDirectories
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val source: ConfigurableFileCollection

	@TaskAction
	fun format() {
		FileFormatter().formatFiles(source.files.sorted(), StandardCharsets.UTF_8).filter { it.hasEdits() }.forEach {
			it.save()
			logger.lifecycle("Formatted {}", it.file)
		}
	}
}

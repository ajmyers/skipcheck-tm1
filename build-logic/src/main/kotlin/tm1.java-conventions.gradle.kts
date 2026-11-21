// Baseline for every Java subproject: toolchain, compiler flags, JUnit
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
	java
	id("io.freefair.lombok")
	// Error Prone: compile-time bug patterns, as errors because of -Werror below
	id("net.ltgt.errorprone")
}

val libs = the<VersionCatalogsExtension>().named("libs")

// Lombok: compile-time only, never a runtime dependency of published libraries.
// The plugin also runs delombok for Javadoc, so generated methods are documented.
lombok {
	version = libs.findVersion("lombok").get().requiredVersion
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(27)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.errorprone {
		// Lombok's generated code is Lombok's business
		disableWarningsInGeneratedCode = true
		// NullAway: in @NullMarked packages, nothing is null unless declared @Nullable (JSpecify)
		option("NullAway:OnlyNullMarked", "true")
		option("NullAway:JSpecifyMode", "true")
		check("NullAway", if (name.contains("Test")) CheckSeverity.OFF else CheckSeverity.ERROR)
	}
	options.encoding = "UTF-8"
	options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-serial,-processing,-options"))
	// Warnings fail the build: a "static method called on an instance" warning once hid a real bug
	options.compilerArgs.add("-Werror")
}

// Java layout: Spring Java Format (tabs, Spring's style). `./gradlew format` fixes it; checkFormat
// is part of check, so unformatted code fails the build. -PformatFiles=a.java,b.java (paths from
// the repository root) narrows format to those files; the pre-commit hook uses this.
// Hand-written sources only: generated ones (under build/) are the generator's business
val buildDirectory = layout.buildDirectory.get().asFile
val javaSourceDirs = provider {
	the<SourceSetContainer>().flatMap { it.allJava.srcDirs }.filter { !it.startsWith(buildDirectory) }
}
val javaSources = files(javaSourceDirs).asFileTree.matching { include("**/*.java") }
val formatOnly = providers.gradleProperty("formatFiles").orNull?.split(",")?.filter { it.isNotBlank() }?.map { rootDir.resolve(it) }
val format by tasks.registering(FormatJava::class) {
	group = "formatting"
	description = "Formats Java sources with Spring Java Format."
	if (formatOnly == null) {
		source.from(javaSources)
	}
	else {
		val dirs = javaSourceDirs.get()
		source.from(formatOnly.filter { f -> f.isFile && dirs.any { f.startsWith(it) } })
	}
}
val checkFormat by tasks.registering(CheckJavaFormat::class) {
	group = "verification"
	description = "Fails if any Java source isn't formatted with Spring Java Format."
	source.from(javaSources)
	report = layout.buildDirectory.file("reports/check-format.txt")
	mustRunAfter(format)
}
tasks.named("check") {
	dependsOn(checkFormat)
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()

	// Tests against a real TM1 run only when one of these points at a TM1 install (see
	// tm1-testcontainers), and skip otherwise. Gradle doesn't track environment variables, so
	// declare them as inputs: otherwise a run that skipped could be reused from the build cache
	// after TM1 is configured, and the TM1 tests would never run.
	for (name in listOf(
		"TM1_PRIVATE_IMAGE", "TM1_HOME_DIR", "TM1_INSTALL_VOLUME", "TM1_BUNDLE_DIR",
		"TM1_BUNDLE", "TM1_BUNDLE_URL", "TM1_BUNDLE_SHA256", "TM1_SERVER_IMAGE",
		// ...and TM1 Web, for tests that need it too
		"TM1_WEB_PRIVATE_IMAGE", "TM1_WEB_HOME_DIR", "TM1_WEB_INSTALL_VOLUME", "TM1_WEB_BUNDLE_DIR",
		"TM1_WEB_BUNDLE", "TM1_WEB_BUNDLE_URL", "TM1_WEB_BUNDLE_SHA256", "TM1_WEB_IMAGE",
		// ...and PAW
		"PAW_ZIP", "PAW_BUNDLE_DIR", "PAW_ACCEPT_LICENSE",
		// Opt-in switches for tests that write files (see tm1-client's metadata tests)
		"TM1_UPDATE_METADATA_SNAPSHOT",
	)) {
		inputs.property(name, providers.environmentVariable(name).orElse(""))
	}
}

dependencies {
	"errorprone"(libs.findLibrary("errorprone-core").get())
	"errorprone"(libs.findLibrary("nullaway").get())
	"testImplementation"(platform(libs.findLibrary("junit-bom").get()))
	"testImplementation"(libs.findLibrary("junit-jupiter").get())
	"testImplementation"(libs.findLibrary("assertj-core").get())
	"testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

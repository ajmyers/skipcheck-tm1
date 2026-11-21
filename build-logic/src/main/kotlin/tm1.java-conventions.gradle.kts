// Baseline for every Java subproject: toolchain, compiler flags, JUnit
plugins {
	java
	id("io.freefair.lombok")
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
	options.encoding = "UTF-8"
	options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-serial,-processing,-options"))
	// Warnings fail the build: a "static method called on an instance" warning once hid a real bug
	options.compilerArgs.add("-Werror")
}

// Java layout: Spring Java Format (tabs, Spring's style). `./gradlew format` fixes it; checkFormat
// is part of check, so unformatted code fails the build. -PformatFiles=a.java,b.java (paths from
// the repository root) narrows format to those files; the pre-commit hook uses this.
val javaSources = the<SourceSetContainer>().map { it.allJava }
val formatOnly = providers.gradleProperty("formatFiles").orNull?.split(",")?.filter { it.isNotBlank() }?.map { rootDir.resolve(it) }
val format by tasks.registering(FormatJava::class) {
	group = "formatting"
	description = "Formats Java sources with Spring Java Format."
	if (formatOnly == null) {
		source.from(javaSources)
	}
	else {
		val dirs = javaSources.flatMap { it.srcDirs }
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

tasks.withType<Test>().configureEach {
	useJUnitPlatform()

	// Tests against a real TM1 run only when one of these points at a TM1 install (see
	// tm1-testcontainers), and skip otherwise. Gradle doesn't track environment variables, so
	// declare them as inputs: otherwise a run that skipped could be reused from the build cache
	// after TM1 is configured, and the TM1 tests would never run.
	for (name in listOf(
		"TM1_PRIVATE_IMAGE", "TM1_HOME_DIR", "TM1_INSTALL_VOLUME", "TM1_BUNDLE_DIR",
		"TM1_BUNDLE", "TM1_BUNDLE_URL", "TM1_BUNDLE_SHA256", "TM1_SERVER_IMAGE",
		// Opt-in switches for tests that write files (see tm1-client's metadata tests)
		"TM1_UPDATE_METADATA_SNAPSHOT",
	)) {
		inputs.property(name, providers.environmentVariable(name).orElse(""))
	}
}

dependencies {
	"testImplementation"(platform(libs.findLibrary("junit-bom").get()))
	"testImplementation"(libs.findLibrary("junit-jupiter").get())
	"testImplementation"(libs.findLibrary("assertj-core").get())
	"testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

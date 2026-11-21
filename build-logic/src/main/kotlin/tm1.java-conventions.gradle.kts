// Baseline for every Java subproject: toolchain, compiler flags, JUnit
plugins {
	java
}

val libs = the<VersionCatalogsExtension>().named("libs")

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(27)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.encoding = "UTF-8"
	options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-serial,-processing,-options"))
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

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
}

dependencies {
	"testImplementation"(platform(libs.findLibrary("junit-bom").get()))
	"testImplementation"(libs.findLibrary("junit-jupiter").get())
	"testImplementation"(libs.findLibrary("assertj-core").get())
	"testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

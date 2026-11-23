// SPDX-License-Identifier: Apache-2.0

plugins {
	application
}

// The version that was published to build/packages/maven: -PskipcheckVersion=...
val skipcheckVersion = providers.gradleProperty("skipcheckVersion").get()

// Nothing is written beside the sources
layout.buildDirectory = file("../../build/package-test/java")

dependencies {
	// Versions come from the BOM, as the guide tells applications to take them
	implementation(platform("io.skipcheck:tm1-bom:$skipcheckVersion"))
	implementation("io.skipcheck:tm1-client")
	implementation("io.skipcheck:tm1-testcontainers")
	implementation("io.skipcheck:tm1-spring-boot-starter")
	implementation("io.skipcheck:tm1-spring-boot-testcontainers")
}

// The libraries promise Java 17 bytecode: compiling for 17 against them fails if a jar is newer
tasks.withType<JavaCompile>().configureEach {
	options.release = 17
}

application {
	mainClass = "Smoke"
}

tasks.named<JavaExec>("run") {
	// The repository's root, for the $metadata snapshot the fake TM1 serves
	args(file("../..").path)
}

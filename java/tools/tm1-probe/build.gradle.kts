// SPDX-License-Identifier: Apache-2.0

// Probes a running TM1 for what its $metadata doesn't say, and writes what it finds beside the
// snapshot (java/tm1-client/src/model/metadata/tm1-probe-<schema version>.json). Run it for a new
// TM1 version with ./gradlew probeTm1; see README.md. A build tool: not published.
plugins {
	id("tm1.java-conventions")
}

description = "Probes a TM1 server for what its \$metadata doesn't declare (build tool, not published)"

dependencies {
	implementation(project(":tm1-csdl"))
	implementation(libs.jspecify)
	implementation(libs.jackson.databind)

	// The probe run gets its server from @RequiresTm1, from any of the TM1 sources
	testImplementation(project(":tm1-testcontainers"))
}

val metadataDir = rootDir.resolve("java/tm1-client/src/model/metadata")

tasks.test {
	// The run itself is the probe task's; without TM1_PROBE it skips anyway
	filter {
		excludeTestsMatching("*ProbeRunTm1Test")
	}
}

tasks.register<Test>("probe") {
	group = "verification"
	description = "Probes a TM1 server (from TM1_HOME_DIR, TM1_BUNDLE_DIR, ...) and writes tm1-probe-<schema>.json beside the \$metadata snapshot."
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	filter {
		includeTestsMatching("*ProbeRunTm1Test")
	}
	environment("TM1_PROBE", "true")
	// How many identical servers share the types (4 unless set)
	providers.environmentVariable("TM1_PROBE_SERVERS").orNull?.let { environment("TM1_PROBE_SERVERS", it) }
	systemProperty("tm1.probe.metadataDir", metadataDir.path)
	// It reads a server, so it's never up to date
	outputs.upToDateWhen { false }
	mustRunAfter(":probeTm1Prepare")
	testLogging {
		showStandardStreams = true
		events("passed", "skipped", "failed")
	}
}

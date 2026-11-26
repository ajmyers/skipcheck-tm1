// SPDX-License-Identifier: Apache-2.0

plugins {
	id("tm1.java-library")
}

description = "Testcontainers support for running IBM Planning Analytics (TM1) servers in tests"

mavenPublishing {
	pom {
		name = "TM1 / IBM Planning Analytics Testcontainers"
		description = project.description
	}
}

dependencies {
	// Nullness annotations in the API (@NullMarked packages, @Nullable), as Spring 7 uses
	api(libs.jspecify)
	// Tm1Version is part of this module's API (Tm1ServerContainer.withExpectedVersion)
	api(project(":tm1-client"))
	api(libs.testcontainers)
	// @RequiresTm1 is a JUnit Jupiter extension
	api(platform(libs.junit.bom))
	api(libs.junit.jupiter.api)
	// PlanningAnalyticsEnvironment.fromConfig reads pa-setup's pa-environment.yaml
	implementation(libs.snakeyaml)
}

// The pa-environment.yaml files fromConfig's tests read, shared with the other clients
tasks.withType<Test>().configureEach {
	val fixtures = rootDir.resolve("spec/conformance/pa-environment")
	val examples = rootDir.resolve("docker/pa-setup/examples")
	systemProperty("tm1.paEnvironment.fixtures", fixtures.path)
	systemProperty("tm1.paEnvironment.examples", examples.path)
	inputs.dir(fixtures).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.dir(examples).withPathSensitivity(PathSensitivity.RELATIVE)
	// ContainerConformanceTest: the scenarios every language starts, and where each writes what
	// Docker got, for scripts/compare-containers.py
	val scenarios = rootDir.resolve("testcontainers-conformance/scenarios")
	systemProperty("tm1.containerConformance.scenarios", scenarios.path)
	systemProperty("tm1.containerConformance.output", rootDir.resolve("build/container-conformance").path)
	inputs.dir(scenarios).withPathSensitivity(PathSensitivity.RELATIVE)
}

// The container conformance tests on their own, for ./gradlew containerConformance: test runs them
// too when a TM1 is configured, among the rest. Never up to date: Gradle doesn't know Docker and
// the TM1 it's given are inputs.
tasks.register<Test>("containerConformance") {
	group = "verification"
	description = "Starts the container conformance scenarios and records what Docker got (ContainerConformanceTest)."
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	filter {
		includeTestsMatching("*ContainerConformanceTest")
	}
	outputs.upToDateWhen { false }
	dependsOn(":containerConformancePrepare")
}

// The default image tag follows the library version, so record it in the jar.
// PawContainer runs docker/paw/paw-local.sh in its runner container, so ship it too.
tasks.processResources {
	from(rootProject.file("docker/paw/paw-local.sh")) {
		into("io/skipcheck/tm1/testcontainers")
	}
	val version = project.version.toString()
	inputs.property("version", version)
	filesMatching("**/tm1-testcontainers.properties") {
		expand("version" to version)
	}
}

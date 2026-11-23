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

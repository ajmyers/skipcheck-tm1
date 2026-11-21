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
	// Tm1Version is part of this module's API (Tm1ServerContainer.withExpectedVersion)
	api(project(":tm1-client"))
	api(libs.testcontainers)
	// @RequiresTm1 is a JUnit Jupiter extension
	api(platform(libs.junit.bom))
	api(libs.junit.jupiter.api)
}

// The default image tag follows the library version, so record it in the jar
tasks.processResources {
	val version = project.version.toString()
	inputs.property("version", version)
	filesMatching("**/tm1-testcontainers.properties") {
		expand("version" to version)
	}
}

// SPDX-License-Identifier: Apache-2.0

plugins {
	id("tm1.java-library")
}

description = "Spring Boot auto-configuration for the IBM Planning Analytics (TM1) Java client"

mavenPublishing {
	pom {
		name = "TM1 / IBM Planning Analytics Spring Boot Starter"
		description = project.description
	}
}

dependencies {
	api(project(":tm1-client"))
	api(libs.spring.boot.starter)
	annotationProcessor(libs.spring.boot.configuration.processor)

	// Boot's connector builder, for SSL bundles and connect timeouts
	implementation(libs.spring.boot.http.client)
	// Pool metrics, when the application uses Micrometer
	compileOnly(libs.micrometer.core)

	testImplementation(libs.spring.boot.test)
	testImplementation(libs.spring.boot.webclient)
	testImplementation(libs.micrometer.core)
	// Tm1SessionPoolMetricsTest: Prometheus refuses one meter name with different tag keys
	testImplementation(libs.micrometer.registry.prometheus)
	// ArchitectureTest: rules over the compiled starter
	testImplementation(libs.archunit)
	// HelloTm1ApplicationTm1Test: runs the Getting started application against a real TM1, and records
	// what it prints for the docs with tm1-client's ExampleOutput
	testImplementation(project(":tm1-testcontainers"))
	testImplementation(testFixtures(project(":tm1-client")))
}

// The examples (src/examples) on the tests' classpath, for HelloTm1ApplicationTm1Test
sourceSets.test {
	val examples = sourceSets["examples"].output
	compileClasspath += examples
	runtimeClasspath += examples
}

// Where ExampleOutput reads the examples and writes what they printed (api-site/example-output)
tasks.withType<Test>().configureEach {
	systemProperty("tm1.repo", rootDir.path)
}

// GettingStartedPropertiesTest reads the Getting started's application.yml files from the examples
// (not the test classpath), so declare it: an edit to that file alone must rerun the test rather than
// come back UP-TO-DATE or from the build cache
tasks.named<Test>("test") {
	inputs.files("src/examples/java/examples/application.yml", "src/examples/java/examples/application-servers.yml")
		.withPathSensitivity(PathSensitivity.RELATIVE)
		.withPropertyName("gettingStartedProperties")
}

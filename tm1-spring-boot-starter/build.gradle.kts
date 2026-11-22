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
	// ArchitectureTest: rules over the compiled starter
	testImplementation(libs.archunit)
}

// GettingStartedPropertiesTest reads the Getting started's application.yml from the examples
// (not the test classpath), so declare it: an edit to that file alone must rerun the test rather than
// come back UP-TO-DATE or from the build cache
tasks.named<Test>("test") {
	inputs.file("src/examples/java/examples/application.yml")
		.withPathSensitivity(PathSensitivity.RELATIVE)
		.withPropertyName("gettingStartedProperties")
}

plugins {
	id("tm1.java-library")
}

description = "Java client for the IBM Planning Analytics (TM1) REST API"

mavenPublishing {
	pom {
		name = "TM1 / IBM Planning Analytics Java Client"
		description = project.description
	}
}

dependencies {
	// RestClient is part of the public API (Tm1Client.Builder accepts one)
	api(libs.spring.web)
	// Jackson 3: JsonNode appears in the API (ODataError), and payloads are decoded with it
	api(libs.jackson.databind)

	// End-to-end tests against a real TM1 (skipped when none is configured)
	testImplementation(project(":tm1-testcontainers"))
	testImplementation(libs.spring.test)
	testImplementation(libs.hamcrest)
}

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
	// The API is reactive: WebClient, Reactor's Mono and Flux, and Spring's DataBuffer
	api(libs.spring.webflux)
	// Jackson 3: payloads are decoded with it, and JsonNode appears in the API
	api(libs.jackson.databind)

	// End-to-end tests against a real TM1 (skipped when none is configured)
	testImplementation(project(":tm1-testcontainers"))
	testImplementation(libs.spring.test)
	testImplementation(libs.reactor.test)
	testImplementation(libs.hamcrest)
}

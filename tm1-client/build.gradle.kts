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
	implementation(libs.jackson.databind)

	testImplementation(libs.spring.test)
	testImplementation(libs.hamcrest)
}

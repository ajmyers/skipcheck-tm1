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
}

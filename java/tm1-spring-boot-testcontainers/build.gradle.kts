plugins {
	id("tm1.java-library")
}

description = "Spring Boot @ServiceConnection support for IBM Planning Analytics (TM1) Testcontainers"

mavenPublishing {
	pom {
		name = "TM1 / IBM Planning Analytics Spring Boot Testcontainers"
		description = project.description
	}
}

dependencies {
	// Nullness annotations in the API (@NullMarked packages, @Nullable), as Spring 7 uses
	api(libs.jspecify)
	api(project(":tm1-spring-boot-starter"))
	api(project(":tm1-testcontainers"))
	api(libs.spring.boot.testcontainers)

	// The examples show a @SpringBootTest and a @TestConfiguration
	"examplesImplementation"(libs.spring.boot.test)

	testImplementation(libs.spring.boot.test)
	testImplementation(libs.spring.test)
}

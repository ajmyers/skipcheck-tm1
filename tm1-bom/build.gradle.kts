plugins {
	`java-platform`
	id("tm1.publishing")
}

description = "Bill of materials for the IBM Planning Analytics (TM1) Java libraries"

mavenPublishing {
	pom {
		name = "TM1 / IBM Planning Analytics BOM"
		description = project.description
	}
}

dependencies {
	constraints {
		api(project(":tm1-csdl"))
		api(project(":tm1-client"))
		api(project(":tm1-spring-boot-starter"))
		api(project(":tm1-spring-boot-testcontainers"))
		api(project(":tm1-testcontainers"))
	}
}

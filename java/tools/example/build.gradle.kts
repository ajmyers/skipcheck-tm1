plugins {
	id("tm1.spring-boot-app")
}

description = "Minimal tool showing tm1-spring-boot-starter in use"

dependencies {
	implementation(project(":tm1-spring-boot-starter"))
}

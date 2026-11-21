// A runnable Spring Boot tool (not published)
plugins {
	id("tm1.java-conventions")
	id("org.springframework.boot")
	id("io.spring.dependency-management")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	// Gradle doesn't pass a TTY through, so Spring can't auto-detect color support
	systemProperty("spring.output.ansi.enabled", "always")
}

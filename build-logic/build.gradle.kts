plugins {
	`kotlin-dsl`
}

dependencies {
	implementation(libs.gradle.plugin.spring.boot)
	implementation(libs.gradle.plugin.spring.dependency.management)
	implementation(libs.gradle.plugin.maven.publish)
	implementation(libs.gradle.plugin.freefair.lombok)
	implementation(libs.gradle.plugin.errorprone)
	// The formatter itself, not its Gradle plugin, whose tasks don't support the configuration cache
	implementation(libs.spring.javaformat.formatter)
}

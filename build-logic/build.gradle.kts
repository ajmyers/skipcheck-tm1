plugins {
	`kotlin-dsl`
}

dependencies {
	implementation(libs.gradle.plugin.spring.boot)
	implementation(libs.gradle.plugin.spring.dependency.management)
	implementation(libs.gradle.plugin.maven.publish)
}

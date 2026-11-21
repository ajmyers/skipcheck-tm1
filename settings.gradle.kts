pluginManagement {
	// Convention plugins shared by every subproject (see build-logic/)
	includeBuild("build-logic")
	repositories {
		gradlePluginPortal()
		mavenCentral()
	}
}

dependencyResolutionManagement {
	repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
	repositories {
		mavenCentral()
	}
}

rootProject.name = "tm1-pa-developer-tools"

// Published libraries (Maven Central)
include("tm1-client")
include("tm1-spring-boot-starter")
include("tm1-spring-boot-testcontainers")
include("tm1-testcontainers")
include("tm1-bom")

// Tools built on the libraries (not published)
include("tools:example")

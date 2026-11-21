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

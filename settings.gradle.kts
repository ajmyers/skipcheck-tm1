// SPDX-License-Identifier: Apache-2.0

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

rootProject.name = "skipcheck-tm1"

// Published libraries (Maven Central)
include("tm1-csdl")

// Build tools (not published)
include("tm1-codegen")

include("tm1-client")
include("tm1-spring-boot-starter")
include("tm1-spring-boot-testcontainers")
include("tm1-testcontainers")
include("tm1-bom")

// Tools built on the libraries (not published)
include("tools:example")

// The Java modules live in java/, beside python/, dotnet/ and typescript/. Their project names
// stay as they were, so tasks are still :tm1-client:test.
rootProject.children.forEach { it.projectDir = file("java/${it.name}") }
project(":tools:example").projectDir = file("java/tools/example")

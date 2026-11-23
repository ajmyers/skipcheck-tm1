// SPDX-License-Identifier: Apache-2.0

dependencyResolutionManagement {
	repositories {
		gradlePluginPortal()
		mavenCentral()
	}
	versionCatalogs {
		create("libs") {
			from(files("../gradle/libs.versions.toml"))
		}
	}
}

rootProject.name = "build-logic"

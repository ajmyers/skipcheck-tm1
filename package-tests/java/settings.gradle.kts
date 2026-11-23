// SPDX-License-Identifier: Apache-2.0

// A build of its own, not part of the repository's: an application that takes the libraries from
// a Maven repository, as one that got them from Maven Central would (see ../README.md). The
// io.skipcheck modules come only from what was just published to build/packages/maven.
rootProject.name = "skipcheck-package-test"

dependencyResolutionManagement {
	repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
	repositories {
		maven {
			url = uri("../../build/packages/maven")
			content {
				includeGroup("io.skipcheck")
			}
		}
		mavenCentral {
			content {
				excludeGroup("io.skipcheck")
			}
		}
	}
}

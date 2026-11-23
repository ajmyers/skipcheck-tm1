// SPDX-License-Identifier: Apache-2.0

// Maven Central publishing via the Central Portal (https://central.sonatype.com).
// Modules set pom name/description; everything shared lives here.
//
// Credentials (~/.gradle/gradle.properties or ORG_GRADLE_PROJECT_* env vars in CI):
//   mavenCentralUsername / mavenCentralPassword   - Central Portal user token
//   signingInMemoryKey / signingInMemoryKeyPassword - ASCII-armored GPG key
plugins {
	id("com.vanniktech.maven.publish")
}

val githubRepo = providers.gradleProperty("githubRepo").get()

mavenPublishing {
	publishToMavenCentral()

	// Signing is mandatory for Central but shouldn't block publishToMavenLocal
	if (providers.gradleProperty("signingInMemoryKey").isPresent) {
		signAllPublications()
	}

	pom {
		url = "https://github.com/$githubRepo"
		inceptionYear = "2026"
		licenses {
			license {
				name = "Apache-2.0"
				url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
				distribution = "repo"
			}
		}
		developers {
			developer {
				id = "ajmyers"
				name = "Andrew Myers"
				url = "https://ajmyers.net"
			}
		}
		scm {
			url = "https://github.com/$githubRepo"
			connection = "scm:git:https://github.com/$githubRepo.git"
			developerConnection = "scm:git:ssh://git@github.com/$githubRepo.git"
		}
		issueManagement {
			system = "GitHub"
			url = "https://github.com/$githubRepo/issues"
		}
	}
}

// A repository in the build folder (build/packages/maven), for the package test: it publishes
// every module there as a release would publish it to Central, then builds an application that
// takes them from it (package-tests/java, ./gradlew packageTestJava).
publishing {
	repositories {
		maven {
			name = "packageTest"
			url = uri(rootProject.layout.buildDirectory.dir("packages/maven"))
		}
	}
}

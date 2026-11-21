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
				name = "GNU Affero General Public License v3.0 only"
				url = "https://www.gnu.org/licenses/agpl-3.0.txt"
				distribution = "repo"
			}
			license {
				name = "Commercial license (for use without AGPL obligations)"
				url = "https://github.com/$githubRepo/blob/master/COMMERCIAL-LICENSE.md"
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

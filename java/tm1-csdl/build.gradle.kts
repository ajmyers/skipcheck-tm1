// SPDX-License-Identifier: Apache-2.0

plugins {
	id("tm1.java-library")
}

description = "A reader for OData CSDL XML (\$metadata), used by the IBM Planning Analytics (TM1) Java client"

mavenPublishing {
	pom {
		name = "TM1 / IBM Planning Analytics CSDL Reader"
		description = project.description
	}
}

dependencies {
	// Nullness annotations in the API (@NullMarked packages, @Nullable), as Spring 7 uses
	api(libs.jspecify)
}

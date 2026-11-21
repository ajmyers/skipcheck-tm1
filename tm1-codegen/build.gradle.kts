plugins {
	id("tm1.java-conventions")
}

description = "Generates the TM1 client's model from the committed \$metadata snapshot (build tool, not published)"

dependencies {
	implementation(project(":tm1-csdl"))
	implementation(libs.javapoet)

	// The generated sources compile against these; the tests compile them
	testImplementation(libs.jackson.databind)
	testImplementation(libs.jspecify)
}

tasks.test {
	// The snapshot the tests generate from
	systemProperty("tm1.snapshot", rootDir.resolve("tm1-client/src/model/metadata/tm1-schema-11.8.35.xml").path)
}

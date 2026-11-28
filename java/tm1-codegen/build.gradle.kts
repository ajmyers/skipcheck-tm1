// SPDX-License-Identifier: Apache-2.0

plugins {
	id("tm1.java-conventions")
}

description = "Generates the TM1 client's model from the committed \$metadata snapshot, and exports the language-neutral API spec (build tool, not published)"

dependencies {
	implementation(project(":tm1-csdl"))
	implementation(libs.javapoet)
	// Reads spec/tm1-overlay.json and writes spec/tm1-api.json
	implementation(libs.jackson.databind)

	// The generated sources compile against these; the tests compile them
	testImplementation(libs.jspecify)
	// ODataProperty and ODataLiteral, which the generated sources use
	testImplementation(project(":tm1-client"))
}

// The overlay (what TM1 does differently from $metadata) is shared by every client's generator:
// it lives in spec/, and the Java generator reads it from the classpath
sourceSets.main {
	resources.srcDir(rootDir.resolve("spec"))
	resources.include("tm1-overlay.json")
}

val metadataDir = rootDir.resolve("java/tm1-client/src/model/metadata")
val snapshots = listOf(
	metadataDir.resolve("tm1-schema-11.8.35.xml"),
	metadataDir.resolve("tm1-schema-12.6.0.xml"),
)
val spec = rootDir.resolve("spec/tm1-api.json")

tasks.test {
	// The snapshots the tests generate from, and the committed spec they compare with
	systemProperty("tm1.snapshots", snapshots.joinToString(",") { it.path })
	systemProperty("tm1.spec", spec.path)
	inputs.files(snapshots).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.files(fileTree(metadataDir) { include("ibm.*.xml", "tm1-probe-*.json") })
		.withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.file(spec).withPathSensitivity(PathSensitivity.RELATIVE)
}

// spec/tm1-api.json is committed, so clients in other languages build without a JVM;
// ApiSpecTest fails when it's out of date
tasks.register<JavaExec>("exportSpec") {
	group = "build"
	description = "Exports the language-neutral API spec (spec/tm1-api.json) from the \$metadata snapshots."
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass = "io.skipcheck.tm1.codegen.Tm1Codegen"
	args(listOf("spec") + snapshots.map { it.path } + listOf(spec.path))
	inputs.files(snapshots)
	inputs.files(fileTree(metadataDir) { include("ibm.*.xml", "tm1-probe-*.json") })
	outputs.file(spec)
}

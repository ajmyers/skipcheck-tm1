// SPDX-License-Identifier: Apache-2.0

plugins {
	id("tm1.java-library")
	// ExampleOutput (src/testFixtures), which records what the examples print, for this module's
	// tests and the other modules'
	`java-test-fixtures`
}

description = "Java client for the IBM Planning Analytics (TM1) REST API"

mavenPublishing {
	pom {
		name = "TM1 / IBM Planning Analytics Java Client"
		description = project.description
	}
}

dependencies {
	// The $metadata reader: Tm1Capabilities exposes its CsdlDocument
	api(project(":tm1-csdl"))
	// Nullness annotations in the API (@NullMarked packages, @Nullable), as Spring 7 uses
	api(libs.jspecify)
	// The API is reactive: WebClient, Reactor's Mono and Flux, and Spring's DataBuffer
	api(libs.spring.webflux)
	// The default HTTP connector: pool control and per-request response timeouts
	api(libs.reactor.netty.http)
	// ReactorClientHttpConnector implements SmartLifecycle, but spring-webflux declares spring-context
	// optional: without it, a standalone client fails at runtime with NoClassDefFoundError
	implementation(libs.spring.context)
	// Jackson 3: payloads are decoded with it, and JsonNode appears in the API
	api(libs.jackson.databind)
	// Logging through SLF4J (Lombok's @Slf4j); the application picks the backend
	api(libs.slf4j.api)
	// Metrics and traces through Micrometer observations; Spring brings it in, but we use it directly
	api(libs.micrometer.observation)

	// End-to-end tests against a real TM1 (skipped when none is configured)
	testImplementation(project(":tm1-testcontainers"))
	testImplementation(libs.spring.test)
	testImplementation(libs.reactor.test)
	testImplementation(libs.hamcrest)
	testImplementation(libs.micrometer.observation.test)
	// ArchitectureTest: rules over the compiled library code
	testImplementation(libs.archunit)
	testRuntimeOnly(libs.slf4j.simple)
}

// The $metadata snapshot (src/model/metadata) is the generator's input, and tests read it from
// the classpath as /metadata/tm1-schema-<version>.xml
sourceSets.test {
	resources.srcDir("src/model")
}

// The model (io.skipcheck.tm1.client.model) is generated from the $metadata snapshot at build
// time by tm1-codegen, into build/generated: never committed, never edited. A TM1
// upgrade shows up as a diff of the snapshot, not of the generated code.
val codegen = configurations.create("codegen") {
	isCanBeConsumed = false
}
dependencies {
	codegen(project(":tm1-codegen"))
}
val modelSources = layout.buildDirectory.dir("generated/sources/tm1-model/java/main")
val snapshot = layout.projectDirectory.file("src/model/metadata/tm1-schema-11.8.35.xml")
val generateModel = tasks.register<JavaExec>("generateModel") {
	group = "build"
	description = "Generates the model from the \$metadata snapshot."
	classpath = codegen
	mainClass = "io.skipcheck.tm1.codegen.Tm1Codegen"
	inputs.file(snapshot).withPathSensitivity(PathSensitivity.RELATIVE)
	// What a probe found that the snapshot doesn't declare (java/tools/tm1-probe)
	inputs.files(fileTree(snapshot.asFile.parentFile) { include("tm1-probe-*.json") }).withPathSensitivity(PathSensitivity.RELATIVE)
	outputs.dir(modelSources)
	outputs.cacheIf { true }
	args(snapshot.asFile.path, modelSources.get().asFile.path)
	// Start from an empty folder: a type or package that's gone from the model mustn't stay
	// behind from an earlier run, to be compiled and cached as if it were still generated
	val output = modelSources.get().asFile
	doFirst {
		output.deleteRecursively()
	}
}
sourceSets.main {
	java.srcDir(generateModel)
}

// The test fixtures are the tests' own: none of their variants (classes, sources) is published
val javaComponent = components["java"] as AdhocComponentWithVariants
afterEvaluate {
	configurations.matching { it.name.startsWith("testFixtures") && it.name.endsWith("Elements") }.forEach { fixtures ->
		javaComponent.withVariantsFromConfiguration(fixtures) { skip() }
	}
}

// The tests run the walkthroughs' examples (src/examples) against TM1, and record what they print
// for the docs (WalkthroughExamplesTm1Test), so the examples are on the tests' classpath
sourceSets.test {
	val examples = sourceSets["examples"].output
	compileClasspath += examples
	runtimeClasspath += examples
}

// The language-neutral API spec, which ApiSpecInventoryTest holds to this client's coverage gate
tasks.withType<Test>().configureEach {
	val spec = rootDir.resolve("spec/tm1-api.json")
	systemProperty("tm1.spec", spec.path)
	// Where ExampleOutput reads the examples and writes what they printed (api-site/example-output)
	systemProperty("tm1.repo", rootDir.path)
	inputs.file(spec).withPathSensitivity(PathSensitivity.RELATIVE)
	// Conformance fixtures (spec/conformance/calls): the tests check them, or with
	// -PrecordConformance rewrite them from what the tests record
	// (spec/conformance/calls), and the builder manifest (spec/conformance/builders.json) and
	// the writes (writes.json) every client is held to
	val conformance = rootDir.resolve("spec/conformance")
	val record = providers.gradleProperty("recordConformance").isPresent
	systemProperty("tm1.conformance.record", record)
	inputs.dir(conformance).withPathSensitivity(PathSensitivity.RELATIVE).optional()
	// The probe files, which ProbedWritesCoverageTest holds the live scenarios to
	inputs.files(fileTree(rootDir.resolve("java/tm1-client/src/model/metadata")) { include("tm1-probe-*.json") })
		.withPathSensitivity(PathSensitivity.RELATIVE)
	if (record) {
		outputs.upToDateWhen { false }
	}
	// The TM1 versions doc, which MetadataReleasesTest holds to the release record and the
	// snapshots, or with TM1_UPDATE_VERSIONS_DOC=true rewrites
	inputs.files(rootDir.resolve("spec/tm1-versions.md")).withPathSensitivity(PathSensitivity.RELATIVE)
	if (providers.environmentVariable("TM1_UPDATE_VERSIONS_DOC").orNull == "true") {
		outputs.upToDateWhen { false }
	}
}

// The tests against a real TM1 on their own (the *Tm1Test classes), for ./gradlew liveTest: test
// runs them too when a TM1 is configured, among the rest. Never up to date: Gradle doesn't know
// the TM1 it talks to is an input.
tasks.register<Test>("liveTest") {
	group = "verification"
	description = "Runs the tests against a real TM1 (the *Tm1Test classes)."
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	filter {
		includeTestsMatching("*Tm1Test")
	}
	outputs.upToDateWhen { false }
	dependsOn(":liveTestPrepare")
}

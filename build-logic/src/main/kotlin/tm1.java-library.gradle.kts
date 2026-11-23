// SPDX-License-Identifier: Apache-2.0

// A library published to Maven Central
plugins {
	id("tm1.java-conventions")
	`java-library`
	id("tm1.publishing")
}

// Build with the latest JDK, but emit bytecode for Spring Framework 7's baseline
// so consumers on Java 17+ can use the library
tasks.withType<JavaCompile>().configureEach {
	options.release = 17
}

// Every jar (classes, sources, Javadoc) carries the license and its notice in META-INF: Apache 2.0
// asks that whoever gets the library gets them too
tasks.withType<Jar>().configureEach {
	metaInf {
		from(layout.settingsDirectory.file("LICENSE"), layout.settingsDirectory.file("NOTICE"))
	}
}

// The public API's Javadoc is part of the deliverable: every public class,
// method and record component must be documented, any doclint warning fails, and check runs it,
// so ./gradlew build catches a gap rather than the release.
tasks.withType<Javadoc>().configureEach {
	(options as StandardJavadocDocletOptions).apply {
		addStringOption("Xdoclint:all", "-quiet")
		addBooleanOption("Werror", true)
		// Report every gap, not just the first 100
		addStringOption("Xmaxwarns", "10000")
		links("https://docs.spring.io/spring-framework/docs/current/javadoc-api/")
		// Colours the code in {@snippet} tags and <pre><code> blocks (JDK 25+, with a bundled highlight.js)
		addBooleanOption("-syntax-highlight", true)
	}
}

tasks.named("check") {
	dependsOn(tasks.named("javadoc"))
}

// Usage examples for the Javadoc, in src/examples/java: real code, compiled against the library on
// every build, so an API change that breaks an example fails the build rather than shipping it.
// The Javadoc quotes marked regions of them with {@snippet class=... region=...}. Compiled, not
// run: they need a TM1 server (CONTRIBUTING.md, "Usage examples").
val main = sourceSets.named("main")
val examples = sourceSets.create("examples") {
	compileClasspath += main.get().output + main.get().compileClasspath
}
tasks.named("check") {
	dependsOn(tasks.named(examples.compileJavaTaskName))
}
// A module's overview page (src/main/javadoc/overview.html) is the first page of its Javadoc:
// where its Getting started goes. {{version}} in it becomes the version being built, so the
// dependency it shows can be copied as is.
val overviewSource = layout.projectDirectory.file("src/main/javadoc/overview.html")
val overview = layout.buildDirectory.file("javadoc-overview/overview.html")
val javadocOverview = tasks.register<Copy>("javadocOverview") {
	val version = project.version.toString()
	inputs.property("version", version)
	from(overviewSource)
	into(overview.map { it.asFile.parentFile })
	filter { line: String -> line.replace("{{version}}", version) }
}
tasks.withType<Javadoc>().configureEach {
	if (overviewSource.asFile.isFile) {
		dependsOn(javadocOverview)
		inputs.file(overview)
		(options as StandardJavadocDocletOptions).overview = overview.get().asFile.path
	}
}
val examplesDir = layout.projectDirectory.dir("src/examples/java")
tasks.withType<Javadoc>().configureEach {
	if (examplesDir.asFile.isDirectory) {
		inputs.dir(examplesDir)
		(options as StandardJavadocDocletOptions).addPathOption("-snippet-path").value = listOf(examplesDir.asFile)
	}
}

// The same Javadoc, themed for skipcheck.io/<module>/api/java/ (api-site/README.md). A separate
// task, so the javadoc jar on Maven Central stays plain: the theme imports the site's styles from
// the same origin, which only works there.
val apiSiteBaseUrl = providers.gradleProperty("apiSiteBaseUrl")
val apiSiteTheme = layout.settingsDirectory.file("api-site/theme/javadoc-theme.css")
tasks.register<Javadoc>("apiSiteJavadoc") {
	group = "documentation"
	description = "Generates the Javadoc themed for the API docs site."
	val javadoc = tasks.named<Javadoc>("javadoc").get()
	// The javadoc task's sources include Lombok's delombok output, so generated methods are documented
	source = javadoc.source
	classpath = javadoc.classpath
	setDestinationDir(layout.buildDirectory.dir("docs/api-site").get().asFile)
	inputs.file(apiSiteTheme)
	(options as StandardJavadocDocletOptions).apply {
		windowTitle = "${project.name} API"
		docTitle = "${project.name} ${project.version} API"
		addStringOption("-add-stylesheet", apiSiteTheme.asFile.path)
		// Only the latest version is published there; javadoc.io serves every release from Maven Central
		bottom = "Older versions: <a href=\"https://javadoc.io/doc/${project.group}/${project.name}\">javadoc.io</a>"
		// Link to the other modules' pages on the site, using the element-list files built here rather
		// than the live site's, which may not have this version yet
		configurations.getByName("api").dependencies.withType<ProjectDependency>().forEach { dependency ->
			val other = project(dependency.path)
			dependsOn("${dependency.path}:apiSiteJavadoc")
			linksOffline("${apiSiteBaseUrl.get()}${other.name}/api/java/",
					other.layout.buildDirectory.dir("docs/api-site").get().asFile.path)
		}
	}
}

// The library's API as JSON, for the docs site's reference pages (api-site/README.md): the same
// sources as the Javadoc, read by the doclet in java/tools/docs-doclet instead of written as HTML.
val docsDoclet = configurations.create("docsDoclet") {
	isCanBeConsumed = false
	isCanBeResolved = true
}
dependencies {
	docsDoclet(project(":tools:docs-doclet"))
}
tasks.register<Javadoc>("docsModel") {
	group = "documentation"
	description = "Writes the library's API as JSON for the docs site."
	val javadoc = tasks.named<Javadoc>("javadoc").get()
	source = javadoc.source
	classpath = javadoc.classpath
	val out = layout.buildDirectory.file("docs/model.json")
	outputs.file(out)
	setDestinationDir(layout.buildDirectory.dir("docs/model").get().asFile)
	inputs.files(docsDoclet)
	options.doclet = "io.skipcheck.tm1.docs.ModelDoclet"
	options.docletpath = docsDoclet.files.toList()
	(options as CoreJavadocOptions).addStringOption("o", out.get().asFile.path)
}

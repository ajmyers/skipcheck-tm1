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

// The public API's Javadoc is part of the deliverable (plan §13 item 14): every public class,
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

// The same Javadoc, themed for ajmyers.net/projects/<module>/api/ (api-site/README.md). A separate
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
			linksOffline("${apiSiteBaseUrl.get()}${other.name}/api/",
					other.layout.buildDirectory.dir("docs/api-site").get().asFile.path)
		}
	}
}

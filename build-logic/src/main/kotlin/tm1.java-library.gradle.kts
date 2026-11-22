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


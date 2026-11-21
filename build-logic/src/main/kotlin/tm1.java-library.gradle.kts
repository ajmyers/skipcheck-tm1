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

tasks.withType<Javadoc>().configureEach {
	(options as StandardJavadocDocletOptions).apply {
		addStringOption("Xdoclint:all,-missing", "-quiet")
		links("https://docs.spring.io/spring-framework/docs/current/javadoc-api/")
	}
}

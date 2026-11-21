// A library published to Maven Central
plugins {
	id("tm1.java-conventions")
	`java-library`
	id("tm1.publishing")
}

// Build with the latest JDK, but emit Java 21 bytecode (LTS, and what most TM1 shops run)
// so consumers on Java 21+ can use the library
tasks.withType<JavaCompile>().configureEach {
	options.release = 21
}

tasks.withType<Javadoc>().configureEach {
	(options as StandardJavadocDocletOptions).apply {
		addStringOption("Xdoclint:all,-missing", "-quiet")
		links("https://docs.spring.io/spring-framework/docs/current/javadoc-api/")
	}
}

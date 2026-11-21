package net.ajmyers.tm1.testcontainers.examples;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;

/**
 * <b>The everyday case: one shared TM1 server for all your tests.</b>
 *
 * <p>{@code @RequiresTm1} skips the class when there's no Docker or no TM1 configured, and
 * otherwise injects the shared container wherever you declare a {@link Tm1ServerContainer}
 * parameter. It's started once per test JVM, on first use, and shared by every test class, so
 * most test classes pay no startup cost at all.
 *
 * <p>Because the server is shared, give the objects you create unique names and delete them
 * afterwards.
 */
@RequiresTm1
class SharedServerExampleTest {

	// 1. In a static @BeforeAll: set up something expensive once per class, like a client
	private static Tm1Rest tm1;

	@BeforeAll
	static void connect(Tm1ServerContainer server) {
		tm1 = new Tm1Rest(server);
	}

	@AfterAll
	static void disconnect() {
		tm1.close();
	}

	private final Tm1ServerContainer server;

	// 2. In the constructor: available to every test in the class
	SharedServerExampleTest(Tm1ServerContainer server) {
		this.server = server;
	}

	@Test
	void usesTheClientSetUpOnce() {
		assertThat(tm1.modelCubeCount()).isZero();   // a new, empty database
	}

	// 3. As a test method parameter: the simplest
	@Test
	void getsTheServerAsAParameter(Tm1ServerContainer injected) {
		assertThat(injected).isSameAs(server);       // always the same shared server
		assertThat(injected.getRestUrl()).endsWith("/api/v1/");
		assertThat(injected.getUsername()).isEqualTo("admin");
		assertThat(injected.getPassword()).isEmpty();
	}
}

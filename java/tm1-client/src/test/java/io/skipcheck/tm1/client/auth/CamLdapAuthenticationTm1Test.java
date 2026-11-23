// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.error.Tm1AuthenticationException;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.testcontainers.CognosContainer;
import io.skipcheck.tm1.testcontainers.CognosSource;
import io.skipcheck.tm1.testcontainers.RequiresCognos;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;
import reactor.core.publisher.Mono;

/**
 * Logging on to a TM1 in CAM security through Cognos's LDAP namespace: with the password
 * ({@code CAMNamespace}), and with a passport the client gets from Cognos. Cognos takes a
 * couple of minutes to start, so the containers are shared by the class.
 */
@RequiresCognos
class CamLdapAuthenticationTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(60);

	private static final String VIEWER = "CAMID(\"LDAP:u:uid=viewer,ou=people\")";

	private static CognosContainer cognos;

	private static Tm1ServerContainer tm1;

	private static Tm1Client admin;

	@BeforeAll
	@SuppressWarnings("resource") // stopped in stop()
	static void start() {
		cognos = new CognosContainer(CognosSource.discover().orElseThrow()).withUser("admin", "adminpw", "Admins")
			.withUser("viewer", "viewerpw", "Readers");
		tm1 = new Tm1ServerContainer(Tm1Source.discover().orElseThrow()).withCamSecurity(cognos, "admin");
		tm1.start();
		admin = client(Tm1Authenticator.camNamespace("admin", "adminpw", "LDAP"));
	}

	@AfterAll
	static void stop() {
		admin.close().block(WAIT);
		tm1.stop();
		cognos.stop();
	}

	private static Tm1Client client(Tm1Authenticator authenticator) {
		return Tm1Client.builder().baseUrl(tm1.getRestUrl()).authenticator(authenticator).build();
	}

	private static Supplier<Mono<String>> counting(Supplier<Mono<String>> passports, AtomicInteger asked) {
		return () -> Mono.defer(() -> {
			asked.incrementAndGet();
			return passports.get();
		});
	}

	@Test
	void logsOnWithThePassword() {
		Tm1Client viewer = client(Tm1Authenticator.camNamespace("viewer", "viewerpw", "LDAP"));
		try {
			assertThat(viewer.users().activeUser(ODataQuery.none()).block(WAIT).name()).isEqualTo(VIEWER);
		}
		finally {
			viewer.close().block(WAIT);
		}
	}

	@Test
	void logsOnWithAPassportFromCognosAndAFreshOneAfterTm1DropsTheSession() {
		AtomicInteger asked = new AtomicInteger();
		Tm1Client viewer = client(Tm1Authenticator
			.camPassport(counting(CognosPassports.password(cognos.getUrl(), "LDAP", "viewer", "viewerpw"), asked)));
		try {
			assertThat(viewer.users().activeUser(ODataQuery.none()).block(WAIT).name()).isEqualTo(VIEWER);
			long first = viewer.session().sessionId().block(WAIT);
			admin.sessions().close(first).block(WAIT);

			assertThat(viewer.users().activeUser(ODataQuery.none()).block(WAIT).name()).as("logged on again")
				.isEqualTo(VIEWER);
			assertThat(viewer.session().sessionId().block(WAIT)).isNotEqualTo(first);
			assertThat(asked).as("a fresh passport for the new login").hasValue(2);
		}
		finally {
			viewer.close().block(WAIT);
		}
	}

	@Test
	void refusesAWrongPasswordOnceWithoutRetrying() {
		AtomicInteger asked = new AtomicInteger();
		Tm1Client wrong = client(Tm1Authenticator
			.camPassport(counting(CognosPassports.password(cognos.getUrl(), "LDAP", "viewer", "wrong"), asked)));
		try {
			assertThatThrownBy(() -> wrong.users().activeUser(ODataQuery.none()).block(WAIT))
				.isInstanceOf(Tm1AuthenticationException.class);
			assertThatThrownBy(() -> wrong.users().activeUser(ODataQuery.none()).block(WAIT))
				.isInstanceOf(Tm1AuthenticationException.class);
			assertThat(asked).as("Cognos asked once: a refusal isn't retried at once").hasValue(1);
		}
		finally {
			wrong.close().onErrorComplete().block(WAIT);
		}
		Tm1Client wrongNamespace = client(Tm1Authenticator.camNamespace("viewer", "wrong", "LDAP"));
		try {
			assertThatThrownBy(() -> wrongNamespace.users().activeUser(ODataQuery.none()).block(WAIT))
				.isInstanceOf(Tm1AuthenticationException.class);
		}
		finally {
			wrongNamespace.close().onErrorComplete().block(WAIT);
		}
	}

}

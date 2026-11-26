// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.LiveServer;
import io.skipcheck.tm1.client.LiveTm1;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.error.Tm1ApiException;
import io.skipcheck.tm1.client.error.Tm1ErrorKind;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/** Impersonation through {@link Tm1Session}, against a real TM1. */
@LiveTm1
class Tm1SessionImpersonationTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static final Tm1Request<JsonNode> ACTIVE_USER = Tm1Request.get(ODataPath.of("ActiveUser"),
			ODataQuery.none().select("Name"), JsonNode.class);

	private LiveServer server;

	private RawTm1 raw;

	private String admin;

	private String alice;

	@BeforeEach
	void createUser(LiveServer server) {
		this.server = server;
		raw = new RawTm1(server);
		admin = raw.login();
		alice = "zz_alice_" + UUID.randomUUID().toString().substring(0, 6);
		raw.createUser(admin, alice, "a");
	}

	@AfterEach
	void deleteUser() {
		raw.deleteUser(admin, alice);
		raw.close(admin);
	}

	@Test
	void runsAsTheImpersonatedUser() {
		Tm1Session session = session(alice.toUpperCase(Locale.ROOT));
		try {
			assertThat(session.execute(ACTIVE_USER).block(WAIT).get("Name").asString()).isEqualTo(alice);
		}
		finally {
			session.close().block(WAIT);
		}
	}

	@Test
	void runsAsAUserWhoseNameIsOutsideAscii() {
		for (String name : new String[] { alice + "_ë", alice + "_日本" }) {
			raw.createUser(admin, name, "a");
			Tm1Session session = session(name);
			try {
				assertThat(session.execute(ACTIVE_USER).block(WAIT).get("Name").asString()).isEqualTo(name);
			}
			finally {
				session.close().block(WAIT);
				raw.deleteUser(admin, URLEncoder.encode(name, StandardCharsets.UTF_8));
			}
		}
	}

	@Test
	void reportsAnUnknownUser() {
		assertThat(kindOf(session("zz_nobody"))).isEqualTo(Tm1ErrorKind.UNKNOWN_USER);
	}

	@Test
	void reportsThatAdministratorsCantBeImpersonated() {
		assertThat(kindOf(session("Admin"))).isEqualTo(Tm1ErrorKind.IMPERSONATION_REFUSED);
	}

	@Test
	void reportsADisabledUserAsRefusedCredentials() {
		raw.send(raw.request("PATCH", "Users('" + alice + "')").cookie(admin).body("{\"Enabled\":false}"));
		assertThat(kindOf(session(alice))).isEqualTo(Tm1ErrorKind.CREDENTIAL_REJECTED);
	}

	/**
	 * Each refused login leaves a session on TM1; a remembered refusal doesn't add more.
	 */
	@Test
	void doesntLogInAgainWhileARefusalIsRemembered() {
		Tm1Session session = session("zz_nobody");
		assertThat(kindOf(session)).isEqualTo(Tm1ErrorKind.UNKNOWN_USER);
		int after = raw.allSessionCount(admin);
		for (int i = 0; i < 5; i++) {
			assertThat(kindOf(session)).isEqualTo(Tm1ErrorKind.UNKNOWN_USER);
		}
		assertThat(raw.allSessionCount(admin)).isEqualTo(after);
	}

	private Tm1Session session(String user) {
		return Tm1Session.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.impersonate(user)
			.build();
	}

	private static Tm1ErrorKind kindOf(Tm1Session session) {
		Object outcome = session.execute(ACTIVE_USER).map(n -> (Object) n).onErrorResume(e -> Mono.just(e)).block(WAIT);
		assertThat(outcome).isInstanceOf(Tm1ApiException.class);
		return ((Tm1ApiException) outcome).kind();
	}

}

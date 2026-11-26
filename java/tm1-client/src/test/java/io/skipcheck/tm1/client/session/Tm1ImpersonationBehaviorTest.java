// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.LiveServer;
import io.skipcheck.tm1.client.LiveTm1;

/**
 * Pins how TM1 handles {@code TM1-Impersonate}, which the session pool relies on. Over
 * bare HTTP, like {@link Tm1SessionBehaviorTest}. Observed on 11.8.03500.6 with TM1's own
 * security; CAM may behave differently.
 */
@LiveTm1
class Tm1ImpersonationBehaviorTest {

	private RawTm1 tm1;

	private String admin;

	private String alice;

	private String bob;

	@BeforeEach
	void createUsers(LiveServer server) {
		tm1 = new RawTm1(server);
		admin = tm1.login();
		String run = UUID.randomUUID().toString().substring(0, 6);
		alice = "zz_alice_" + run;
		bob = "zz_bob_" + run;
		tm1.createUser(admin, alice, "a");
		tm1.createUser(admin, bob, "b");
	}

	@AfterEach
	void deleteUsers() {
		tm1.deleteUser(admin, alice);
		tm1.deleteUser(admin, bob);
		tm1.close(admin);
	}

	/**
	 * Impersonation is decided at login: the session becomes that user, and the header on
	 * later requests is ignored, whatever it says. So it's a session setting, and one
	 * identity means one session.
	 */
	@Test
	void decidesTheIdentityAtLoginAndIgnoresTheHeaderAfterwards() {
		HttpResponse<String> login = tm1
			.send(tm1.request("GET", "ActiveUser?$select=Name").basic().header("TM1-Impersonate", alice));
		assertThat(login.statusCode()).isEqualTo(200);
		assertThat(RawTm1.json(login).get("Name").asString()).isEqualTo(alice);
		String session = RawTm1.cookie(login).orElseThrow();
		try {
			assertThat(name(tm1.request("GET", "ActiveUser?$select=Name").cookie(session))).isEqualTo(alice);
			assertThat(
					name(tm1.request("GET", "ActiveUser?$select=Name").cookie(session).header("TM1-Impersonate", bob)))
				.as("a different user on a later request")
				.isEqualTo(alice);

			String plain = tm1.login();
			assertThat(
					name(tm1.request("GET", "ActiveUser?$select=Name").cookie(plain).header("TM1-Impersonate", alice)))
				.as("impersonation on a later request of an admin session")
				.isEqualTo("Admin");
			tm1.close(plain);
		}
		finally {
			tm1.close(session);
		}
	}

	/**
	 * It also works when logging in the session a 401 opened, as the client's re-login
	 * does.
	 */
	@Test
	void impersonatesOnALoginThatReusesThe401sSession() {
		String seed = RawTm1.cookie(tm1.send(tm1.request("GET", "ActiveUser?$select=Name"))).orElseThrow();
		try {
			assertThat(name(tm1.request("GET", "ActiveUser?$select=Name")
				.cookie(seed)
				.basic()
				.header("TM1-Impersonate", alice))).isEqualTo(alice);
		}
		finally {
			tm1.close(seed);
		}
	}

	/**
	 * Impersonating a user that doesn't exist is a 400, and a non-administrator trying to
	 * impersonate is a 403. Both bodies are plain text, not OData errors: a status line,
	 * then the message. The client reads such a body as the error message, so the
	 * classifier's message rules apply. Each leaves a session with no user behind
	 * (refused on use, so not a way in as the administrator).
	 */
	@Test
	void refusesUnknownUsersAndNonAdministratorsWithPlainTextErrors() {
		HttpResponse<String> unknown = tm1
			.send(tm1.request("GET", "ActiveUser?$select=Name").basic().header("TM1-Impersonate", "zz_nobody"));
		assertThat(unknown.statusCode()).isEqualTo(400);
		assertThat(unknown.body()).startsWith("Error: Bad Request")
			.contains("The user specified in the impersonation request does not exist.");
		String left = RawTm1.cookie(unknown).orElseThrow();
		assertThat(tm1.send(tm1.request("GET", "ActiveUser?$select=Name").cookie(left)).statusCode())
			.as("the session a refused impersonation leaves")
			.isEqualTo(401);

		HttpResponse<String> nonAdmin = tm1.send(tm1.request("GET", "ActiveUser?$select=Name")
			.header("Authorization", RawTm1.basic(alice, "a"))
			.header("TM1-Impersonate", bob));
		assertThat(nonAdmin.statusCode()).isEqualTo(403);
		assertThat(nonAdmin.body()).startsWith("Error: Forbidden")
			.contains("You need to be a (data-)administrator to be able to impersonate another user.");
	}

	/**
	 * Nobody can impersonate an administrator, not even another administrator, and that
	 * includes the built-in Admin. TM1 itself blocks this escalation, with a 403 that
	 * says so.
	 */
	@Test
	void refusesToImpersonateAnyAdministrator() {
		String otherAdmin = alice + "_adm";
		String dataAdmin = alice + "_data";
		createUser(otherAdmin, "ADMIN");
		createUser(dataAdmin, "DataAdmin");
		try {
			for (String[] attempt : new String[][] { { "admin", "", otherAdmin }, { "admin", "", "Admin" },
					{ dataAdmin, "p", otherAdmin }, { dataAdmin, "p", "Admin" } }) {
				HttpResponse<String> refused = impersonate(attempt[0], attempt[1], attempt[2]);
				assertThat(refused.statusCode()).as("%s impersonating %s", attempt[0], attempt[2]).isEqualTo(403);
				assertThat(refused.body()).contains("You have insufficient rights to impersonate the user specified!");
			}
		}
		finally {
			tm1.deleteUser(admin, otherAdmin);
			tm1.deleteUser(admin, dataAdmin);
		}
	}

	/**
	 * Data administrators can impersonate ordinary users; security and operations
	 * administrators can't, with a different 403 message.
	 */
	@Test
	void letsDataAdministratorsImpersonateButNotOtherAdministratorRoles() {
		String dataAdmin = alice + "_data";
		String securityAdmin = alice + "_sec";
		String operationsAdmin = alice + "_ops";
		createUser(dataAdmin, "DataAdmin");
		createUser(securityAdmin, "SecurityAdmin");
		createUser(operationsAdmin, "OperationsAdmin");
		try {
			HttpResponse<String> allowed = impersonate(dataAdmin, "p", bob);
			assertThat(allowed.statusCode()).isEqualTo(200);
			RawTm1.cookie(allowed).ifPresent(tm1::close);
			for (String role : new String[] { securityAdmin, operationsAdmin }) {
				HttpResponse<String> refused = impersonate(role, "p", bob);
				assertThat(refused.statusCode()).as(role).isEqualTo(403);
				assertThat(refused.body())
					.contains("You need to be a (data-)administrator to be able to impersonate another user.");
			}
		}
		finally {
			tm1.deleteUser(admin, dataAdmin);
			tm1.deleteUser(admin, securityAdmin);
			tm1.deleteUser(admin, operationsAdmin);
		}
	}

	/**
	 * Impersonating a disabled user is a 401 with an empty body: exactly what wrong
	 * credentials look like. The client can't tell the two apart, so it reports both.
	 */
	@Test
	void refusesADisabledUserLikeWrongCredentials() {
		tm1.send(tm1.request("PATCH", "Users('" + bob + "')").cookie(admin).body("{\"Enabled\":false}"));
		HttpResponse<String> refused = impersonate("admin", "", bob);
		assertThat(refused.statusCode()).isEqualTo(401);
		assertThat(refused.body()).isEmpty();
	}

	/**
	 * An empty {@code TM1-Impersonate} means no impersonation: the session runs as the
	 * user who logged in, here the administrator. So the client must never send a blank
	 * name.
	 */
	@Test
	void treatsAnEmptyHeaderAsNoImpersonation() {
		HttpResponse<String> login = impersonate("admin", "", "");
		try {
			assertThat(login.statusCode()).isEqualTo(200);
			assertThat(RawTm1.json(login).get("Name").asString()).isEqualTo("Admin");
		}
		finally {
			RawTm1.cookie(login).ifPresent(tm1::close);
		}
	}

	/**
	 * User names match ignoring case and spaces, as TM1 object names do, so the pool must
	 * compare identities the same way, or one user would get two sessions.
	 */
	@Test
	void matchesUserNamesIgnoringCaseAndSpaces() {
		for (String written : new String[] { alice.toUpperCase(Locale.ROOT), alice.replace("_", "_ ") }) {
			HttpResponse<String> login = impersonate("admin", "", written);
			try {
				assertThat(login.statusCode()).as(written).isEqualTo(200);
				assertThat(RawTm1.json(login).get("Name").asString()).as(written).isEqualTo(alice);
			}
			finally {
				RawTm1.cookie(login).ifPresent(tm1::close);
			}
		}
	}

	/**
	 * A user whose name has characters outside ASCII is impersonated when the header
	 * carries the name as UTF-8, and only then: as ISO 8859-1 bytes or percent-encoded,
	 * TM1 reports the user unknown. Sent over a bare socket, as the JDK's
	 * {@code HttpClient} writes such a character as {@code ?}. The client sends UTF-8
	 * ({@code HeaderText}).
	 */
	@Test
	void impersonatesUsersWithNonAsciiNamesOnlyAsUtf8() {
		String accented = alice + "_ë";
		tm1.createUser(admin, accented, "p");
		try {
			RawTm1.Bytes impersonated = impersonate(accented.getBytes(StandardCharsets.UTF_8));
			impersonated.cookie().ifPresent(tm1::close);
			assertThat(impersonated.status()).isEqualTo(200);
			assertThat(impersonated.text()).contains("\"Name\":\"" + accented + "\"");

			byte[] percentEncoded = URLEncoder.encode(accented, StandardCharsets.UTF_8)
				.getBytes(StandardCharsets.US_ASCII);
			for (byte[] written : new byte[][] { accented.getBytes(StandardCharsets.ISO_8859_1), percentEncoded }) {
				RawTm1.Bytes refused = impersonate(written);
				assertThat(refused.status()).isEqualTo(400);
				assertThat(refused.text()).contains("The user specified in the impersonation request does not exist.");
			}
		}
		finally {
			tm1.deleteUser(admin, URLEncoder.encode(accented, StandardCharsets.UTF_8));
		}
	}

	private RawTm1.Bytes impersonate(byte[] target) {
		return tm1.getWithHeaderBytes("ActiveUser?$select=Name", RawTm1.basic("admin", ""), "TM1-Impersonate", target);
	}

	private HttpResponse<String> impersonate(String user, String password, String target) {
		return tm1.send(tm1.request("GET", "ActiveUser?$select=Name")
			.header("Authorization", RawTm1.basic(user, password))
			.header("TM1-Impersonate", target));
	}

	private void createUser(String name, String group) {
		tm1.send(tm1.request("POST", "Users")
			.cookie(admin)
			.body("{\"Name\":\"" + name + "\",\"Password\":\"p\",\"Groups@odata.bind\":[\"Groups('" + group
					+ "')\"]}"));
	}

	private String name(RawTm1.Request request) {
		return RawTm1.json(tm1.send(request)).get("Name").asString();
	}

}

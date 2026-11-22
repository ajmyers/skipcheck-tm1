package net.ajmyers.tm1.client.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import net.ajmyers.tm1.client.error.Tm1AuthenticationException;
import net.ajmyers.tm1.client.error.Tm1ErrorKind;
import reactor.core.publisher.Mono;

/** CognosPassports against a stand-in for Cognos's session resource. */
class CognosPassportsTest {

	private static final Duration WAIT = Duration.ofSeconds(10);

	private HttpServer cognos;

	private final List<String> bodies = new CopyOnWriteArrayList<>();

	private int logons;

	@BeforeEach
	void start() throws IOException {
		cognos = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		cognos.createContext("/api/v1/session", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			bodies.add(exchange.getRequestMethod() + " " + body);
			if (body.contains("\"wrong\"")) {
				byte[] refusal = "{\"errorCodeString\":\"camAuthUserRecoverable\"}".getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(401, refusal.length);
				exchange.getResponseBody().write(refusal);
			}
			else {
				int n;
				synchronized (this) {
					n = ++logons;
				}
				exchange.getResponseHeaders().add("Set-Cookie", "cam_passport=passport-" + n + "; Path=/; HttpOnly");
				exchange.sendResponseHeaders(201, -1);
			}
			exchange.close();
		});
		cognos.start();
	}

	@AfterEach
	void stop() {
		cognos.stop(0);
	}

	private String dispatcher() {
		return "http://127.0.0.1:" + cognos.getAddress().getPort();
	}

	@Test
	void logsOnForAFreshPassportEachTime() {
		var passports = CognosPassports.password(dispatcher() + "/bi/", "LDAP", "viewer", "viewerpw");
		assertThat(passports.get().block(WAIT)).isEqualTo("passport-1");
		assertThat(passports.get().block(WAIT)).isEqualTo("passport-2");
		assertThat(bodies).allSatisfy(body -> assertThat(body).startsWith("PUT ")
			.contains("{\"name\":\"CAMNamespace\",\"value\":\"LDAP\"}")
			.contains("\"CAMUsername\"")
			.contains("\"viewerpw\""));
	}

	@Test
	void sendsAnIdTokenRatherThanAPassword() {
		var passports = CognosPassports.idToken(dispatcher(), "OIDC", () -> Mono.just("eyJ.token"));
		assertThat(passports.get().block(WAIT)).isEqualTo("passport-1");
		assertThat(bodies).singleElement().asString().contains("CAMOIDCIdToken").contains("eyJ.token");
	}

	@Test
	void refusesWrongCredentialsAsRejected() {
		var passports = CognosPassports.password(dispatcher(), "LDAP", "viewer", "wrong");
		assertThatThrownBy(() -> passports.get().block(WAIT)).isInstanceOfSatisfying(Tm1AuthenticationException.class,
				e -> assertThat(e.kind()).isEqualTo(Tm1ErrorKind.CREDENTIAL_REJECTED));
	}

	@Test
	void describesWithoutTheSecret() {
		Tm1Authenticator cam = Tm1Authenticator.cognosLogon(dispatcher(), "LDAP", "viewer", "viewerpw");
		assertThat(cam.describe()).isEqualTo("CAMPassport from Cognos LDAP/viewer");
		assertThat(cam.toString()).doesNotContain("viewerpw");
		assertThat(cam.authorization().block(WAIT)).isEqualTo("CAMPassport passport-1");
	}

}

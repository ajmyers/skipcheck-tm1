// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.JsonNode;

/**
 * That {@code TM1_VERIFY=false} really reaches the clients {@link LiveServer} builds: a
 * stand-in for TM1 over HTTPS with a self-signed certificate (answering the login's
 * session ID and anything else alike) is refused, unless the certificate isn't to be
 * checked.
 */
class LiveServerTrustTest {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static DisposableServer https;

	@BeforeAll
	static void start(@TempDir Path folder) throws Exception {
		Path store = folder.resolve("self-signed.p12");
		Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
				"-genkeypair", "-alias", "tm1", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
				"-validity", "2", "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "secret",
				"-keypass", "secret")
			.redirectErrorStream(true)
			.start();
		assertThat(keytool.waitFor()).as(new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
			.isZero();
		KeyStore keys = KeyStore.getInstance("PKCS12");
		try (InputStream in = Files.newInputStream(store)) {
			keys.load(in, "secret".toCharArray());
		}
		KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		managers.init(keys, "secret".toCharArray());
		SslContext server = SslContextBuilder.forServer(managers).build();
		https = HttpServer.create()
			.host("localhost")
			.port(0)
			.secure(ssl -> ssl.sslContext(server))
			.handle((request, response) -> response.header("Content-Type", "application/json")
				.header("Set-Cookie", "TM1SessionId=s; Path=/api/")
				.sendString(Mono.just(request.uri().contains("ActiveSession") ? "{\"ID\":1}" : "{\"Name\":\"tm1\"}")))
			.bindNow();
	}

	@AfterAll
	static void stop() {
		https.disposeNow();
	}

	private static LiveServer server(String verify) {
		return LiveServer
			.running(
					Map.of(LiveServer.URL, "https://localhost:" + https.port() + "/api/v1/", "TM1_VERIFY", verify)::get)
			.orElseThrow();
	}

	private static JsonNode server(Tm1Client tm1) {
		try {
			return tm1.session()
				.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.none(), JsonNode.class))
				.block(WAIT);
		}
		finally {
			tm1.close().onErrorComplete().block(WAIT);
		}
	}

	@Test
	void trustsTheCertificateOnlyWhenTm1VerifyIsFalse() {
		assertThat(server(server("false").clientBuilder().build()).get("Name").asString()).isEqualTo("tm1");
		Tm1Session session = server("false").sessionBuilder().build();
		try {
			assertThat(session.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.none(), JsonNode.class))
				.block(WAIT)
				.get("Name")
				.asString()).isEqualTo("tm1");
		}
		finally {
			session.close().onErrorComplete().block(WAIT);
		}
		assertThatThrownBy(() -> server(server("true").clientBuilder().build())).hasStackTraceContaining("PKIX");
	}

}

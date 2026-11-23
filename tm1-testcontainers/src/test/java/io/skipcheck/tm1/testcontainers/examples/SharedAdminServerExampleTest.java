package io.skipcheck.tm1.testcontainers.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.RequiresTm1Web;
import io.skipcheck.tm1.testcontainers.Tm1AdminServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;
import io.skipcheck.tm1.testcontainers.Tm1WebContainer;
import io.skipcheck.tm1.testcontainers.Tm1WebSession;
import io.skipcheck.tm1.testcontainers.Tm1WebSource;

/**
 * <b>Several TM1 servers, one Admin Server</b>, as in a real deployment: the servers
 * register with a {@link Tm1AdminServerContainer}, and TM1 Web lists them all from it.
 *
 * <p>
 * {@code withAdminServer} puts each container on the Admin Server's Docker network and
 * starts the Admin Server first. Each server is named with the image's
 * {@code TM1_SERVER_NAME}.
 */
class SharedAdminServerExampleTest {

	@Test
	@RequiresTm1
	void serversRegisterWithOneAdminServer() throws Exception {
		Tm1Source source = Tm1Source.discover().orElseThrow();
		try (var admin = new Tm1AdminServerContainer(source);
				var planning = new Tm1ServerContainer(source).withEnv("TM1_SERVER_NAME", "planning")
					.withAdminServer(admin);
				var reporting = new Tm1ServerContainer(source).withEnv("TM1_SERVER_NAME", "reporting")
					.withAdminServer(admin)) {
			planning.start();
			reporting.start();

			assertThat(servers(admin)).contains("\"Name\":\"planning\"").contains("\"Name\":\"reporting\"");
			try (var rest = new Tm1Rest(planning)) {
				assertThat(rest.get("Configuration/ServerName/$value")).isEqualTo("planning");
			}
		}
	}

	@Test
	@RequiresTm1Web
	void tm1WebListsEveryRegisteredServer() throws Exception {
		Tm1Source source = Tm1Source.discover().orElseThrow();
		try (var admin = new Tm1AdminServerContainer(source);
				var planning = new Tm1ServerContainer(source).withEnv("TM1_SERVER_NAME", "planning")
					.withAdminServer(admin);
				var reporting = new Tm1ServerContainer(source).withEnv("TM1_SERVER_NAME", "reporting")
					.withAdminServer(admin);
				var web = new Tm1WebContainer(Tm1WebSource.discover().orElseThrow()).withAdminServer(admin)) {
			planning.start();
			reporting.start();
			web.start();

			assertThat(new Tm1WebSession(web).servers(web.getAdminHost())).contains("planning", "reporting");
		}
	}

	/**
	 * The Admin Server's list of registered servers. TM1 servers register within seconds.
	 */
	private static String servers(Tm1AdminServerContainer admin) throws Exception {
		String body = "";
		for (int i = 0; i < 30 && !(body.contains("planning") && body.contains("reporting")); i++) {
			Thread.sleep(1000);
			body = HttpClient.newHttpClient()
				.send(HttpRequest.newBuilder(URI.create(admin.getRestUrl() + "Servers?$select=Name")).build(),
						HttpResponse.BodyHandlers.ofString())
				.body();
		}
		return body;
	}

}

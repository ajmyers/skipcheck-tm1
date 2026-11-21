package net.ajmyers.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

@RequiresTm1Web
class Tm1WebContainerTest {

	@Test
	void listsAndLogsOnToTheSharedTm1Server(Tm1WebContainer web, Tm1ServerContainer tm1) throws Exception {
		String server = serverName(tm1);
		var session = new Tm1WebSession(web);

		assertThat(session.servers(web.getAdminHost())).contains(server);
		assertThat(session.login(web.getAdminHost(), server, tm1.getUsername(), tm1.getPassword())).isNotBlank();
	}

	@Test
	void sharesOneContainerWiredToTheSharedServer(Tm1WebContainer web, Tm1ServerContainer tm1) {
		assertThat(web).isSameAs(Tm1WebContainer.shared());
		assertThat(web.getTm1Server()).isSameAs(tm1);
		assertThat(web.getUrl()).startsWith("http://").endsWith("/tm1web/");
	}

	/** The server's name, which TM1 Web lists it under: {@code tm1} unless configured. */
	static String serverName(Tm1ServerContainer tm1) throws Exception {
		String auth = Base64.getEncoder()
			.encodeToString((tm1.getUsername() + ":" + tm1.getPassword()).getBytes(StandardCharsets.UTF_8));
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create(tm1.getRestUrl() + "Configuration/ServerName/$value"))
				.header("Authorization", "Basic " + auth)
				.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		return response.body();
	}

}

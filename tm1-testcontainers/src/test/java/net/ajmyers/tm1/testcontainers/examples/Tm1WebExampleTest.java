package net.ajmyers.tm1.testcontainers.examples;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.testcontainers.RequiresTm1Web;
import net.ajmyers.tm1.testcontainers.Tm1Seed;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;
import net.ajmyers.tm1.testcontainers.Tm1WebContainer;
import net.ajmyers.tm1.testcontainers.Tm1WebSession;

/**
 * <b>TM1 Web against a TM1 server of its own</b>, here with IBM's SData sample, for tests
 * that need data in TM1 Web or a server no other test touches.
 *
 * <p>
 * {@code withTm1Server} gives the TM1 server a Docker network shared with TM1 Web, and
 * starting TM1 Web starts the server first. For tests that are happy to share, inject a
 * {@link Tm1WebContainer} parameter instead: it's wired to the shared TM1 server.
 */
@RequiresTm1Web
class Tm1WebExampleTest {

	@Test
	void logsOnToItsOwnServerThroughTm1Web(Tm1WebContainer shared, Tm1ServerContainer sharedTm1) {
		// Same sources as the shared containers; pass your own Tm1Source/Tm1WebSource
		// instead
		try (var tm1 = new Tm1ServerContainer(sharedTm1.getSource()).withSeed(Tm1Seed.SDATA);
				var web = new Tm1WebContainer(shared.getSource()).withTm1Server(tm1)) {
			web.start();

			// TM1 Web drives a browser; Tm1WebSession stands in for one here
			var session = new Tm1WebSession(web);
			assertThat(session.servers(web.getAdminHost())).containsExactly("tm1");
			String tm1SessionId = session.login(web.getAdminHost(), "tm1", tm1.getUsername(), tm1.getPassword());
			assertThat(tm1SessionId).isNotBlank();

			// What a universal report does: evaluate MDX through TM1 Web's evalservice
			String serviceRoot = "http://" + tm1.getContainerInfo().getConfig().getHostName() + ":"
					+ Tm1ServerContainer.REST_PORT;
			assertThat(
					session.evaluateMdx(serviceRoot, "tm1", tm1SessionId,
							"SELECT {[Model].[Model].Members} ON 0 FROM [SalesCube] WHERE ([Actvsbud].[Actual], "
									+ "[Region].[World], [Account1].[Sales], [Month].[Year])"))
				.contains("\"success\":true");
		}
	}

}

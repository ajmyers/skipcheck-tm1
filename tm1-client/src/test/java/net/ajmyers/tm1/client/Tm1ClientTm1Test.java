package net.ajmyers.tm1.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;
import tools.jackson.databind.JsonNode;

/**
 * Phase 0's exit criterion, in the plan's own words: connect, read {@code Server}, close.
 */
@RequiresTm1
class Tm1ClientTm1Test {

	@Test
	void connectsReadsTheServerAndCloses(Tm1ServerContainer tm1) {
		Tm1Client client = Tm1Client.builder()
			.baseUrl(tm1.getRestUrl())
			.basicAuth(tm1.getUsername(), tm1.getPassword())
			.build();

		JsonNode server = client.connect()
			.flatMap(c -> c.session()
				.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.none().select("Name", "ProductVersion"),
						JsonNode.class)))
			.block(Duration.ofSeconds(30));
		client.close().block(Duration.ofSeconds(30));

		assertThat(server.get("ProductVersion").asString()).isEqualTo(tm1.getTm1Version().toString());
		assertThat(client.session().isClosed()).isTrue();
		assertThat(client.withSandbox("x").session().sandbox()).hasValue("x");
	}

}

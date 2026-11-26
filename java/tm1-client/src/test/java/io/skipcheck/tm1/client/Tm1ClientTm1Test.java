// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import tools.jackson.databind.JsonNode;

/**
 * The smallest whole use of the client: connect, read {@code Server}, close.
 */
@LiveTm1
class Tm1ClientTm1Test {

	@Test
	void connectsReadsTheServerAndCloses(LiveServer tm1) {
		Tm1Client client = tm1.clientBuilder().build();

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

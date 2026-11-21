package net.ajmyers.tm1.client.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.session.Tm1Session;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;

/** The generated model, read straight from a real TM1 through the session. */
@RequiresTm1
class GeneratedModelTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	@Test
	void readsTheServerAndStreamsDimensionsIntoGeneratedTypes(Tm1ServerContainer tm1) {
		Tm1Session session = Tm1Session.builder()
			.baseUrl(tm1.getRestUrl())
			.basicAuth(tm1.getUsername(), tm1.getPassword())
			.build();
		try {
			Server server = session.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.none(), Server.class))
				.block(WAIT);
			assertThat(server.productVersion()).isEqualTo(tm1.getTm1Version().toString());
			assertThat(server.etag()).as("TM1 sends no ETag for the Server singleton").isNull();

			List<Dimension> dimensions = session
				.stream(Tm1Request.list(ODataPath.of("Dimensions"), ODataQuery.none().expand("Hierarchies"),
						Dimension.class))
				.collectList()
				.block(WAIT);
			assertThat(dimensions).isNotEmpty().allSatisfy(d -> {
				assertThat(d.name()).isNotBlank();
				assertThat(d.etag()).isNotBlank();
				assertThat(d.hierarchies()).isNotEmpty();
			});
		}
		finally {
			session.close().block(WAIT);
		}
	}

}

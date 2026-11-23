package examples;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Cube;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.version.Tm1Version;
import io.skipcheck.tm1.testcontainers.Tm1Seed;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * Examples for {@link Tm1ServerContainer}, quoted in its Javadoc. Test code, so it may
 * block.
 */
final class Tm1ServerContainerExamples {

	private static final Duration WAIT = Duration.ofSeconds(30);

	Tm1Client client(Tm1ServerContainer server) {
		// @start region = client
		// The container's REST URL and login, in TM1's own security
		return Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
		// @end
	}

	List<String> sample() {
		// @start region = sample
		Tm1Source source = Tm1Source.discover().orElseThrow();
		// SData's login is admin / apple, and the server must be this version
		try (var server = new Tm1ServerContainer(source).withSeed(Tm1Seed.SDATA)
			.withExpectedVersion(Tm1Version.parse("11.8.03500.6"))) {
			server.start();
			Tm1Client tm1 = Tm1Client.builder()
				.baseUrl(server.getRestUrl())
				.basicAuth(server.getUsername(), server.getPassword())
				.build();
			try {
				// SData's cubes, such as SalesCube
				return tm1.cubes()
					.modelCubes(ODataQuery.none().select(Cube.NAME))
					.map(Cube::name)
					.collectList()
					.block(WAIT);
			}
			finally {
				tm1.close().block(WAIT); // before the container stops
			}
		}
		// @end
	}

	String ownDatabase(Tm1Source source) {
		// @start region = own-database
		// A fixture model (.cub, .dim, .pro files and so on), copied in before TM1 starts
		try (var server = new Tm1ServerContainer(source).withData(Path.of("src/test/resources/tm1/sales"))
			.withCredentials("admin", "apple")) { // a login the database has
			server.start();
			return server.getRestUrl(); // http://localhost:54321/api/v1/
		}
		// @end
	}

	private Tm1ServerContainerExamples() {
	}

}

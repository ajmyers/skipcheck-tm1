package examples;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.model.Cube;
import net.ajmyers.tm1.client.odata.ODataQuery;

/**
 * Examples for {@link Tm1Client}, quoted in its Javadoc.
 */
final class ClientExamples {

	void connect() {
		// @start region = connect
		Tm1Client tm1 = Tm1Client.builder()
			.baseUrl("http://localhost:8010/api/v1/")
			.basicAuth("admin", "apple")
			.build();

		tm1.cubes()
			.modelCubes(ODataQuery.none().select(Cube.NAME))
			.map(Cube::name)
			.doOnNext(System.out::println)
			.then(tm1.close()) // close after the list completes, not alongside it
			.block(); // block only in a standalone main(), never in reactive code
		// @end
	}

	private ClientExamples() {
	}

}

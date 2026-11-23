package examples;

import java.time.Duration;
import java.util.Map;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Cube;
import io.skipcheck.tm1.client.model.ProcessExecuteResult;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Mono;

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

	Mono<ProcessExecuteResult> async(Tm1Client tm1) {
		// @start region = async
		// TM1 runs it in the background and the client polls for the result, so no
		// request stays open. Cancelling the subscription stops the process on TM1.
		return tm1.withAsync(Duration.ofSeconds(2))
			.processes()
			.executeWithReturn("Rebuild Forecast", Map.of(), ODataQuery.none());
		// @end
	}

	private ClientExamples() {
	}

}

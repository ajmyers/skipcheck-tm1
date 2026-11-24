// SPDX-License-Identifier: Apache-2.0

package examples;

import java.util.List;

import io.skipcheck.tm1.client.Tm1Batch;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Cube;
import io.skipcheck.tm1.client.model.Dimension;
import io.skipcheck.tm1.client.model.Element;
import io.skipcheck.tm1.client.model.ElementCreate;
import io.skipcheck.tm1.client.model.Process;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple3;

/**
 * Examples for {@link Tm1Batch}, quoted in its Javadoc.
 */
final class BatchExamples {

	Mono<Tuple3<Cube, Dimension, List<Process>>> read(Tm1Client tm1) {
		// @start region = read
		// Each call is queued as it's made, and has its result once the batch has run
		Tm1Batch batch = tm1.batch();
		Mono<Cube> sales = batch.client().cubes().get("Sales", ODataQuery.none());
		Mono<Dimension> region = batch.client().dimensions().get("Region", ODataQuery.none());
		Flux<Process> processes = batch.client().processes().list(ODataQuery.none().select(Process.NAME));
		return batch.run().then(Mono.zip(sales, region, processes.collectList()));
		// @end
	}

	Flux<String> many(Tm1Client tm1, List<String> cities) {
		// @start region = many
		// One request per element, 200 to a $batch; one element's failure is its own
		Tm1Batch batch = tm1.batch();
		List<Mono<String>> results = cities.stream()
			.map(city -> batch.client()
				.elements()
				.create("City", "City", ElementCreate.builder().name(city).build())
				.map(Element::name)
				.onErrorResume(e -> Mono.just(city + " failed: " + e.getMessage())))
			.toList();
		return batch.run(200).thenMany(Flux.concat(results));
		// @end
	}

	private BatchExamples() {
	}

}

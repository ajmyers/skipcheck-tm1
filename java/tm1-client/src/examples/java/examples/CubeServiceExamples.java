package examples;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Cube;
import io.skipcheck.tm1.client.model.CubePatch;
import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.cube.CubeService}, quoted in its
 * Javadoc.
 */
final class CubeServiceExamples {

	Flux<String> list(Tm1Client tm1) {
		// @start region = list
		// The names of the model cubes starting with "Sales", in name order
		return tm1.cubes()
			.modelCubes(ODataQuery.none()
				.select(Cube.NAME)
				.filter(Filter.startsWith(Cube.NAME, "Sales"))
				.orderBy(Cube.NAME))
			.map(Cube::name);
		// @end
	}

	Mono<Cube> create(Tm1Client tm1) {
		// @start region = create
		// A cube over two existing dimensions, in this order
		ODataPath dimensions = ODataPath.of("Dimensions");
		return tm1.cubes()
			.create(CubePatch.builder()
				.name("Sales")
				.bindDimensions(dimensions.key("Region"), dimensions.key("Month"))
				.build());
		// @end
	}

	Mono<Void> updateRules(Tm1Client tm1) {
		// @start region = update-rules
		// TM1 saves rules even when they don't compile, so check them first
		String rules = "SKIPCHECK;\n['Margin'] = N: ['Revenue'] - ['Cost'];";
		return tm1.cubes()
			.checkRules("Sales", rules)
			.collectList()
			.flatMap(errors -> errors.isEmpty() ? tm1.cubes().update("Sales", CubePatch.builder().rules(rules).build())
					: Mono.error(new IllegalArgumentException("The rules don't compile: " + errors)));
		// @end
	}

	private CubeServiceExamples() {
	}

}

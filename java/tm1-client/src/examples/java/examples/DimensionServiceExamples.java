// SPDX-License-Identifier: Apache-2.0

package examples;

import java.util.List;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Dimension;
import io.skipcheck.tm1.client.model.DimensionPatch;
import io.skipcheck.tm1.client.model.EdgePatch;
import io.skipcheck.tm1.client.model.ElementPatch;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.model.Hierarchy;
import io.skipcheck.tm1.client.model.HierarchyPatch;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.dimension.DimensionService}, quoted
 * in its Javadoc.
 */
final class DimensionServiceExamples {

	Flux<String> list(Tm1Client tm1) {
		// @start region = list
		// The names of the model dimensions, in name order
		return tm1.dimensions()
			.modelDimensions(ODataQuery.none().select(Dimension.NAME).orderBy(Dimension.NAME))
			.map(Dimension::name);
		// @end
	}

	Mono<Dimension> create(Tm1Client tm1) {
		// @start region = create
		// Region, with one hierarchy of the same name: Total = North + South
		ElementPatch total = ElementPatch.builder().name("Total").type(ElementType.CONSOLIDATED).build();
		ElementPatch north = ElementPatch.builder().name("North").type(ElementType.NUMERIC).build();
		ElementPatch south = ElementPatch.builder().name("South").type(ElementType.NUMERIC).build();
		return tm1.dimensions()
			.create(DimensionPatch.builder()
				.name("Region")
				.hierarchies(List.of(HierarchyPatch.builder()
					.name("Region")
					.elements(List.of(total, north, south))
					.edges(List.of(EdgePatch.builder().parentName("Total").componentName("North").weight(1.0).build(),
							EdgePatch.builder().parentName("Total").componentName("South").weight(1.0).build()))
					.build()))
				.build());
		// @end
	}

	Flux<String> hierarchies(Tm1Client tm1) {
		// @start region = hierarchies
		// A dimension with its hierarchies' names expanded in the same request
		return tm1.dimensions()
			.get("Region", ODataQuery.none().expand(Dimension.HIERARCHIES, h -> h.select(Hierarchy.NAME)))
			.flatMapIterable(Dimension::hierarchies)
			.map(Hierarchy::name);
		// @end
	}

	private DimensionServiceExamples() {
	}

}

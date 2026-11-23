// SPDX-License-Identifier: Apache-2.0

package examples;

import java.util.List;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Element;
import io.skipcheck.tm1.client.model.Subset;
import io.skipcheck.tm1.client.model.SubsetPatch;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.dimension.SubsetScope;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.dimension.SubsetService}, quoted in
 * its Javadoc.
 */
final class SubsetServiceExamples {

	Mono<Subset> createStatic(Tm1Client tm1) {
		// @start region = create-static
		// A static subset lists its elements, bound by their paths
		ODataPath elements = ODataPath.of("Dimensions").key("Region").nav("Hierarchies").key("Region").nav("Elements");
		return tm1.subsets()
			.create("Region", "Region", SubsetScope.PUBLIC,
					SubsetPatch.builder()
						.name("Key Regions")
						.bindElements(elements.key("North"), elements.key("South"))
						.build());
		// @end
	}

	Mono<Subset> createDynamic(Tm1Client tm1) {
		// @start region = create-dynamic
		// A dynamic subset has an MDX expression, evaluated each time it's read
		return tm1.subsets()
			.create("Region", "Region", SubsetScope.PUBLIC,
					SubsetPatch.builder()
						.name("All Leaves")
						.expression("{TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)}")
						.build());
		// @end
	}

	Mono<Void> replaceElements(Tm1Client tm1) {
		// @start region = replace
		// A PATCH appends elements, so empty the list, then add the new ones in order
		return tm1.subsets()
			.clearElements("Region", "Region", SubsetScope.PUBLIC, "Key Regions")
			.then(tm1.subsets()
				.addElements("Region", "Region", SubsetScope.PUBLIC, "Key Regions", List.of("East", "West")));
		// @end
	}

	Flux<String> session(Tm1Client tm1) {
		// @start region = session
		// TM1 names a session subset itself, ignoring the name sent: read it by the name
		// it gives back
		return tm1.subsets()
			.createSessionSubset("Region", "Region",
					SubsetPatch.builder().name("Regions").expression("{[Region].[Region].[Total].Children}").build())
			.flatMapMany(subset -> tm1.subsets()
				.elements("Region", "Region", SubsetScope.SESSION, subset.name(),
						ODataQuery.none().select(Element.NAME)))
			.map(Element::name);
		// @end
	}

	private SubsetServiceExamples() {
	}

}

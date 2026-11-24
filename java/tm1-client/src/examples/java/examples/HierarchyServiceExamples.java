// SPDX-License-Identifier: Apache-2.0

package examples;

import java.util.List;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.EdgePatch;
import io.skipcheck.tm1.client.model.ElementCreate;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.model.Hierarchy;
import io.skipcheck.tm1.client.model.HierarchyCreate;
import io.skipcheck.tm1.client.model.Member;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.dimension.HierarchyService}, quoted
 * in its Javadoc.
 */
final class HierarchyServiceExamples {

	Mono<Hierarchy> create(Tm1Client tm1) {
		// @start region = create
		// A second hierarchy of Region, rolling the same leaves up by manager
		return tm1.hierarchies()
			.create("Region", HierarchyCreate.builder()
				.name("By Manager")
				.elements(List.of(ElementCreate.builder().name("Smith").type(ElementType.CONSOLIDATED).build(),
						ElementCreate.builder().name("North").type(ElementType.NUMERIC).build(),
						ElementCreate.builder().name("South").type(ElementType.NUMERIC).build()))
				.edges(List.of(EdgePatch.builder().parentName("Smith").componentName("North").weight(1.0).build(),
						EdgePatch.builder().parentName("Smith").componentName("South").weight(1.0).build()))
				.build());
		// @end
	}

	Mono<Void> replaceStructure(Tm1Client tm1) {
		// @start region = replace
		// Makes the elements and edges exactly these: TM1 deletes every other element of
		// the hierarchy, with its data
		ElementCreate all = ElementCreate.builder().name("All Versions").type(ElementType.CONSOLIDATED).build();
		ElementCreate actual = ElementCreate.builder().name("Actual").type(ElementType.NUMERIC).build();
		ElementCreate budget = ElementCreate.builder().name("Budget").type(ElementType.NUMERIC).build();
		return tm1.hierarchies()
			.replaceStructure("Version", "Version", List.of(all, actual, budget), List.of(
					EdgePatch.builder().parentName("All Versions").componentName("Actual").weight(1.0).build(),
					EdgePatch.builder().parentName("All Versions").componentName("Budget").weight(1.0).build()));
		// @end
	}

	Flux<String> roots(Tm1Client tm1) {
		// @start region = roots
		// The top of the hierarchy: the members with no parent, in order
		return tm1.hierarchies()
			.rootMembers("Region", "Region", ODataQuery.none().select(Member.NAME))
			.map(Member::name);
		// @end
	}

	private HierarchyServiceExamples() {
	}

}

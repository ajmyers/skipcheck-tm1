// SPDX-License-Identifier: Apache-2.0

package examples;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Element;
import io.skipcheck.tm1.client.model.ElementCreate;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.dimension.ElementService}, quoted
 * in its Javadoc.
 */
final class ElementServiceExamples {

	Flux<String> consolidations(Tm1Client tm1) {
		// @start region = filter
		// The names of the consolidated elements, in name order
		return tm1.elements()
			.list("Region", "Region",
					ODataQuery.none()
						.select(Element.NAME)
						.filter(Element.TYPE.eq(ElementType.CONSOLIDATED))
						.orderBy(Element.NAME))
			.map(Element::name);
		// @end
	}

	Mono<Void> addChild(Tm1Client tm1) {
		// @start region = add-child
		// A new element is a root; SetComponent then puts it last under Total
		return tm1.elements()
			.create("Region", "Region", ElementCreate.builder().name("West").type(ElementType.NUMERIC).build())
			.then(tm1.elements().setComponent("Region", "Region", "Total", "West"));
		// @end
	}

	Flux<String> children(Tm1Client tm1) {
		// @start region = children
		// The children of Total, in order
		return tm1.elements()
			.components("Region", "Region", "Total", ODataQuery.none().select(Element.NAME))
			.map(Element::name);
		// @end
	}

	private ElementServiceExamples() {
	}

}

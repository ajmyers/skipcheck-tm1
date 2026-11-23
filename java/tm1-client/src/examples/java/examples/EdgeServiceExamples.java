// SPDX-License-Identifier: Apache-2.0

package examples;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Edge;
import io.skipcheck.tm1.client.model.EdgePatch;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.dimension.EdgeService}, quoted in
 * its Javadoc.
 */
final class EdgeServiceExamples {

	Flux<String> list(Tm1Client tm1) {
		// @start region = list
		// Every parent-child link of the hierarchy, as "parent > child (weight)"
		return tm1.edges()
			.list("Region", "Region", ODataQuery.none())
			.map(edge -> edge.parentName() + " > " + edge.componentName() + " (" + edge.weight() + ")");
		// @end
	}

	Mono<Void> reweight(Tm1Client tm1) {
		// @start region = reweight
		// Make Cost count negatively in Margin
		return tm1.edges().update("Account", "Account", "Margin", "Cost", EdgePatch.builder().weight(-1.0).build());
		// @end
	}

	Mono<Edge> move(Tm1Client tm1) {
		// @start region = move
		// Move North from Total to Total Sales: add the new edge, then delete the old
		return tm1.edges()
			.create("Region", "Region",
					EdgePatch.builder().parentName("Total Sales").componentName("North").weight(1.0).build())
			.flatMap(edge -> tm1.edges().delete("Region", "Region", "Total", "North").thenReturn(edge));
		// @end
	}

	private EdgeServiceExamples() {
	}

}

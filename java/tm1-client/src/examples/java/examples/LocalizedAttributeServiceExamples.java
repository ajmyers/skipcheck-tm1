// SPDX-License-Identifier: Apache-2.0

package examples;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Attributes;
import io.skipcheck.tm1.client.model.AttributesPatch;
import io.skipcheck.tm1.client.model.LocalizedAttributes;
import io.skipcheck.tm1.client.model.LocalizedAttributesCreate;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.attribute.AttributeOwner;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * Examples for
 * {@link io.skipcheck.tm1.client.service.attribute.LocalizedAttributeService}, quoted in
 * its Javadoc.
 */
final class LocalizedAttributeServiceExamples {

	Mono<LocalizedAttributes> translate(Tm1Client tm1) {
		// @start region = translate
		// The French for an element's Long Name, an attribute its hierarchy has. A
		// locale's translations are created once: TM1 can't change them here.
		return tm1.localizedAttributes()
			.create(AttributeOwner.element("Region", "Region", "North"),
					LocalizedAttributesCreate.builder()
						.localeID("fr")
						.attributes(AttributesPatch.builder().property("Long Name", "Région Nord").build())
						.build());
		// @end
	}

	Mono<String> read(Tm1Client tm1) {
		// @start region = read
		// Attributes other than Caption come back in dynamicProperties
		return tm1.localizedAttributes()
			.get(AttributeOwner.element("Region", "Region", "North"), "fr", ODataQuery.none())
			.mapNotNull(LocalizedAttributes::attributes)
			.mapNotNull(Attributes::dynamicProperties)
			.mapNotNull(values -> values.get("Long Name"))
			.map(JsonNode::asString);
		// @end
	}

	private LocalizedAttributeServiceExamples() {
	}

}

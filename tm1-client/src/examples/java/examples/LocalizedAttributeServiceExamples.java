package examples;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.model.Attributes;
import net.ajmyers.tm1.client.model.AttributesPatch;
import net.ajmyers.tm1.client.model.LocalizedAttributes;
import net.ajmyers.tm1.client.model.LocalizedAttributesPatch;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.service.attribute.AttributeOwner;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * Examples for
 * {@link net.ajmyers.tm1.client.service.attribute.LocalizedAttributeService}, quoted in
 * its Javadoc.
 */
final class LocalizedAttributeServiceExamples {

	Mono<LocalizedAttributes> translate(Tm1Client tm1) {
		// @start region = translate
		// The French for an element's Long Name, an attribute its hierarchy has. A
		// locale's translations are created once: TM1 can't change them here.
		return tm1.localizedAttributes()
			.create(AttributeOwner.element("Region", "Region", "North"),
					LocalizedAttributesPatch.builder()
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

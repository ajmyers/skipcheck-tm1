package examples;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.model.AttributeDefinition;
import net.ajmyers.tm1.client.model.AttributeDefinitionPatch;
import net.ajmyers.tm1.client.model.AttributeType;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.service.attribute.AttributeSet;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link net.ajmyers.tm1.client.service.attribute.AttributeService}, quoted
 * in its Javadoc.
 */
final class AttributeServiceExamples {

	Mono<AttributeDefinition> create(Tm1Client tm1) {
		// @start region = create
		// A text attribute on every element of the Region hierarchy
		return tm1.attributes()
			.create(AttributeSet.elements("Region", "Region"),
					AttributeDefinitionPatch.builder().name("Manager").type(AttributeType.STRING).build());
		// @end
	}

	Flux<String> list(Tm1Client tm1) {
		// @start region = list
		// The attributes that every cube has
		return tm1.attributes()
			.list(AttributeSet.cubes(), ODataQuery.none().select(AttributeDefinition.NAME))
			.mapNotNull(AttributeDefinition::name);
		// @end
	}

	private AttributeServiceExamples() {
	}

}

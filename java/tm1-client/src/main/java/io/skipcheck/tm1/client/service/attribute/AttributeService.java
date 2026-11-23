// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.attribute;

import java.util.Objects;

import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.AttributeDefinition;
import io.skipcheck.tm1.client.model.AttributeDefinitionPatch;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Attribute definitions: which attributes a kind of object has, and their types, for each
 * {@link AttributeSet}. Creating one adds it to every object of that kind; the values are
 * cells of the matching <code>&#125;…Attributes</code> control cube.
 *
 * <p>
 * Add an attribute to every element of a hierarchy:
 * {@snippet class = examples.AttributeServiceExamples region = create}
 *
 * <p>
 * List the attributes a kind of object has, here cubes:
 * {@snippet class = examples.AttributeServiceExamples region = list}
 */
public final class AttributeService {

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public AttributeService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET} the set's attributes, streamed.
	 * @param set which attributes
	 * @param query what to select, filter and order
	 * @return the attribute definitions
	 */
	@Tm1Endpoint("EntitySet DimensionAttributes")
	@Tm1Endpoint("Navigation Dimension/HierarchyAttributes")
	@Tm1Endpoint("Navigation Hierarchy/ElementAttributes")
	@Tm1Endpoint("Navigation Hierarchy/SubsetAttributes")
	@Tm1Endpoint("EntitySet CubeAttributes")
	@Tm1Endpoint("Navigation Cube/ViewAttributes")
	@Tm1Endpoint("EntitySet ProcessAttributes")
	@Tm1Endpoint("EntitySet ChoreAttributes")
	public Flux<AttributeDefinition> list(AttributeSet set, ODataQuery query) {
		return session.stream(Tm1Request.list(set.path(), query, AttributeDefinition.class));
	}

	/**
	 * {@code GET} one attribute of the set.
	 * @param set which attributes
	 * @param name the attribute's name
	 * @param query what to select
	 * @return the attribute definition, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("EntitySet DimensionAttributes")
	@Tm1Endpoint("Navigation Dimension/HierarchyAttributes")
	@Tm1Endpoint("Navigation Hierarchy/ElementAttributes")
	@Tm1Endpoint("Navigation Hierarchy/SubsetAttributes")
	@Tm1Endpoint("EntitySet CubeAttributes")
	@Tm1Endpoint("Navigation Cube/ViewAttributes")
	@Tm1Endpoint("EntitySet ProcessAttributes")
	@Tm1Endpoint("EntitySet ChoreAttributes")
	public Mono<AttributeDefinition> get(AttributeSet set, String name, ODataQuery query) {
		return session.execute(
				Tm1Request.get(set.path().key(Objects.requireNonNull(name, "name")), query, AttributeDefinition.class));
	}

	/**
	 * {@code POST}: adds an attribute to every object of the set's kind.
	 * @param set which attributes
	 * @param attribute the new attribute: its name and type
	 * @return the attribute as created
	 */
	@Tm1Endpoint("EntitySet DimensionAttributes")
	@Tm1Endpoint("Navigation Dimension/HierarchyAttributes")
	@Tm1Endpoint("Navigation Hierarchy/ElementAttributes")
	@Tm1Endpoint("Navigation Hierarchy/SubsetAttributes")
	@Tm1Endpoint("EntitySet CubeAttributes")
	@Tm1Endpoint("Navigation Cube/ViewAttributes")
	@Tm1Endpoint("EntitySet ProcessAttributes")
	@Tm1Endpoint("EntitySet ChoreAttributes")
	public Mono<AttributeDefinition> create(AttributeSet set, AttributeDefinitionPatch attribute) {
		return session.execute(Tm1Request.post(set.path(), attribute, AttributeDefinition.class));
	}

	/**
	 * {@code DELETE}: removes an attribute, with its values, from every object of the
	 * set's kind.
	 * @param set which attributes
	 * @param name the attribute's name
	 * @return completes when the attribute is deleted
	 */
	@Tm1Endpoint("EntitySet DimensionAttributes")
	@Tm1Endpoint("Navigation Dimension/HierarchyAttributes")
	@Tm1Endpoint("Navigation Hierarchy/ElementAttributes")
	@Tm1Endpoint("Navigation Hierarchy/SubsetAttributes")
	@Tm1Endpoint("EntitySet CubeAttributes")
	@Tm1Endpoint("Navigation Cube/ViewAttributes")
	@Tm1Endpoint("EntitySet ProcessAttributes")
	@Tm1Endpoint("EntitySet ChoreAttributes")
	public Mono<Void> delete(AttributeSet set, String name) {
		return session.execute(Tm1Request.delete(set.path().key(Objects.requireNonNull(name, "name"))));
	}

}

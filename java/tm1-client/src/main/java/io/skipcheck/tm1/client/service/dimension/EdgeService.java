// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.dimension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.Edge;
import io.skipcheck.tm1.client.model.EdgePatch;
import io.skipcheck.tm1.client.model.Element;
import io.skipcheck.tm1.client.model.Hierarchy;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Edges: {@code /Dimensions('d')/Hierarchies('h')/Edges}, the parent-child links of a
 * hierarchy, each keyed by its parent's and its component's names.
 *
 * <p>
 * List a hierarchy's edges:
 * {@snippet class = examples.EdgeServiceExamples region = list}
 *
 * <p>
 * Change an edge's weight:
 * {@snippet class = examples.EdgeServiceExamples region = reweight}
 *
 * <p>
 * Move an element to another parent:
 * {@snippet class = examples.EdgeServiceExamples region = move}
 */
public final class EdgeService {

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public EdgeService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET …/Hierarchies('h')/Edges}, streamed.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param query what to select, expand, filter and order
	 * @return the hierarchy's edges
	 */
	@Tm1Endpoint("Navigation Hierarchy/Edges")
	public Flux<Edge> list(String dimension, String hierarchy, ODataQuery query) {
		return session.stream(Tm1Request.list(edges(dimension, hierarchy), query, Edge.class));
	}

	/**
	 * {@code GET …/Edges(ParentName='p',ComponentName='c')}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param parent the parent's name
	 * @param component the component's name
	 * @param query what to select and expand
	 * @return the edge, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("Navigation Hierarchy/Edges")
	public Mono<Edge> get(String dimension, String hierarchy, String parent, String component, ODataQuery query) {
		return session.execute(Tm1Request.get(edge(dimension, hierarchy, parent, component), query, Edge.class));
	}

	/**
	 * {@code POST …/Edges}: makes an element a child of a consolidation.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param edge the new edge: parent, component and, optionally, weight
	 * @return the edge as created
	 */
	@Tm1Endpoint("Navigation Hierarchy/Edges")
	public Mono<Edge> create(String dimension, String hierarchy, EdgePatch edge) {
		return session
			.execute(Tm1Request.post(edges(dimension, hierarchy), Objects.requireNonNull(edge, "edge"), Edge.class));
	}

	/**
	 * {@code PATCH …/Edges(ParentName='p',ComponentName='c')}: changes the edge's weight.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param parent the parent's name
	 * @param component the component's name
	 * @param patch the changes
	 * @return completes when the edge is changed
	 */
	@Tm1Endpoint("Navigation Hierarchy/Edges")
	public Mono<Void> update(String dimension, String hierarchy, String parent, String component, EdgePatch patch) {
		return session.execute(Tm1Request.patch(edge(dimension, hierarchy, parent, component), patch));
	}

	/**
	 * {@code DELETE …/Edges(ParentName='p',ComponentName='c')}: takes an element out of a
	 * consolidation; the element stays in the hierarchy.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param parent the parent's name
	 * @param component the component's name
	 * @return completes when the edge is deleted
	 */
	@Tm1Endpoint("Navigation Hierarchy/Edges")
	public Mono<Void> delete(String dimension, String hierarchy, String parent, String component) {
		return session.execute(Tm1Request.delete(edge(dimension, hierarchy, parent, component)));
	}

	/**
	 * {@code GET …/Edges(…)/Parent}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param parent the parent's name
	 * @param component the component's name
	 * @param query what to select and expand
	 * @return the edge's parent
	 */
	@Tm1Endpoint("Navigation Edge/Parent")
	public Mono<Element> parent(String dimension, String hierarchy, String parent, String component, ODataQuery query) {
		return session
			.execute(Tm1Request.get(edge(dimension, hierarchy, parent, component).nav("Parent"), query, Element.class));
	}

	/**
	 * {@code GET …/Edges(…)/Component}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param parent the parent's name
	 * @param component the component's name
	 * @param query what to select and expand
	 * @return the edge's component
	 */
	@Tm1Endpoint("Navigation Edge/Component")
	public Mono<Element> component(String dimension, String hierarchy, String parent, String component,
			ODataQuery query) {
		return session.execute(
				Tm1Request.get(edge(dimension, hierarchy, parent, component).nav("Component"), query, Element.class));
	}

	/**
	 * {@code GET …/Edges(…)/Hierarchy}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param parent the parent's name
	 * @param component the component's name
	 * @param query what to select and expand
	 * @return the edge's hierarchy
	 */
	@Tm1Endpoint("Navigation Edge/Hierarchy")
	public Mono<Hierarchy> hierarchy(String dimension, String hierarchy, String parent, String component,
			ODataQuery query) {
		return session.execute(
				Tm1Request.get(edge(dimension, hierarchy, parent, component).nav("Hierarchy"), query, Hierarchy.class));
	}

	private static ODataPath edges(String dimension, String hierarchy) {
		return ODataPath.of("Dimensions")
			.key(Objects.requireNonNull(dimension, "dimension"))
			.nav("Hierarchies")
			.key(Objects.requireNonNull(hierarchy, "hierarchy"))
			.nav("Edges");
	}

	private static ODataPath edge(String dimension, String hierarchy, String parent, String component) {
		Map<String, Object> key = new LinkedHashMap<>();
		key.put("ParentName", Objects.requireNonNull(parent, "parent"));
		key.put("ComponentName", Objects.requireNonNull(component, "component"));
		return edges(dimension, hierarchy).key(key);
	}

}

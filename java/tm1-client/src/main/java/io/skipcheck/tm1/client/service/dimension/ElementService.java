package io.skipcheck.tm1.client.service.dimension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.client.Tm1Operation;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.Edge;
import io.skipcheck.tm1.client.model.Element;
import io.skipcheck.tm1.client.model.ElementPatch;
import io.skipcheck.tm1.client.model.Hierarchy;
import io.skipcheck.tm1.client.model.Tm1Operations;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Elements: {@code /Dimensions('d')/Hierarchies('h')/Elements}, an element's parents,
 * components and edges, and the operations bound to an element.
 *
 * <p>
 * TM1 (11.8) ignores {@code Name} in an element PATCH, so elements can't be renamed, and
 * rejects {@code Attributes}, whose values are cells of the
 * <code>&#125;ElementAttributes_&lt;dimension&gt;</code> cube. {@link #update} refuses
 * both, saying why.
 *
 * <p>
 * List elements with a query: here, the consolidations.
 * {@snippet class = examples.ElementServiceExamples region = filter}
 *
 * <p>
 * Add an element under a consolidation. A new element is a root until it has a parent:
 * {@snippet class = examples.ElementServiceExamples region = add-child}
 *
 * <p>
 * Read a consolidation's children:
 * {@snippet class = examples.ElementServiceExamples region = children}
 */
public final class ElementService {

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public ElementService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET …/Hierarchies('h')/Elements}, streamed.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param query what to select, expand, filter and order
	 * @return the hierarchy's elements
	 */
	@Tm1Endpoint("Navigation Hierarchy/Elements")
	public Flux<Element> list(String dimension, String hierarchy, ODataQuery query) {
		return session.stream(Tm1Request.list(elements(dimension, hierarchy), query, Element.class));
	}

	/**
	 * {@code GET …/Elements('e')}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param element the element's name
	 * @param query what to select and expand
	 * @return the element, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("Navigation Hierarchy/Elements")
	public Mono<Element> get(String dimension, String hierarchy, String element, ODataQuery query) {
		return session.execute(Tm1Request.get(element(dimension, hierarchy, element), query, Element.class));
	}

	/**
	 * {@code POST …/Elements}: adds an element, as a root of the hierarchy.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param element the new element; it needs a name, and a type unless it's numeric
	 * @return the element as created
	 */
	@Tm1Endpoint("Navigation Hierarchy/Elements")
	public Mono<Element> create(String dimension, String hierarchy, ElementPatch element) {
		return session.execute(Tm1Request.post(elements(dimension, hierarchy), element, Element.class));
	}

	/**
	 * {@code PATCH …/Elements('e')}: changes the properties the patch sets, such as its
	 * type.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param element the element's name
	 * @param patch the changes, without {@code Name} or {@code Attributes}
	 * @return completes when the element is changed
	 * @throws IllegalArgumentException if the patch sets {@code Name}, which TM1 ignores,
	 * or {@code Attributes}, which it rejects
	 */
	@Tm1Endpoint("Navigation Hierarchy/Elements")
	public Mono<Void> update(String dimension, String hierarchy, String element, ElementPatch patch) {
		return session.execute(Tm1Request.patch(element(dimension, hierarchy, element), checked(patch)));
	}

	/**
	 * {@code PATCH …/Elements('e')} with {@code If-Match}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param element the element's name
	 * @param patch the changes, without {@code Name} or {@code Attributes}
	 * @param etag the ETag the element was read with ({@link Element#etag()})
	 * @return completes when the element is changed, or a
	 * {@code Tm1PreconditionFailedException} if it had changed
	 * @throws IllegalArgumentException if the patch sets {@code Name} or
	 * {@code Attributes}
	 */
	@Tm1Endpoint("Navigation Hierarchy/Elements")
	public Mono<Void> update(String dimension, String hierarchy, String element, ElementPatch patch, String etag) {
		return session.execute(Tm1Request.patch(element(dimension, hierarchy, element), checked(patch)).ifMatch(etag));
	}

	/**
	 * {@code DELETE …/Elements('e')}, with the element's edges and data.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param element the element's name
	 * @return completes when the element is deleted
	 */
	@Tm1Endpoint("Navigation Hierarchy/Elements")
	public Mono<Void> delete(String dimension, String hierarchy, String element) {
		return session.execute(Tm1Request.delete(element(dimension, hierarchy, element)));
	}

	/**
	 * {@code GET …/Elements('e')/Parents}, streamed.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param element the element's name
	 * @param query what to select, expand, filter and order
	 * @return the consolidations the element is in
	 */
	@Tm1Endpoint("Navigation Element/Parents")
	public Flux<Element> parents(String dimension, String hierarchy, String element, ODataQuery query) {
		return session
			.stream(Tm1Request.list(element(dimension, hierarchy, element).nav("Parents"), query, Element.class));
	}

	/**
	 * {@code GET …/Elements('e')/Components}, streamed.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param element the element's name
	 * @param query what to select, expand, filter and order
	 * @return the element's children, in order
	 */
	@Tm1Endpoint("Navigation Element/Components")
	public Flux<Element> components(String dimension, String hierarchy, String element, ODataQuery query) {
		return session
			.stream(Tm1Request.list(element(dimension, hierarchy, element).nav("Components"), query, Element.class));
	}

	/**
	 * {@code GET …/Elements('e')/Edges}, streamed.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param element the element's name
	 * @param query what to select, expand, filter and order
	 * @return the edges to the element's children
	 */
	@Tm1Endpoint("Navigation Element/Edges")
	public Flux<Edge> edges(String dimension, String hierarchy, String element, ODataQuery query) {
		return session.stream(Tm1Request.list(element(dimension, hierarchy, element).nav("Edges"), query, Edge.class));
	}

	/**
	 * {@code GET …/Elements('e')/Hierarchy}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param element the element's name
	 * @param query what to select and expand
	 * @return the element's hierarchy
	 */
	@Tm1Endpoint("Navigation Element/Hierarchy")
	public Mono<Hierarchy> hierarchy(String dimension, String hierarchy, String element, ODataQuery query) {
		return session
			.execute(Tm1Request.get(element(dimension, hierarchy, element).nav("Hierarchy"), query, Hierarchy.class));
	}

	/**
	 * {@code tm1.SetComponent}: makes {@code child} a child of {@code parent}, with
	 * weight 1, placed <em>last</em> among its children. ({@code $metadata} doesn't say;
	 * TM1 11.8 puts it last.)
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param parent the consolidation's name
	 * @param child the child's name
	 * @return completes when the edge is set
	 */
	@Tm1Endpoint("Action Element/SetComponent")
	public Mono<Void> setComponent(String dimension, String hierarchy, String parent, String child) {
		return sendSetComponent(dimension, hierarchy, parent, child, null, null);
	}

	/**
	 * {@code tm1.SetComponent}: makes {@code child} a child of {@code parent} with a
	 * weight, placed last among its children.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param parent the consolidation's name
	 * @param child the child's name
	 * @param weight the child's weight in the consolidation
	 * @return completes when the edge is set
	 */
	@Tm1Endpoint("Action Element/SetComponent")
	public Mono<Void> setComponent(String dimension, String hierarchy, String parent, String child, double weight) {
		return sendSetComponent(dimension, hierarchy, parent, child, null, weight);
	}

	/**
	 * {@code tm1.SetComponent}: makes {@code child} a child of {@code parent} with a
	 * weight, placed just before another child.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param parent the consolidation's name
	 * @param child the child's name
	 * @param before the name of the child to place it before
	 * @param weight the child's weight in the consolidation
	 * @return completes when the edge is set
	 */
	@Tm1Endpoint("Action Element/SetComponent")
	public Mono<Void> setComponent(String dimension, String hierarchy, String parent, String child, String before,
			double weight) {
		return sendSetComponent(dimension, hierarchy, parent, child, Objects.requireNonNull(before, "before"),
				Double.valueOf(weight));
	}

	/**
	 * {@code tm1.Lock}: stops everyone else changing the element until it's unlocked.
	 * Needs schema 11.8.23.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param element the element's name
	 * @return completes when the element is locked
	 */
	@Tm1Endpoint("Action Element/Lock")
	public Mono<Void> lock(String dimension, String hierarchy, String element) {
		return session.execute(action(Tm1Operations.ELEMENT_LOCK, dimension, hierarchy, element, Map.of()));
	}

	/**
	 * {@code tm1.Unlock}. Needs schema 11.8.23.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param element the element's name
	 * @return completes when the element is unlocked
	 */
	@Tm1Endpoint("Action Element/Unlock")
	public Mono<Void> unlock(String dimension, String hierarchy, String element) {
		return session.execute(action(Tm1Operations.ELEMENT_UNLOCK, dimension, hierarchy, element, Map.of()));
	}

	/**
	 * {@code tm1.Reserve}: stops everyone else changing the element's data until it's
	 * released. Needs schema 11.8.34.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param element the element's name
	 * @return completes when the element is reserved
	 */
	@Tm1Endpoint("Action Element/Reserve")
	public Mono<Void> reserve(String dimension, String hierarchy, String element) {
		return session.execute(action(Tm1Operations.ELEMENT_RESERVE, dimension, hierarchy, element, Map.of()));
	}

	/**
	 * {@code tm1.Release}. Needs schema 11.8.34.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param element the element's name
	 * @return completes when the element is released
	 */
	@Tm1Endpoint("Action Element/Release")
	public Mono<Void> release(String dimension, String hierarchy, String element) {
		return session.execute(action(Tm1Operations.ELEMENT_RELEASE, dimension, hierarchy, element, Map.of()));
	}

	private Mono<Void> sendSetComponent(String dimension, String hierarchy, String parent, String child,
			@Nullable String before, @Nullable Double weight) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("Element@odata.bind", element(dimension, hierarchy, child).toString());
		if (before != null) {
			body.put("Before@odata.bind", element(dimension, hierarchy, before).toString());
		}
		if (weight != null) {
			body.put("Weight", weight);
		}
		return session.execute(action(Tm1Operations.ELEMENT_SET_COMPONENT, dimension, hierarchy, parent, body));
	}

	private static ElementPatch checked(ElementPatch patch) {
		if (patch.json().containsKey("Name")) {
			throw new IllegalArgumentException("TM1 ignores Name in an element PATCH: elements can't be renamed");
		}
		if (patch.json().containsKey("Attributes")) {
			throw new IllegalArgumentException(
					"TM1 rejects Attributes in an element PATCH: attribute values are cells of }ElementAttributes_<dimension>");
		}
		return patch;
	}

	private static ODataPath elements(String dimension, String hierarchy) {
		return ODataPath.of("Dimensions")
			.key(Objects.requireNonNull(dimension, "dimension"))
			.nav("Hierarchies")
			.key(Objects.requireNonNull(hierarchy, "hierarchy"))
			.nav("Elements");
	}

	private static ODataPath element(String dimension, String hierarchy, String element) {
		return elements(dimension, hierarchy).key(Objects.requireNonNull(element, "element"));
	}

	private static Tm1Request<Void> action(Tm1Operation operation, String dimension, String hierarchy, String element,
			Map<String, ?> body) {
		return Tm1Request.post(element(dimension, hierarchy, element).action(operation.name()), body, Void.class)
			.requires(operation.capability());
	}

}

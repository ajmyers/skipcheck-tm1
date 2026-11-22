package net.ajmyers.tm1.client.service.dimension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import net.ajmyers.tm1.client.Tm1Operation;
import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.model.Element;
import net.ajmyers.tm1.client.model.Hierarchy;
import net.ajmyers.tm1.client.model.Subset;
import net.ajmyers.tm1.client.model.SubsetPatch;
import net.ajmyers.tm1.client.model.Tm1Operations;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.service.Tm1Endpoint;
import net.ajmyers.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Subsets: a hierarchy's public, private and session subsets ({@link SubsetScope}), their
 * elements, and the operations bound to a subset or creating a session subset.
 *
 * <p>
 * A static subset lists its elements; a dynamic one has an MDX {@code Expression}.
 * Binding {@code Elements} in a subset PATCH <em>appends</em> to them, so {@link #update}
 * refuses it: {@link #addElements} appends by name and {@link #clearElements} empties the
 * list.
 *
 * <p>
 * Create a static subset, binding its elements:
 * {@snippet class = examples.SubsetServiceExamples region = create-static}
 *
 * <p>
 * Create a dynamic subset from an MDX expression:
 * {@snippet class = examples.SubsetServiceExamples region = create-dynamic}
 *
 * <p>
 * Replace a static subset's elements:
 * {@snippet class = examples.SubsetServiceExamples region = replace}
 *
 * <p>
 * Create a session subset and read its elements by the name TM1 gave it:
 * {@snippet class = examples.SubsetServiceExamples region = session}
 */
public final class SubsetService {

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public SubsetService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET …/Hierarchies('h')/Subsets} (or the private or session ones), streamed.
	 * TM1 (11.8) lists no session subsets, though each reads by the name
	 * {@link #createSessionSubset} returned.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param scope which subsets
	 * @param query what to select, expand, filter and order
	 * @return the subsets
	 */
	@Tm1Endpoint("Navigation Hierarchy/Subsets")
	@Tm1Endpoint("Navigation Hierarchy/PrivateSubsets")
	@Tm1Endpoint("Navigation Hierarchy/SessionSubsets")
	public Flux<Subset> list(String dimension, String hierarchy, SubsetScope scope, ODataQuery query) {
		return session.stream(Tm1Request.list(subsets(dimension, hierarchy, scope), query, Subset.class));
	}

	/**
	 * {@code GET …/Subsets('s')}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param scope where the subset is
	 * @param subset the subset's name
	 * @param query what to select and expand
	 * @return the subset, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("Navigation Hierarchy/Subsets")
	@Tm1Endpoint("Navigation Hierarchy/PrivateSubsets")
	@Tm1Endpoint("Navigation Hierarchy/SessionSubsets")
	public Mono<Subset> get(String dimension, String hierarchy, SubsetScope scope, String subset, ODataQuery query) {
		return session.execute(Tm1Request.get(subset(dimension, hierarchy, scope, subset), query, Subset.class));
	}

	/**
	 * {@code POST …/Subsets}: creates a subset: static with {@code Elements@odata.bind}
	 * ({@code SubsetPatch.Builder#bindElements}), dynamic with an {@code Expression}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param scope where to create it
	 * @param subset the new subset; it needs a name
	 * @return the subset as created
	 */
	@Tm1Endpoint("Navigation Hierarchy/Subsets")
	@Tm1Endpoint("Navigation Hierarchy/PrivateSubsets")
	@Tm1Endpoint("Navigation Hierarchy/SessionSubsets")
	public Mono<Subset> create(String dimension, String hierarchy, SubsetScope scope, SubsetPatch subset) {
		return session.execute(Tm1Request.post(subsets(dimension, hierarchy, scope),
				Objects.requireNonNull(subset, "subset"), Subset.class));
	}

	/**
	 * {@code PATCH …/Subsets('s')}: changes the properties the patch sets, such as a
	 * dynamic subset's expression.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param scope where the subset is
	 * @param subset the subset's name
	 * @param patch the changes, without {@code Elements}
	 * @return completes when the subset is changed
	 * @throws IllegalArgumentException if the patch sets or binds {@code Elements}, which
	 * TM1 appends: use {@link #addElements} or {@link #clearElements}
	 */
	@Tm1Endpoint("Navigation Hierarchy/Subsets")
	@Tm1Endpoint("Navigation Hierarchy/PrivateSubsets")
	@Tm1Endpoint("Navigation Hierarchy/SessionSubsets")
	public Mono<Void> update(String dimension, String hierarchy, SubsetScope scope, String subset, SubsetPatch patch) {
		return session.execute(Tm1Request.patch(subset(dimension, hierarchy, scope, subset), withoutElements(patch)));
	}

	/**
	 * {@code PATCH …/Subsets('s')} with {@code If-Match}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param scope where the subset is
	 * @param subset the subset's name
	 * @param patch the changes, without {@code Elements}
	 * @param etag the ETag the subset was read with ({@link Subset#etag()})
	 * @return completes when the subset is changed, or a
	 * {@code Tm1PreconditionFailedException} if it had changed
	 * @throws IllegalArgumentException if the patch sets or binds {@code Elements}
	 */
	@Tm1Endpoint("Navigation Hierarchy/Subsets")
	@Tm1Endpoint("Navigation Hierarchy/PrivateSubsets")
	@Tm1Endpoint("Navigation Hierarchy/SessionSubsets")
	public Mono<Void> update(String dimension, String hierarchy, SubsetScope scope, String subset, SubsetPatch patch,
			String etag) {
		return session.execute(
				Tm1Request.patch(subset(dimension, hierarchy, scope, subset), withoutElements(patch)).ifMatch(etag));
	}

	/**
	 * {@code PATCH …/Subsets('s')} binding {@code Elements}: appends elements to a static
	 * subset, in order.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param scope where the subset is
	 * @param subset the subset's name
	 * @param elements the names of the elements to append
	 * @return completes when they're added
	 */
	@Tm1Endpoint("Navigation Hierarchy/Subsets")
	@Tm1Endpoint("Navigation Hierarchy/PrivateSubsets")
	@Tm1Endpoint("Navigation Hierarchy/SessionSubsets")
	public Mono<Void> addElements(String dimension, String hierarchy, SubsetScope scope, String subset,
			List<String> elements) {
		List<String> bindings = new ArrayList<>();
		for (String element : elements) {
			bindings.add(element(dimension, hierarchy, element).toString());
		}
		return session.execute(
				Tm1Request.patch(subset(dimension, hierarchy, scope, subset), Map.of("Elements@odata.bind", bindings)));
	}

	/**
	 * {@code DELETE …/Subsets('s')/Elements/$ref}: removes every element from a static
	 * subset; the elements stay in the hierarchy.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param scope where the subset is
	 * @param subset the subset's name
	 * @return completes when the subset is empty
	 */
	@Tm1Endpoint("Navigation Subset/Elements")
	public Mono<Void> clearElements(String dimension, String hierarchy, SubsetScope scope, String subset) {
		return session.execute(Tm1Request.delete(subset(dimension, hierarchy, scope, subset).nav("Elements").ref()));
	}

	/**
	 * {@code DELETE …/Subsets('s')}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param scope where the subset is
	 * @param subset the subset's name
	 * @return completes when the subset is deleted
	 */
	@Tm1Endpoint("Navigation Hierarchy/Subsets")
	@Tm1Endpoint("Navigation Hierarchy/PrivateSubsets")
	@Tm1Endpoint("Navigation Hierarchy/SessionSubsets")
	public Mono<Void> delete(String dimension, String hierarchy, SubsetScope scope, String subset) {
		return session.execute(Tm1Request.delete(subset(dimension, hierarchy, scope, subset)));
	}

	/**
	 * {@code GET …/Subsets('s')/Elements}, streamed: a static subset's elements in order,
	 * or what a dynamic one's expression gives now.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param scope where the subset is
	 * @param subset the subset's name
	 * @param query what to select, expand and filter of each element
	 * @return the subset's elements
	 */
	@Tm1Endpoint("Navigation Subset/Elements")
	public Flux<Element> elements(String dimension, String hierarchy, SubsetScope scope, String subset,
			ODataQuery query) {
		return session
			.stream(Tm1Request.list(subset(dimension, hierarchy, scope, subset).nav("Elements"), query, Element.class));
	}

	/**
	 * {@code GET …/Subsets('s')/Hierarchy}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param scope where the subset is
	 * @param subset the subset's name
	 * @param query what to select and expand
	 * @return the subset's hierarchy
	 */
	@Tm1Endpoint("Navigation Subset/Hierarchy")
	public Mono<Hierarchy> hierarchy(String dimension, String hierarchy, SubsetScope scope, String subset,
			ODataQuery query) {
		return session.execute(
				Tm1Request.get(subset(dimension, hierarchy, scope, subset).nav("Hierarchy"), query, Hierarchy.class));
	}

	/**
	 * {@code tm1.SaveAs}: copies a subset under a new name, as public and keeping whether
	 * it's static or dynamic.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param scope where the subset is
	 * @param subset the subset's name
	 * @param newName the copy's name
	 * @return the copy
	 */
	@Tm1Endpoint("Action Subset/SaveAs")
	public Mono<Subset> saveAs(String dimension, String hierarchy, SubsetScope scope, String subset, String newName) {
		return session.execute(action(Tm1Operations.SUBSET_SAVE_AS, subset(dimension, hierarchy, scope, subset),
				Map.of("Name", newName), Subset.class));
	}

	/**
	 * {@code tm1.SaveAs}: copies a subset under a new name.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param scope where the subset is
	 * @param subset the subset's name
	 * @param newName the copy's name
	 * @param makePrivate whether the copy is private
	 * @param makeStatic whether the copy is static, holding the elements the subset has
	 * now
	 * @return the copy
	 */
	@Tm1Endpoint("Action Subset/SaveAs")
	public Mono<Subset> saveAs(String dimension, String hierarchy, SubsetScope scope, String subset, String newName,
			boolean makePrivate, boolean makeStatic) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("Name", newName);
		body.put("MakePrivate", makePrivate);
		body.put("MakeStatic", makeStatic);
		return session.execute(
				action(Tm1Operations.SUBSET_SAVE_AS, subset(dimension, hierarchy, scope, subset), body, Subset.class));
	}

	/**
	 * {@code tm1.SetElement}: adds an element to a static subset, or moves it if it's
	 * there.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param scope where the subset is
	 * @param subset the subset's name
	 * @param element the element's name
	 * @return completes when the element is placed
	 */
	@Tm1Endpoint("Action Subset/SetElement")
	public Mono<Void> setElement(String dimension, String hierarchy, SubsetScope scope, String subset, String element) {
		return sendSetElement(dimension, hierarchy, scope, subset, element, null, null);
	}

	/**
	 * {@code tm1.SetElement} with {@code Before}: places an element just before another
	 * in a static subset.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param scope where the subset is
	 * @param subset the subset's name
	 * @param element the element's name
	 * @param before the name of the element to place it before
	 * @return completes when the element is placed
	 */
	@Tm1Endpoint("Action Subset/SetElement")
	public Mono<Void> setElementBefore(String dimension, String hierarchy, SubsetScope scope, String subset,
			String element, String before) {
		return sendSetElement(dimension, hierarchy, scope, subset, element, "Before@odata.bind",
				Objects.requireNonNull(before, "before"));
	}

	/**
	 * {@code tm1.SetElement} with {@code After}: places an element just after another in
	 * a static subset.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param scope where the subset is
	 * @param subset the subset's name
	 * @param element the element's name
	 * @param after the name of the element to place it after
	 * @return completes when the element is placed
	 */
	@Tm1Endpoint("Action Subset/SetElement")
	public Mono<Void> setElementAfter(String dimension, String hierarchy, SubsetScope scope, String subset,
			String element, String after) {
		return sendSetElement(dimension, hierarchy, scope, subset, element, "After@odata.bind",
				Objects.requireNonNull(after, "after"));
	}

	/**
	 * {@code tm1.CreateSessionSubset} with a definition: creates a subset visible only to
	 * this session. TM1 (11.8) ignores the definition's {@code Name} and names it itself;
	 * the result has the name to use.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param definition the subset: an {@code Expression}, or bound {@code Elements}
	 * @return the session subset, with the name TM1 gave it
	 */
	@Tm1Endpoint("Action Hierarchy/CreateSessionSubset")
	public Mono<Subset> createSessionSubset(String dimension, String hierarchy, SubsetPatch definition) {
		return session.execute(action(Tm1Operations.HIERARCHY_CREATE_SESSION_SUBSET, hierarchy(dimension, hierarchy),
				Map.of("Subset", definition), Subset.class));
	}

	/**
	 * {@code tm1.CreateSessionSubset} with a reference: copies a subset into one visible
	 * only to this session.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param scope where the subset to copy is
	 * @param subset the name of the subset to copy
	 * @return the session subset, with the name TM1 gave it
	 */
	@Tm1Endpoint("Action Hierarchy/CreateSessionSubset")
	public Mono<Subset> createSessionSubsetFrom(String dimension, String hierarchy, SubsetScope scope, String subset) {
		return session.execute(action(Tm1Operations.HIERARCHY_CREATE_SESSION_SUBSET, hierarchy(dimension, hierarchy),
				Map.of("Subset@odata.bind", subset(dimension, hierarchy, scope, subset).toString()), Subset.class));
	}

	private Mono<Void> sendSetElement(String dimension, String hierarchy, SubsetScope scope, String subset,
			String element, @Nullable String position, @Nullable String other) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("Element@odata.bind", element(dimension, hierarchy, element).toString());
		if (position != null && other != null) {
			body.put(position, element(dimension, hierarchy, other).toString());
		}
		return session.execute(action(Tm1Operations.SUBSET_SET_ELEMENT, subset(dimension, hierarchy, scope, subset),
				body, Void.class));
	}

	private static SubsetPatch withoutElements(SubsetPatch patch) {
		if (patch.json().keySet().stream().anyMatch(key -> key.equals("Elements") || key.startsWith("Elements@"))) {
			throw new IllegalArgumentException(
					"A subset PATCH with Elements appends to them: use addElements, or clearElements first");
		}
		return patch;
	}

	private static ODataPath hierarchy(String dimension, String hierarchy) {
		return ODataPath.of("Dimensions")
			.key(Objects.requireNonNull(dimension, "dimension"))
			.nav("Hierarchies")
			.key(Objects.requireNonNull(hierarchy, "hierarchy"));
	}

	private static ODataPath subsets(String dimension, String hierarchy, SubsetScope scope) {
		return hierarchy(dimension, hierarchy).nav(scope.navigation());
	}

	private static ODataPath subset(String dimension, String hierarchy, SubsetScope scope, String subset) {
		return subsets(dimension, hierarchy, scope).key(Objects.requireNonNull(subset, "subset"));
	}

	private static ODataPath element(String dimension, String hierarchy, String element) {
		return hierarchy(dimension, hierarchy).nav("Elements").key(Objects.requireNonNull(element, "element"));
	}

	private static <T> Tm1Request<T> action(Tm1Operation operation, ODataPath target, Map<String, ?> body,
			Class<T> type) {
		return Tm1Request.post(target.action(operation.name()), body, type).requires(operation.capability());
	}

}

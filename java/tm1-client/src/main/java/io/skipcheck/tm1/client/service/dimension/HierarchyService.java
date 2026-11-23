// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.dimension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.skipcheck.tm1.client.Tm1Operation;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.Dimension;
import io.skipcheck.tm1.client.model.EdgePatch;
import io.skipcheck.tm1.client.model.ElementPatch;
import io.skipcheck.tm1.client.model.Hierarchy;
import io.skipcheck.tm1.client.model.HierarchyPatch;
import io.skipcheck.tm1.client.model.Member;
import io.skipcheck.tm1.client.model.Tm1Operations;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Hierarchies: {@code /Dimensions('d')/Hierarchies}, a dimension's default hierarchy, and
 * the operations bound to a hierarchy. Elements, edges, members, levels and subsets have
 * services of their own.
 *
 * <p>
 * A hierarchy PATCH that sets {@code Elements} or {@code Edges} <em>replaces</em> them:
 * TM1 deletes the elements it doesn't list. So {@link #update} refuses such a patch, and
 * {@link #replaceStructure} does it by name.
 *
 * <p>
 * Add a hierarchy to a dimension, with its elements and edges:
 * {@snippet class = examples.HierarchyServiceExamples region = create}
 *
 * <p>
 * Replace a hierarchy's structure, knowing that TM1 deletes what isn't listed:
 * {@snippet class = examples.HierarchyServiceExamples region = replace}
 *
 * <p>
 * Read the roots of a hierarchy:
 * {@snippet class = examples.HierarchyServiceExamples region = roots}
 */
public final class HierarchyService {

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public HierarchyService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET Dimensions('d')/Hierarchies}, streamed.
	 * @param dimension the dimension's name
	 * @param query what to select, expand, filter and order
	 * @return the dimension's hierarchies
	 */
	@Tm1Endpoint("Navigation Dimension/Hierarchies")
	public Flux<Hierarchy> list(String dimension, ODataQuery query) {
		return session.stream(Tm1Request.list(hierarchies(dimension), query, Hierarchy.class));
	}

	/**
	 * {@code GET Dimensions('d')/Hierarchies('h')}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param query what to select and expand
	 * @return the hierarchy, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("Navigation Dimension/Hierarchies")
	public Mono<Hierarchy> get(String dimension, String hierarchy, ODataQuery query) {
		return session.execute(Tm1Request.get(hierarchy(dimension, hierarchy), query, Hierarchy.class));
	}

	/**
	 * {@code POST Dimensions('d')/Hierarchies}: adds a hierarchy, with its elements and
	 * edges if the patch has them.
	 * @param dimension the dimension's name
	 * @param hierarchy the new hierarchy; it needs a name
	 * @return the hierarchy as created
	 */
	@Tm1Endpoint("Navigation Dimension/Hierarchies")
	public Mono<Hierarchy> create(String dimension, HierarchyPatch hierarchy) {
		return session.execute(Tm1Request.post(hierarchies(dimension), hierarchy, Hierarchy.class));
	}

	/**
	 * {@code PATCH Dimensions('d')/Hierarchies('h')}: changes the properties the patch
	 * sets. TM1 (11.8) ignores {@code Visible}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param patch the changes, without {@code Elements} or {@code Edges}
	 * @return completes when the hierarchy is changed
	 * @throws IllegalArgumentException if the patch sets {@code Elements} or
	 * {@code Edges}, which would replace them: use {@link #replaceStructure}
	 */
	@Tm1Endpoint("Navigation Dimension/Hierarchies")
	public Mono<Void> update(String dimension, String hierarchy, HierarchyPatch patch) {
		return session.execute(Tm1Request.patch(hierarchy(dimension, hierarchy), withoutStructure(patch)));
	}

	/**
	 * {@code PATCH Dimensions('d')/Hierarchies('h')} with {@code If-Match}: changes the
	 * properties the patch sets, unless the hierarchy changed since it was read.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param patch the changes, without {@code Elements} or {@code Edges}
	 * @param etag the ETag the hierarchy was read with ({@link Hierarchy#etag()})
	 * @return completes when the hierarchy is changed, or a
	 * {@code Tm1PreconditionFailedException} if it had changed
	 * @throws IllegalArgumentException if the patch sets {@code Elements} or
	 * {@code Edges}: use {@link #replaceStructure}
	 */
	@Tm1Endpoint("Navigation Dimension/Hierarchies")
	public Mono<Void> update(String dimension, String hierarchy, HierarchyPatch patch, String etag) {
		return session
			.execute(Tm1Request.patch(hierarchy(dimension, hierarchy), withoutStructure(patch)).ifMatch(etag));
	}

	/**
	 * {@code PATCH Dimensions('d')/Hierarchies('h')} with {@code Elements} and
	 * {@code Edges}: makes them exactly these. TM1 deletes every element not listed, with
	 * its data.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param elements every element the hierarchy should have
	 * @param edges every parent-child edge it should have
	 * @return completes when the hierarchy is replaced
	 */
	@Tm1Endpoint("Navigation Dimension/Hierarchies")
	public Mono<Void> replaceStructure(String dimension, String hierarchy, List<ElementPatch> elements,
			List<EdgePatch> edges) {
		HierarchyPatch patch = HierarchyPatch.builder().elements(elements).edges(edges).build();
		return session.execute(Tm1Request.patch(hierarchy(dimension, hierarchy), patch));
	}

	/**
	 * {@code DELETE Dimensions('d')/Hierarchies('h')}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @return completes when the hierarchy is deleted
	 */
	@Tm1Endpoint("Navigation Dimension/Hierarchies")
	public Mono<Void> delete(String dimension, String hierarchy) {
		return session.execute(Tm1Request.delete(hierarchy(dimension, hierarchy)));
	}

	/**
	 * {@code GET Dimensions('d')/DefaultHierarchy}.
	 * @param dimension the dimension's name
	 * @param query what to select and expand
	 * @return the dimension's default hierarchy
	 */
	@Tm1Endpoint("Navigation Dimension/DefaultHierarchy")
	public Mono<Hierarchy> defaultHierarchy(String dimension, ODataQuery query) {
		return session.execute(Tm1Request.get(dimension(dimension).nav("DefaultHierarchy"), query, Hierarchy.class));
	}

	/**
	 * {@code GET Dimensions('d')/Hierarchies('h')/Dimension}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param query what to select and expand
	 * @return the hierarchy's dimension
	 */
	@Tm1Endpoint("Navigation Hierarchy/Dimension")
	public Mono<Dimension> dimension(String dimension, String hierarchy, ODataQuery query) {
		return session
			.execute(Tm1Request.get(hierarchy(dimension, hierarchy).nav("Dimension"), query, Dimension.class));
	}

	/**
	 * {@code GET Dimensions('d')/Hierarchies('h')/AllMember}: the member that stands for
	 * the whole hierarchy. TM1 (11.8) has one only when the hierarchy has a single root,
	 * which is then that root; with several roots it sends no content, and this completes
	 * empty.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param query what to select and expand
	 * @return the all member, or empty if the hierarchy has several roots
	 */
	@Tm1Endpoint("Navigation Hierarchy/AllMember")
	public Mono<Member> allMember(String dimension, String hierarchy, ODataQuery query) {
		return session.execute(Tm1Request.get(hierarchy(dimension, hierarchy).nav("AllMember"), query, Member.class));
	}

	/**
	 * {@code GET Dimensions('d')/Hierarchies('h')/DefaultMember}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param query what to select and expand
	 * @return the default member
	 */
	@Tm1Endpoint("Navigation Hierarchy/DefaultMember")
	public Mono<Member> defaultMember(String dimension, String hierarchy, ODataQuery query) {
		return session
			.execute(Tm1Request.get(hierarchy(dimension, hierarchy).nav("DefaultMember"), query, Member.class));
	}

	/**
	 * {@code tm1.RootMembers()}: the members with no parent, streamed. Needs schema
	 * 11.4.7.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param query what to select and expand of each member
	 * @return the root members
	 */
	@Tm1Endpoint("Function Hierarchy/RootMembers")
	public Flux<Member> rootMembers(String dimension, String hierarchy, ODataQuery query) {
		Tm1Operation operation = Tm1Operations.HIERARCHY_ROOT_MEMBERS;
		return session.stream(Tm1Request
			.list(hierarchy(dimension, hierarchy).function(operation.name(), Map.of()), query, Member.class)
			.requires(operation.capability()));
	}

	/**
	 * {@code tm1.SaveAs}: copies a hierarchy under a new name in the same dimension,
	 * failing if one exists.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param newName the copy's name
	 * @return the copy
	 */
	@Tm1Endpoint("Action Hierarchy/SaveAs")
	public Mono<Hierarchy> saveAs(String dimension, String hierarchy, String newName) {
		return session.execute(action(Tm1Operations.HIERARCHY_SAVE_AS, dimension, hierarchy, Map.of("Name", newName),
				Hierarchy.class));
	}

	/**
	 * {@code tm1.SaveAs}: copies a hierarchy under a new name in the same dimension.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param newName the copy's name
	 * @param overwrite whether to replace a hierarchy that already has the new name
	 * @param keepExistingAttributes when overwriting, whether to keep the attributes of
	 * what's replaced; when not, true creates no attributes for the copy
	 * @return the copy
	 */
	@Tm1Endpoint("Action Hierarchy/SaveAs")
	public Mono<Hierarchy> saveAs(String dimension, String hierarchy, String newName, boolean overwrite,
			boolean keepExistingAttributes) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("Name", newName);
		body.put("Overwrite", overwrite);
		body.put("KeepExistingAttributes", keepExistingAttributes);
		return session.execute(action(Tm1Operations.HIERARCHY_SAVE_AS, dimension, hierarchy, body, Hierarchy.class));
	}

	/**
	 * {@code tm1.SetElement} with no {@code Before}: makes an element a root of the
	 * hierarchy, placed <em>last</em> among the roots. ({@code $metadata} says first; TM1
	 * 11.8 puts it last.)
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param element the element's name
	 * @return completes when the element is placed
	 */
	@Tm1Endpoint("Action Hierarchy/SetElement")
	public Mono<Void> setElement(String dimension, String hierarchy, String element) {
		return session.execute(action(Tm1Operations.HIERARCHY_SET_ELEMENT, dimension, hierarchy,
				Map.of("Element@odata.bind", element(dimension, hierarchy, element).toString()), Void.class));
	}

	/**
	 * {@code tm1.SetElement}: makes an element a root of the hierarchy, placed just
	 * before another root.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param element the element's name
	 * @param before the name of the root to place it before
	 * @return completes when the element is placed
	 */
	@Tm1Endpoint("Action Hierarchy/SetElement")
	public Mono<Void> setElement(String dimension, String hierarchy, String element, String before) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("Element@odata.bind", element(dimension, hierarchy, element).toString());
		body.put("Before@odata.bind", element(dimension, hierarchy, before).toString());
		return session.execute(action(Tm1Operations.HIERARCHY_SET_ELEMENT, dimension, hierarchy, body, Void.class));
	}

	private static HierarchyPatch withoutStructure(HierarchyPatch patch) {
		// Bindings to the collections (Elements@odata.bind) are structure changes too
		if (patch.json()
			.keySet()
			.stream()
			.anyMatch(key -> key.equals("Elements") || key.equals("Edges") || key.startsWith("Elements@")
					|| key.startsWith("Edges@"))) {
			throw new IllegalArgumentException(
					"A hierarchy PATCH with Elements or Edges replaces them, deleting what it doesn't list: use replaceStructure");
		}
		return patch;
	}

	private static ODataPath dimension(String dimension) {
		return ODataPath.of("Dimensions").key(Objects.requireNonNull(dimension, "dimension"));
	}

	private static ODataPath hierarchies(String dimension) {
		return dimension(dimension).nav("Hierarchies");
	}

	private static ODataPath hierarchy(String dimension, String hierarchy) {
		return hierarchies(dimension).key(Objects.requireNonNull(hierarchy, "hierarchy"));
	}

	private static ODataPath element(String dimension, String hierarchy, String element) {
		return hierarchy(dimension, hierarchy).nav("Elements").key(Objects.requireNonNull(element, "element"));
	}

	private static <T> Tm1Request<T> action(Tm1Operation operation, String dimension, String hierarchy,
			Map<String, ?> body, Class<T> type) {
		return Tm1Request.post(hierarchy(dimension, hierarchy).action(operation.name()), body, type)
			.requires(operation.capability());
	}

}

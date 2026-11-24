// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.dimension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.skipcheck.tm1.client.Tm1Operation;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.Dimension;
import io.skipcheck.tm1.client.model.DimensionCreate;
import io.skipcheck.tm1.client.model.DimensionPatch;
import io.skipcheck.tm1.client.model.Hierarchy;
import io.skipcheck.tm1.client.model.Tm1Operations;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Dimensions: {@code /Dimensions} and the operations bound to a dimension.
 *
 * <p>
 * A dimension's hierarchies, elements and subsets have services of their own.
 *
 * <p>
 * List dimensions with a query: here, the names of the model dimensions.
 * {@snippet class = examples.DimensionServiceExamples region = list}
 *
 * <p>
 * Create a dimension with its hierarchy, elements and edges in one request:
 * {@snippet class = examples.DimensionServiceExamples region = create}
 *
 * <p>
 * Read a dimension with its hierarchies expanded:
 * {@snippet class = examples.DimensionServiceExamples region = hierarchies}
 */
public final class DimensionService {

	private static final ODataPath DIMENSIONS = ODataPath.of("Dimensions");

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public DimensionService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET Dimensions}: every dimension, control dimensions included, streamed.
	 * @param query what to select, expand, filter and order
	 * @return the dimensions
	 */
	@Tm1Endpoint("EntitySet Dimensions")
	public Flux<Dimension> list(ODataQuery query) {
		return session.stream(Tm1Request.list(DIMENSIONS, query, Dimension.class));
	}

	/**
	 * {@code ModelDimensions()}: the dimensions that aren't control dimensions (whose
	 * names start with <code>&#125;</code>), streamed.
	 * @param query what to select, expand, filter and order
	 * @return the model dimensions
	 */
	@Tm1Endpoint("FunctionImport ModelDimensions")
	public Flux<Dimension> modelDimensions(ODataQuery query) {
		return session
			.stream(Tm1Request.list(ODataPath.functionImport("ModelDimensions", Map.of()), query, Dimension.class));
	}

	/**
	 * {@code ControlDimensions()}: the control dimensions, such as
	 * <code>&#125;Clients</code>, streamed.
	 * @param query what to select, expand, filter and order
	 * @return the control dimensions
	 */
	@Tm1Endpoint("FunctionImport ControlDimensions")
	public Flux<Dimension> controlDimensions(ODataQuery query) {
		return session
			.stream(Tm1Request.list(ODataPath.functionImport("ControlDimensions", Map.of()), query, Dimension.class));
	}

	/**
	 * {@code GET Dimensions('name')}.
	 * @param name the dimension's name
	 * @param query what to select and expand
	 * @return the dimension, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("EntitySet Dimensions")
	public Mono<Dimension> get(String name, ODataQuery query) {
		return session.execute(Tm1Request.get(dimension(name), query, Dimension.class));
	}

	/**
	 * {@code POST Dimensions}: creates a dimension, with its hierarchies, elements and
	 * edges if the patch has them (a deep insert).
	 * @param dimension the new dimension; it needs a name
	 * @return the dimension as created
	 */
	@Tm1Endpoint("EntitySet Dimensions")
	public Mono<Dimension> create(DimensionCreate dimension) {
		return session.execute(Tm1Request.post(DIMENSIONS, dimension, Dimension.class));
	}

	/**
	 * {@code PATCH Dimensions('name')}: changes the properties the patch sets.
	 *
	 * <p>
	 * TM1 (11.8) accepts {@code Hierarchies} and {@code AllLeavesHierarchyName} in a
	 * dimension PATCH and ignores them: change hierarchies through their own service, and
	 * add an all-leaves hierarchy with {@link #addAllLeavesHierarchy}.
	 * @param name the dimension's name
	 * @param patch the changes
	 * @return completes when the dimension is changed
	 */
	@Tm1Endpoint("EntitySet Dimensions")
	public Mono<Void> update(String name, DimensionPatch patch) {
		return session.execute(Tm1Request.patch(dimension(name), patch));
	}

	/**
	 * {@code PATCH Dimensions('name')} with {@code If-Match}: changes the properties the
	 * patch sets, unless the dimension changed since it was read.
	 * @param name the dimension's name
	 * @param patch the changes
	 * @param etag the ETag the dimension was read with ({@link Dimension#etag()})
	 * @return completes when the dimension is changed, or a
	 * {@code Tm1PreconditionFailedException} if it had changed
	 */
	@Tm1Endpoint("EntitySet Dimensions")
	public Mono<Void> update(String name, DimensionPatch patch, String etag) {
		return session.execute(Tm1Request.patch(dimension(name), patch).ifMatch(etag));
	}

	/**
	 * {@code DELETE Dimensions('name')}.
	 * @param name the dimension's name
	 * @return completes when the dimension is deleted
	 */
	@Tm1Endpoint("EntitySet Dimensions")
	public Mono<Void> delete(String name) {
		return session.execute(Tm1Request.delete(dimension(name)));
	}

	/**
	 * {@code tm1.SaveAs}: copies a dimension under a new name, failing if one exists.
	 * @param name the dimension's name
	 * @param newName the copy's name
	 * @return the copy
	 */
	@Tm1Endpoint("Action Dimension/SaveAs")
	public Mono<Dimension> saveAs(String name, String newName) {
		return session.execute(action(Tm1Operations.DIMENSION_SAVE_AS, name, Map.of("Name", newName), Dimension.class));
	}

	/**
	 * {@code tm1.SaveAs}: copies a dimension under a new name.
	 * @param name the dimension's name
	 * @param newName the copy's name
	 * @param overwrite whether to replace a dimension that already has the new name
	 * @param keepExistingAttributes when overwriting, whether to keep the attributes of
	 * what's replaced; when not, true creates no attributes for the copy
	 * @return the copy
	 */
	@Tm1Endpoint("Action Dimension/SaveAs")
	public Mono<Dimension> saveAs(String name, String newName, boolean overwrite, boolean keepExistingAttributes) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("Name", newName);
		body.put("Overwrite", overwrite);
		body.put("KeepExistingAttributes", keepExistingAttributes);
		return session.execute(action(Tm1Operations.DIMENSION_SAVE_AS, name, body, Dimension.class));
	}

	/**
	 * {@code tm1.AddAllLeavesHierarchy}: adds a hierarchy of all the dimension's leaves,
	 * named {@code Leaves} and not visible, which makes it a multi-hierarchy dimension.
	 * @param name the dimension's name
	 * @return the new hierarchy
	 */
	@Tm1Endpoint("Action Dimension/AddAllLeavesHierarchy")
	public Mono<Hierarchy> addAllLeavesHierarchy(String name) {
		return session
			.execute(action(Tm1Operations.DIMENSION_ADD_ALL_LEAVES_HIERARCHY, name, Map.of(), Hierarchy.class));
	}

	/**
	 * {@code tm1.AddAllLeavesHierarchy}: adds a hierarchy of all the dimension's leaves,
	 * which makes it a multi-hierarchy dimension.
	 * @param name the dimension's name
	 * @param hierarchyName the new hierarchy's name
	 * @param visible whether the new hierarchy is visible
	 * @return the new hierarchy
	 */
	@Tm1Endpoint("Action Dimension/AddAllLeavesHierarchy")
	public Mono<Hierarchy> addAllLeavesHierarchy(String name, String hierarchyName, boolean visible) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("AllLeavesHierarchyName", hierarchyName);
		body.put("Visible", visible);
		return session.execute(action(Tm1Operations.DIMENSION_ADD_ALL_LEAVES_HIERARCHY, name, body, Hierarchy.class));
	}

	/**
	 * {@code tm1.Lock}: stops everyone else changing the dimension until it's unlocked.
	 * @param name the dimension's name
	 * @return completes when the dimension is locked
	 */
	@Tm1Endpoint("Action Dimension/Lock")
	public Mono<Void> lock(String name) {
		return session.execute(action(Tm1Operations.DIMENSION_LOCK, name, Map.of(), Void.class));
	}

	/**
	 * {@code tm1.Unlock}.
	 * @param name the dimension's name
	 * @return completes when the dimension is unlocked
	 */
	@Tm1Endpoint("Action Dimension/Unlock")
	public Mono<Void> unlock(String name) {
		return session.execute(action(Tm1Operations.DIMENSION_UNLOCK, name, Map.of(), Void.class));
	}

	/**
	 * {@code tm1.Reserve}: stops everyone else changing the dimension until it's
	 * released. Needs schema 11.8.34.
	 * @param name the dimension's name
	 * @return completes when the dimension is reserved
	 */
	@Tm1Endpoint("Action Dimension/Reserve")
	public Mono<Void> reserve(String name) {
		return session.execute(action(Tm1Operations.DIMENSION_RESERVE, name, Map.of(), Void.class));
	}

	/**
	 * {@code tm1.Release}. Needs schema 11.8.34.
	 * @param name the dimension's name
	 * @return completes when the dimension is released
	 */
	@Tm1Endpoint("Action Dimension/Release")
	public Mono<Void> release(String name) {
		return session.execute(action(Tm1Operations.DIMENSION_RELEASE, name, Map.of(), Void.class));
	}

	private static ODataPath dimension(String name) {
		return DIMENSIONS.key(Objects.requireNonNull(name, "name"));
	}

	private static <T> Tm1Request<T> action(Tm1Operation operation, String name, Map<String, ?> body, Class<T> type) {
		return Tm1Request.post(dimension(name).action(operation.name()), body, type).requires(operation.capability());
	}

}

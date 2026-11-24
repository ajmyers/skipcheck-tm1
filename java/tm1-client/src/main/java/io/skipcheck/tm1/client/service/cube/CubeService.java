// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.cube;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.skipcheck.tm1.client.Tm1Operation;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.CalculationComponent;
import io.skipcheck.tm1.client.model.CheckFeedersSelection;
import io.skipcheck.tm1.client.model.Cube;
import io.skipcheck.tm1.client.model.CubeCreate;
import io.skipcheck.tm1.client.model.CubePatch;
import io.skipcheck.tm1.client.model.Dimension;
import io.skipcheck.tm1.client.model.FedCellDescriptor;
import io.skipcheck.tm1.client.model.FeederTrace;
import io.skipcheck.tm1.client.model.RuleSyntaxError;
import io.skipcheck.tm1.client.model.Tm1Operations;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * Cubes: {@code /Cubes}, a cube's dimensions, and the operations bound to a cube. Views
 * have a service of their own; cell data is read and written through cellsets.
 *
 * <p>
 * List cubes with a query: here, the model cubes whose names start with "Sales".
 * {@snippet class = examples.CubeServiceExamples region = list}
 *
 * <p>
 * Create a cube over existing dimensions, bound in order:
 * {@snippet class = examples.CubeServiceExamples region = create}
 *
 * <p>
 * Change a cube's rules, checking them first:
 * {@snippet class = examples.CubeServiceExamples region = update-rules}
 */
public final class CubeService {

	private static final ODataPath CUBES = ODataPath.of("Cubes");

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public CubeService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET Cubes}: every cube, control cubes included, streamed.
	 * @param query what to select, expand, filter and order
	 * @return the cubes
	 */
	@Tm1Endpoint("EntitySet Cubes")
	public Flux<Cube> list(ODataQuery query) {
		return session.stream(Tm1Request.list(CUBES, query, Cube.class));
	}

	/**
	 * {@code ModelCubes()}: the cubes that aren't control cubes (whose names start with
	 * <code>&#125;</code>), streamed.
	 * @param query what to select, expand, filter and order
	 * @return the model cubes
	 */
	@Tm1Endpoint("FunctionImport ModelCubes")
	public Flux<Cube> modelCubes(ODataQuery query) {
		return session.stream(Tm1Request.list(ODataPath.functionImport("ModelCubes", Map.of()), query, Cube.class));
	}

	/**
	 * {@code ControlCubes()}: the control cubes, such as <code>&#125;ClientGroups</code>,
	 * streamed.
	 * @param query what to select, expand, filter and order
	 * @return the control cubes
	 */
	@Tm1Endpoint("FunctionImport ControlCubes")
	public Flux<Cube> controlCubes(ODataQuery query) {
		return session.stream(Tm1Request.list(ODataPath.functionImport("ControlCubes", Map.of()), query, Cube.class));
	}

	/**
	 * {@code GET Cubes('name')}.
	 * @param name the cube's name
	 * @param query what to select and expand
	 * @return the cube, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("EntitySet Cubes")
	public Mono<Cube> get(String name, ODataQuery query) {
		return session.execute(Tm1Request.get(cube(name), query, Cube.class));
	}

	/**
	 * {@code POST Cubes}: creates a cube over existing dimensions, bound in order with
	 * {@code CubePatch.Builder#bindDimensions}, and optionally its rules.
	 * @param cube the new cube; it needs a name and at least two dimensions
	 * @return the cube as created
	 */
	@Tm1Endpoint("EntitySet Cubes")
	public Mono<Cube> create(CubeCreate cube) {
		return session.execute(Tm1Request.post(CUBES, cube, Cube.class));
	}

	/**
	 * {@code PATCH Cubes('name')}: changes the properties the patch sets, typically
	 * {@code Rules} or {@code DrillthroughRules}; {@code null} clears them.
	 *
	 * <p>
	 * TM1 (11.8) saves rules even when they don't compile: check them first with
	 * {@link #checkRules(String, String)}.
	 * @param name the cube's name
	 * @param patch the changes
	 * @return completes when the cube is changed
	 */
	@Tm1Endpoint("EntitySet Cubes")
	public Mono<Void> update(String name, CubePatch patch) {
		return session.execute(Tm1Request.patch(cube(name), patch));
	}

	/**
	 * {@code PATCH Cubes('name')} with {@code If-Match}: changes the properties the patch
	 * sets, unless the cube changed since it was read. TM1 (11.8) saves rules even when
	 * they don't compile.
	 * @param name the cube's name
	 * @param patch the changes
	 * @param etag the ETag the cube was read with ({@link Cube#etag()})
	 * @return completes when the cube is changed, or a
	 * {@code Tm1PreconditionFailedException} if it had changed
	 */
	@Tm1Endpoint("EntitySet Cubes")
	public Mono<Void> update(String name, CubePatch patch, String etag) {
		return session.execute(Tm1Request.patch(cube(name), patch).ifMatch(etag));
	}

	/**
	 * {@code DELETE Cubes('name')}, with its data and views; its dimensions stay.
	 * @param name the cube's name
	 * @return completes when the cube is deleted
	 */
	@Tm1Endpoint("EntitySet Cubes")
	public Mono<Void> delete(String name) {
		return session.execute(Tm1Request.delete(cube(name)));
	}

	/**
	 * {@code GET Cubes('name')/Dimensions}, streamed, in the cube's order.
	 *
	 * <p>
	 * TM1 (11.8) lists its virtual {@code Sandboxes} dimension first, before the
	 * dimensions the cube was made of; skip it to get those.
	 * @param name the cube's name
	 * @param query what to select, expand, filter and order
	 * @return the cube's dimensions, {@code Sandboxes} first
	 */
	@Tm1Endpoint("Navigation Cube/Dimensions")
	public Flux<Dimension> dimensions(String name, ODataQuery query) {
		return session.stream(Tm1Request.list(cube(name).nav("Dimensions"), query, Dimension.class));
	}

	/**
	 * {@code tm1.DimensionsStorageOrder()}: the cube's dimensions in the order TM1 stores
	 * them, which can differ from the order they're presented in. Needs schema 11.4.0.
	 * @param name the cube's name
	 * @param query what to select of each dimension
	 * @return the dimensions in storage order
	 */
	@Tm1Endpoint("Function Cube/DimensionsStorageOrder")
	public Flux<Dimension> dimensionsStorageOrder(String name, ODataQuery query) {
		Tm1Operation operation = Tm1Operations.CUBE_DIMENSIONS_STORAGE_ORDER;
		return session.stream(Tm1Request.list(cube(name).function(operation.name(), Map.of()), query, Dimension.class)
			.requires(operation.capability()));
	}

	/**
	 * {@code tm1.ReorderDimensions}: changes the order TM1 stores the cube's dimensions
	 * in, which changes its memory use but not how it's presented. Needs schema 11.4.0.
	 * @param name the cube's name
	 * @param dimensions the cube's dimensions, in the new storage order
	 * @return the change in the cube's memory use, as TM1 reports it (a percentage)
	 */
	@Tm1Endpoint("Action Cube/ReorderDimensions")
	public Mono<Double> reorderDimensions(String name, List<String> dimensions) {
		List<String> bindings = new ArrayList<>();
		for (String dimension : dimensions) {
			bindings.add(ODataPath.of("Dimensions").key(dimension).toString());
		}
		return session
			.execute(action(Tm1Operations.CUBE_REORDER_DIMENSIONS, name, Map.of("Dimensions@odata.bind", bindings),
					JsonNode.class))
			// A primitive result comes wrapped, as {"@odata.context": ..., "value": 12.5}
			.map(result -> result.path("value").asDouble());
	}

	/**
	 * {@code tm1.Load}: loads the cube into memory, if it isn't. Needs schema 11.6.0.
	 * @param name the cube's name
	 * @return completes when the cube is loaded
	 */
	@Tm1Endpoint("Action Cube/Load")
	public Mono<Void> load(String name) {
		return session.execute(action(Tm1Operations.CUBE_LOAD, name, Map.of(), Void.class));
	}

	/**
	 * {@code tm1.Unload}: frees the cube's memory; TM1 loads it again when it's next
	 * used. Needs schema 11.6.0.
	 * @param name the cube's name
	 * @return completes when the cube is unloaded
	 */
	@Tm1Endpoint("Action Cube/Unload")
	public Mono<Void> unload(String name) {
		return session.execute(action(Tm1Operations.CUBE_UNLOAD, name, Map.of(), Void.class));
	}

	/**
	 * {@code tm1.Lock}: stops everyone else changing the cube until it's unlocked.
	 * @param name the cube's name
	 * @return completes when the cube is locked
	 */
	@Tm1Endpoint("Action Cube/Lock")
	public Mono<Void> lock(String name) {
		return session.execute(action(Tm1Operations.CUBE_LOCK, name, Map.of(), Void.class));
	}

	/**
	 * {@code tm1.Unlock}.
	 * @param name the cube's name
	 * @return completes when the cube is unlocked
	 */
	@Tm1Endpoint("Action Cube/Unlock")
	public Mono<Void> unlock(String name) {
		return session.execute(action(Tm1Operations.CUBE_UNLOCK, name, Map.of(), Void.class));
	}

	/**
	 * {@code tm1.Reserve}: stops everyone else changing the cube until it's released.
	 * Needs schema 11.8.34.
	 * @param name the cube's name
	 * @return completes when the cube is reserved
	 */
	@Tm1Endpoint("Action Cube/Reserve")
	public Mono<Void> reserve(String name) {
		return session.execute(action(Tm1Operations.CUBE_RESERVE, name, Map.of(), Void.class));
	}

	/**
	 * {@code tm1.Release}. Needs schema 11.8.34.
	 * @param name the cube's name
	 * @return completes when the cube is released
	 */
	@Tm1Endpoint("Action Cube/Release")
	public Mono<Void> release(String name) {
		return session.execute(action(Tm1Operations.CUBE_RELEASE, name, Map.of(), Void.class));
	}

	/**
	 * {@code tm1.CheckRules}: compiles the cube's saved rules and lists their errors.
	 * Needs schema 11.1.0.
	 * @param name the cube's name
	 * @return the syntax errors, with line numbers; none if the rules compile
	 */
	@Tm1Endpoint("Action Cube/CheckRules")
	public Flux<RuleSyntaxError> checkRules(String name) {
		Tm1Operation operation = Tm1Operations.CUBE_CHECK_RULES;
		return session.stream(Tm1Request.postList(cube(name).action(operation.name()), Map.of(), RuleSyntaxError.class)
			.requires(operation.capability()));
	}

	/**
	 * {@code tm1.CheckRules} with {@code Rules}: compiles rules for the cube without
	 * saving them, so they can be checked before {@link #update} saves them (which TM1
	 * does even when they don't compile). Needs schema 11.1.0.
	 * @param name the cube's name
	 * @param rules the rules to check
	 * @return the syntax errors, with line numbers; none if the rules compile
	 */
	@Tm1Endpoint("Action Cube/CheckRules")
	public Flux<RuleSyntaxError> checkRules(String name, String rules) {
		Tm1Operation operation = Tm1Operations.CUBE_CHECK_RULES;
		return session.stream(
				Tm1Request
					.postList(cube(name).action(operation.name()),
							Map.of("Rules", Objects.requireNonNull(rules, "rules")), RuleSyntaxError.class)
					.requires(operation.capability()));
	}

	/**
	 * {@code tm1.TraceCellCalculation}: how a cell's value is calculated, as a tree of
	 * rule and consolidation components. Needs schema 11.1.0.
	 * @param name the cube's name
	 * @param tuple the cell: dimension name to element name, for every dimension of the
	 * cube in its order (a {@code LinkedHashMap}); each element is in its dimension's
	 * same-named hierarchy
	 * @param query what to expand of the result, such as {@code Components},
	 * {@code Tuple} or {@code Cube}
	 * @return the calculation's root component
	 */
	@Tm1Endpoint("Action Cube/TraceCellCalculation")
	@Tm1Endpoint("Navigation CalculationComponent/Cube")
	@Tm1Endpoint("Navigation CalculationComponent/Tuple")
	public Mono<CalculationComponent> traceCellCalculation(String name, Map<String, String> tuple, ODataQuery query) {
		return session.execute(action(Tm1Operations.CUBE_TRACE_CELL_CALCULATION, name,
				Map.of("Tuple@odata.bind", bindings(tuple)), CalculationComponent.class)
			.withQuery(query));
	}

	/**
	 * {@code tm1.TraceFeeders}: which cells a cell feeds, and the feeder statements that
	 * do it. Needs schema 11.1.0.
	 * @param name the cube's name
	 * @param tuple the cell, as for {@link #traceCellCalculation}
	 * @param query what to expand of the result, such as {@code FedCells/Tuple}:
	 * {@code FedCells} is a complex property, so TM1 wants a path, not nested options
	 * ({@code FedCells($expand=Tuple)} gets 400)
	 * @return the trace
	 */
	@Tm1Endpoint("Action Cube/TraceFeeders")
	@Tm1Endpoint("Navigation FedCellDescriptor/Cube")
	@Tm1Endpoint("Navigation FedCellDescriptor/Tuple")
	public Mono<FeederTrace> traceFeeders(String name, Map<String, String> tuple, ODataQuery query) {
		return session.execute(action(Tm1Operations.CUBE_TRACE_FEEDERS, name,
				Map.of("Tuple@odata.bind", bindings(tuple)), FeederTrace.class)
			.withQuery(query));
	}

	/**
	 * {@code tm1.CheckFeeders}: the cells under a consolidated cell that are fed, not
	 * fed, or both, to find missing feeders. Needs schema 11.1.0.
	 * @param name the cube's name
	 * @param tuple the cell, as for {@link #traceCellCalculation}
	 * @param selection which cells to list
	 * @param query what to expand of each cell, such as {@code Tuple} or {@code Cube}
	 * @return the cells, streamed
	 */
	@Tm1Endpoint("Action Cube/CheckFeeders")
	@Tm1Endpoint("Navigation FedCellDescriptor/Cube")
	@Tm1Endpoint("Navigation FedCellDescriptor/Tuple")
	public Flux<FedCellDescriptor> checkFeeders(String name, Map<String, String> tuple, CheckFeedersSelection selection,
			ODataQuery query) {
		Tm1Operation operation = Tm1Operations.CUBE_CHECK_FEEDERS;
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("Tuple@odata.bind", bindings(tuple));
		body.put("Selection", Objects.requireNonNull(selection.json(), "selection"));
		return session.stream(Tm1Request.postList(cube(name).action(operation.name()), body, FedCellDescriptor.class)
			.withQuery(query)
			.requires(operation.capability()));
	}

	private static List<String> bindings(Map<String, String> tuple) {
		List<String> bindings = new ArrayList<>();
		tuple.forEach((dimension,
				element) -> bindings.add(ODataPath.of("Dimensions")
					.key(dimension)
					.nav("Hierarchies")
					.key(dimension)
					.nav("Elements")
					.key(element)
					.toString()));
		return bindings;
	}

	private static ODataPath cube(String name) {
		return CUBES.key(Objects.requireNonNull(name, "name"));
	}

	private static <T> Tm1Request<T> action(Tm1Operation operation, String name, Map<String, ?> body, Class<T> type) {
		return Tm1Request.post(cube(name).action(operation.name()), body, type).requires(operation.capability());
	}

}

package net.ajmyers.tm1.client.service.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import net.ajmyers.tm1.client.Tm1Operation;
import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.model.CubeUpdatePatch;
import net.ajmyers.tm1.client.model.Tm1Operations;
import net.ajmyers.tm1.client.model.UpdateOrder;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.service.Tm1Endpoint;
import net.ajmyers.tm1.client.session.Tm1Session;
import reactor.core.publisher.Mono;

/**
 * Cell writes to a cube, by coordinates. Cells are given as {@link Tuples#of} maps of
 * dimension to element; reading is through cellsets ({@link CellsetService}), and writing
 * into one by ordinal is there too.
 *
 * <p>
 * Values are numbers for numeric cells and text for string cells; {@code null} clears a
 * cell. TM1 (11.8) refuses a write to a consolidated cell (400,
 * {@code CubeCellWriteStatusElementIsConsolidated}): it doesn't spread; write the leaves.
 *
 * <p>
 * {@code tm1.Update} takes several cells, but TM1 (11.8) writes only the first, so
 * {@link #update} takes one; {@link #updateCells} writes many in one request.
 *
 * <p>
 * Write one cell, given an element for every dimension of the cube, in order:
 * {@snippet class = examples.CellServiceExamples region = update}
 *
 * <p>
 * Write many cells in one request:
 * {@snippet class = examples.CellServiceExamples region = update-cells}
 */
public final class CellService {

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public CellService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code tm1.Update}: writes one value to a cell.
	 * @param cube the cube's name
	 * @param tuple the cell: dimension to element, as for {@link Tuples#of}
	 * @param value the value, or {@code null} to clear the cell
	 * @return completes when it's written
	 */
	@Tm1Endpoint("Action Cube/Update")
	@Tm1Endpoint("Navigation CellDescriptor/Tuple")
	public Mono<Void> update(String cube, Map<String, String> tuple, @Nullable Object value) {
		Map<String, @Nullable Object> body = new LinkedHashMap<>();
		body.put("Cells", List.of(Map.of("Tuple@odata.bind", bindings(Tuples.of(tuple)))));
		body.put("Value", value);
		return session.execute(action(Tm1Operations.CUBE_UPDATE, cube, body));
	}

	/**
	 * {@code tm1.UpdateCells}: writes many cells, each its own value, in one request.
	 * Needs schema 11.0.0. TM1 (11.8) requires a {@code Tuple} and a {@code Value} in
	 * every update (400 otherwise). The patch can also bind a {@code ReferenceCube} and
	 * {@code ReferenceCell}; on a leaf they didn't change what's written, and they don't
	 * copy that cell's value.
	 * @param cube the cube's name
	 * @param updates the writes:
	 * {@code CubeUpdatePatch.builder().bindTuple(Tuples.of(cell)).value(v)}, or with a
	 * reference cube and cell to copy from
	 * @return completes when they're written
	 */
	@Tm1Endpoint("Action Cube/UpdateCells")
	@Tm1Endpoint("Navigation CubeUpdate/Tuple")
	@Tm1Endpoint("Navigation CubeUpdate/ReferenceCube")
	@Tm1Endpoint("Navigation CubeUpdate/ReferenceCell")
	public Mono<Void> updateCells(String cube, List<CubeUpdatePatch> updates) {
		return session.execute(action(Tm1Operations.CUBE_UPDATE_CELLS, cube, Map.of("Updates", List.copyOf(updates))));
	}

	/**
	 * {@code tm1.UpdateCells} with an order: {@code ByLevel} writes leaves before the
	 * consolidations above them, so a spread to a consolidation isn't overwritten. Needs
	 * schema 11.0.0.
	 * @param cube the cube's name
	 * @param updates the writes
	 * @param order the order to apply them in
	 * @return completes when they're written
	 */
	@Tm1Endpoint("Action Cube/UpdateCells")
	@Tm1Endpoint("Navigation CubeUpdate/Tuple")
	@Tm1Endpoint("Navigation CubeUpdate/ReferenceCube")
	@Tm1Endpoint("Navigation CubeUpdate/ReferenceCell")
	public Mono<Void> updateCells(String cube, List<CubeUpdatePatch> updates, UpdateOrder order) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("Updates", List.copyOf(updates));
		body.put("Order", Objects.requireNonNull(order.json(), "order"));
		return session.execute(action(Tm1Operations.CUBE_UPDATE_CELLS, cube, body));
	}

	private static List<String> bindings(ODataPath[] paths) {
		List<String> bindings = new ArrayList<>();
		for (ODataPath path : paths) {
			bindings.add(path.toString());
		}
		return bindings;
	}

	private static Tm1Request<Void> action(Tm1Operation operation, String cube, Map<String, ?> body) {
		return Tm1Request
			.post(ODataPath.of("Cubes").key(Objects.requireNonNull(cube, "cube")).action(operation.name()), body,
					Void.class)
			.requires(operation.capability());
	}

}

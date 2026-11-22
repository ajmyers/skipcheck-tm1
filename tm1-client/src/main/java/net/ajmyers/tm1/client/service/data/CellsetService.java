package net.ajmyers.tm1.client.service.data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;

import net.ajmyers.tm1.client.Tm1Operation;
import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.model.Cellset;
import net.ajmyers.tm1.client.model.CellsetAxis;
import net.ajmyers.tm1.client.model.CellsetAxisTuple;
import net.ajmyers.tm1.client.model.CellsetCell;
import net.ajmyers.tm1.client.model.CellsetUpdatePatch;
import net.ajmyers.tm1.client.model.Cube;
import net.ajmyers.tm1.client.model.Hierarchy;
import net.ajmyers.tm1.client.model.Member;
import net.ajmyers.tm1.client.model.Tm1Operations;
import net.ajmyers.tm1.client.model.View;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.service.Tm1Endpoint;
import net.ajmyers.tm1.client.service.cube.ViewScope;
import net.ajmyers.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Cellsets: the server-side results of an MDX query or a view, with their axes and cells.
 *
 * <p>
 * A cellset lives on the server until it's deleted, or the session ends; one that isn't
 * deleted holds the server's memory. The 1:1 methods ({@link #execute}, {@link #cells},
 * {@link #partition}, {@link #delete}) leave that to the caller. {@link #stream} does it
 * all: runs the query, pages the cells in order, and deletes the cellset when the result
 * completes, fails or is cancelled.
 *
 * <p>
 * Read the cells of an MDX query, placing each by its ordinal on the axes:
 * {@snippet class = examples.CellsetServiceExamples region = stream}
 *
 * <p>
 * Write into the cells of a query by ordinal, deleting its cellset after:
 * {@snippet class = examples.CellsetServiceExamples region = write}
 *
 * <p>
 * Read the values of a saved view:
 * {@snippet class = examples.CellsetServiceExamples region = view}
 */
public final class CellsetService {

	private static final ODataPath CELLSETS = ODataPath.of("Cellsets");

	/** How many pages {@link #stream} fetches at once, beyond the first. */
	private static final int PAGES_IN_FLIGHT = 2;

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public CellsetService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code ExecuteMDX}: runs an MDX query, leaving its cellset on the server until
	 * {@link #delete}. Expand {@code Axes} and {@code Cells} (with {@code $top} and
	 * {@code $select}) to get them in the same round trip.
	 * @param mdx the query
	 * @param query what to expand of the cellset
	 * @return the cellset, with its {@code ID}
	 */
	@Tm1Endpoint("ActionImport ExecuteMDX")
	public Mono<Cellset> execute(String mdx, ODataQuery query) {
		Tm1Operation operation = Tm1Operations.EXECUTE_MDX;
		return session.execute(Tm1Request
			.post(ODataPath.of(Objects.requireNonNull(operation.importName())),
					Map.of("MDX", Objects.requireNonNull(mdx, "mdx")), Cellset.class)
			.withQuery(query)
			.requires(operation.capability()));
	}

	/**
	 * {@code ExecuteMDXStatement}: runs an MDX statement for its effect, keeping no
	 * cellset. Needs schema 11.8.20. TM1 (11.8) runs a {@code SELECT} and returns
	 * nothing, and rejects {@code CREATE SET} as a syntax error (code 501).
	 * @param mdx the statement
	 * @return completes when it's run
	 */
	@Tm1Endpoint("ActionImport ExecuteMDXStatement")
	public Mono<Void> executeStatement(String mdx) {
		Tm1Operation operation = Tm1Operations.EXECUTE_MDX_STATEMENT;
		return session
			.execute(Tm1Request
				.post(ODataPath.of(Objects.requireNonNull(operation.importName())),
						Map.of("MDX", Objects.requireNonNull(mdx, "mdx")), Void.class)
				.requires(operation.capability()));
	}

	/**
	 * {@code tm1.Execute} on a native or MDX view: its cellset, left on the server until
	 * {@link #delete}.
	 * @param cube the cube's name
	 * @param scope where the view is
	 * @param view the view's name
	 * @param query what to expand of the cellset
	 * @return the cellset, with its {@code ID}
	 */
	@Tm1Endpoint("Action NativeView/Execute")
	@Tm1Endpoint("Action MDXView/Execute")
	public Mono<Cellset> executeView(String cube, ViewScope scope, String view, ODataQuery query) {
		ODataPath path = ODataPath.of("Cubes")
			.key(Objects.requireNonNull(cube, "cube"))
			.nav(scope.navigation())
			.key(Objects.requireNonNull(view, "view"))
			.action(Tm1Operations.NATIVE_VIEW_EXECUTE.name());
		return session.execute(Tm1Request.post(path, Map.of(), Cellset.class).withQuery(query));
	}

	/**
	 * {@code GET Cellsets}, streamed. TM1 (11.8) lists none, though each cellset reads by
	 * its ID with {@link #get}: keep the IDs of the cellsets you create.
	 * @param query what to select and expand
	 * @return the cellsets
	 */
	@Tm1Endpoint("EntitySet Cellsets")
	public Flux<Cellset> list(ODataQuery query) {
		return session.stream(Tm1Request.list(CELLSETS, query, Cellset.class));
	}

	/**
	 * {@code GET Cellsets('id')}.
	 * @param id the cellset's ID
	 * @param query what to expand
	 * @return the cellset, or a {@code Tm1NotFoundException} if it's gone
	 */
	@Tm1Endpoint("EntitySet Cellsets")
	public Mono<Cellset> get(String id, ODataQuery query) {
		return session.execute(Tm1Request.get(cellset(id), query, Cellset.class));
	}

	/**
	 * {@code DELETE Cellsets('id')}: frees the cellset's memory on the server.
	 * @param id the cellset's ID
	 * @return completes when it's deleted
	 */
	@Tm1Endpoint("EntitySet Cellsets")
	public Mono<Void> delete(String id) {
		return session.execute(Tm1Request.delete(cellset(id)));
	}

	/**
	 * {@code GET Cellsets('id')/Axes}, streamed: columns (0), rows (1), and so on, then
	 * the slicer. TM1 (11.8) adds the slicer even without a {@code WHERE}: it holds the
	 * cube's other dimensions, such as its virtual {@code Sandboxes}, with cardinality 1.
	 * @param id the cellset's ID
	 * @param query what to expand, such as {@code Tuples($expand=Members)}
	 * @return the axes
	 */
	@Tm1Endpoint("Navigation Cellset/Axes")
	public Flux<CellsetAxis> axes(String id, ODataQuery query) {
		return session.stream(Tm1Request.list(cellset(id).nav("Axes"), query, CellsetAxis.class));
	}

	/**
	 * {@code GET Cellsets('id')/Axes(n)/Hierarchies}, streamed.
	 * @param id the cellset's ID
	 * @param axis the axis ordinal
	 * @param query what to select
	 * @return the hierarchies on the axis
	 */
	@Tm1Endpoint("Navigation CellsetAxis/Hierarchies")
	public Flux<Hierarchy> hierarchies(String id, int axis, ODataQuery query) {
		return session.stream(Tm1Request.list(axis(id, axis).nav("Hierarchies"), query, Hierarchy.class));
	}

	/**
	 * {@code GET Cellsets('id')/Axes(n)/Tuples}, streamed, in order: an axis can be
	 * large.
	 * @param id the cellset's ID
	 * @param axis the axis ordinal
	 * @param query what to expand, such as {@code Members($select=Name)}
	 * @return the axis's tuples
	 */
	@Tm1Endpoint("Navigation CellsetAxis/Tuples")
	public Flux<CellsetAxisTuple> tuples(String id, int axis, ODataQuery query) {
		return session.stream(Tm1Request.list(axis(id, axis).nav("Tuples"), query, CellsetAxisTuple.class));
	}

	/**
	 * {@code GET Cellsets('id')/Axes(n)/Tuples(t)/Members}, streamed: one member per
	 * hierarchy on the axis.
	 * @param id the cellset's ID
	 * @param axis the axis ordinal
	 * @param tuple the tuple ordinal
	 * @param query what to select
	 * @return the tuple's members
	 */
	@Tm1Endpoint("Navigation CellsetAxisTuple/Members")
	public Flux<Member> tupleMembers(String id, int axis, int tuple, ODataQuery query) {
		return session
			.stream(Tm1Request.list(axis(id, axis).nav("Tuples").key(tuple).nav("Members"), query, Member.class));
	}

	/**
	 * {@code GET Cellsets('id')/Cells}, streamed, in ordinal order. Use {@code $top} and
	 * {@code $skip}, or {@link #partition}, for pages.
	 * @param id the cellset's ID
	 * @param query what to select of each cell ({@code Ordinal}, {@code Value}, …)
	 * @return the cells
	 */
	@Tm1Endpoint("Navigation Cellset/Cells")
	public Flux<CellsetCell> cells(String id, ODataQuery query) {
		return session.stream(Tm1Request.list(cellset(id).nav("Cells"), query, CellsetCell.class));
	}

	/**
	 * {@code GET Cellsets('id')/Cells(n)/Members}, streamed: the members that place a
	 * cell, one per hierarchy of the cube. TM1 (11.8) includes the virtual
	 * {@code Sandboxes} dimension's, {@code Base}, first.
	 * @param id the cellset's ID
	 * @param ordinal the cell's ordinal
	 * @param query what to select
	 * @return the cell's members
	 */
	@Tm1Endpoint("Navigation CellsetCell/Members")
	public Flux<Member> cellMembers(String id, long ordinal, ODataQuery query) {
		return session
			.stream(Tm1Request.list(cellset(id).nav("Cells").key(ordinal).nav("Members"), query, Member.class));
	}

	/**
	 * {@code GET Cellsets('id')/Cube}.
	 * @param id the cellset's ID
	 * @param query what to select and expand
	 * @return the cube the cellset is from
	 */
	@Tm1Endpoint("Navigation Cellset/Cube")
	public Mono<Cube> cube(String id, ODataQuery query) {
		return session.execute(Tm1Request.get(cellset(id).nav("Cube"), query, Cube.class));
	}

	/**
	 * {@code tm1.GetPartition(Begin=b,End=e)}: a rectangle of cells, streamed. Needs
	 * schema 10.2.2.3.
	 *
	 * <p>
	 * TM1 (11.8) reads {@code Begin} and {@code End} as the top-left and bottom-right
	 * cells of a rectangle on the first two axes, not as a range of ordinals: with two
	 * columns, {@code partition(id, 2, 4, …)} is column 0 of rows 1 and 2, cells 2 and 4,
	 * not 3. A run of whole rows, from a row's first cell to a row's last, is a range.
	 * @param id the cellset's ID
	 * @param begin the ordinal of the rectangle's top-left cell
	 * @param end the ordinal of its bottom-right cell
	 * @param query what to select of each cell; across pages, keep it the same
	 * @return the cells in the rectangle
	 */
	@Tm1Endpoint("Function Cellset/GetPartition")
	public Flux<CellsetCell> partition(String id, long begin, long end, ODataQuery query) {
		Tm1Operation operation = Tm1Operations.CELLSET_GET_PARTITION;
		Map<String, Object> range = new LinkedHashMap<>();
		range.put("Begin", begin);
		range.put("End", end);
		return session.stream(Tm1Request.list(cellset(id).function(operation.name(), range), query, CellsetCell.class)
			.requires(operation.capability()));
	}

	/**
	 * {@code tm1.Update}: writes one value to a cell of the cellset, by ordinal. The
	 * action takes a range ({@code BeginOrdinal}, {@code EndOrdinal}), but TM1 (11.8)
	 * writes only the first cell of it, so this takes one ordinal; {@link #updateCells}
	 * writes many.
	 * @param id the cellset's ID
	 * @param ordinal the cell's ordinal
	 * @param value the value, or {@code null} to clear the cell
	 * @return completes when it's written
	 */
	@Tm1Endpoint("Action Cellset/Update")
	public Mono<Void> update(String id, long ordinal, @Nullable Object value) {
		Map<String, @Nullable Object> body = new LinkedHashMap<>();
		body.put("BeginOrdinal", ordinal);
		body.put("EndOrdinal", ordinal);
		body.put("Value", value);
		return session.execute(cellsetAction(Tm1Operations.CELLSET_UPDATE, id, body, Void.class));
	}

	/**
	 * {@code tm1.UpdateCells}: writes many of the cellset's cells, each its own value, by
	 * ordinal. Needs schema 11.0.0.
	 * @param id the cellset's ID
	 * @param updates the writes:
	 * {@code CellsetUpdatePatch.builder().ordinal(n).value(v)}, or with a reference cube
	 * and cell to copy from
	 * @return completes when they're written
	 */
	@Tm1Endpoint("Action Cellset/UpdateCells")
	@Tm1Endpoint("Navigation CellsetUpdate/ReferenceCube")
	@Tm1Endpoint("Navigation CellsetUpdate/ReferenceCell")
	public Mono<Void> updateCells(String id, List<CellsetUpdatePatch> updates) {
		return session.execute(cellsetAction(Tm1Operations.CELLSET_UPDATE_CELLS, id,
				Map.of("Updates", List.copyOf(updates)), Void.class));
	}

	/**
	 * {@code tm1.SaveViewAs}: saves the cellset's query as a public view of its cube,
	 * failing if one has the name. Needs schema 11.0.0.
	 * @param id the cellset's ID
	 * @param name the view's name
	 * @return the view
	 */
	@Tm1Endpoint("Action Cellset/SaveViewAs")
	public Mono<View> saveViewAs(String id, String name) {
		return session.execute(cellsetAction(Tm1Operations.CELLSET_SAVE_VIEW_AS, id,
				Map.of("Name", Objects.requireNonNull(name, "name")), View.class));
	}

	/**
	 * {@code tm1.SaveViewAs}: saves the cellset's query as a view of its cube. Needs
	 * schema 11.0.0.
	 * @param id the cellset's ID
	 * @param name the view's name
	 * @param overwrite whether to replace a view that has the name
	 * @param makePrivate whether the view is private
	 * @return the view
	 */
	@Tm1Endpoint("Action Cellset/SaveViewAs")
	public Mono<View> saveViewAs(String id, String name, boolean overwrite, boolean makePrivate) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("Name", Objects.requireNonNull(name, "name"));
		body.put("Overwrite", overwrite);
		body.put("MakePrivate", makePrivate);
		return session.execute(cellsetAction(Tm1Operations.CELLSET_SAVE_VIEW_AS, id, body, View.class));
	}

	private static <T> Tm1Request<T> cellsetAction(Tm1Operation operation, String id, Map<String, ?> body,
			Class<T> type) {
		return Tm1Request.post(cellset(id).action(operation.name()), body, type).requires(operation.capability());
	}

	/**
	 * Runs an MDX query and streams its cells to {@code handler}, then deletes the
	 * cellset: when the result completes, fails, or is cancelled (plan §7).
	 *
	 * <p>
	 * One round trip brings the axes (hierarchies by name, tuples with their members'
	 * names and unique names) and the first {@code pageSize} cells; the rest come in
	 * pages of {@code GetPartition}, a few at a time and in ordinal order, so memory
	 * stays bounded on both sides however large the cellset.
	 *
	 * {@snippet class = examples.CellsetServiceExamples region = stream}
	 * @param <R> what the handler gives
	 * @param mdx the query
	 * @param cellQuery what to select of each cell; include {@code Ordinal} to place them
	 * @param pageSize how many cells to fetch per request
	 * @param handler given the cellset (its {@code ID} and expanded {@code Axes}) and its
	 * cells, in ordinal order; its result is this method's
	 * @return what the handler gives, with the cellset deleted at the end
	 */
	@Tm1Endpoint("ActionImport ExecuteMDX")
	@Tm1Endpoint("Function Cellset/GetPartition")
	@Tm1Endpoint("EntitySet Cellsets")
	public <R> Flux<R> stream(String mdx, ODataQuery cellQuery, int pageSize,
			BiFunction<Cellset, Flux<CellsetCell>, Publisher<R>> handler) {
		if (pageSize < 1) {
			throw new IllegalArgumentException("pageSize must be positive: " + pageSize);
		}
		ODataQuery first = ODataQuery.none()
			.expand("Axes",
					a -> a.expand("Hierarchies", h -> h.select("Name"))
						.expand("Tuples", t -> t.expand("Members", m -> m.select("Name", "UniqueName"))))
			.expand("Cells", ignored -> cellQuery.top(pageSize));
		return Flux.usingWhen(execute(mdx, first),
				cellset -> handler.apply(cellset, cells(cellset, cellQuery, pageSize)),
				CellsetService.this::deleteQuietly, (cellset, error) -> deleteQuietly(cellset),
				CellsetService.this::deleteQuietly);
	}

	/** Deletes a streamed cellset; one TM1 sent without an ID has nothing to delete. */
	private Mono<Void> deleteQuietly(Cellset cellset) {
		String id = cellset.id();
		return id == null ? Mono.empty() : delete(id);
	}

	/**
	 * The cells of a streamed cellset: the first page, which came inline, then the rest.
	 * {@code GetPartition} takes a rectangle, so with two axes the pages are whole rows:
	 * the first page's last row, if it came incomplete, is dropped and fetched again with
	 * the next. With more axes, rows aren't rectangles, so pages come from {@code Cells}
	 * with {@code $skip} and {@code $top}.
	 */
	private Flux<CellsetCell> cells(Cellset cellset, ODataQuery cellQuery, int pageSize) {
		List<CellsetCell> inline = cellset.cells() == null ? List.of() : cellset.cells();
		List<CellsetAxis> axes = cellset.axes() == null ? List.of() : cellset.axes();
		long total = 1;
		for (CellsetAxis axis : axes) {
			total *= cardinality(axis);
		}
		if (inline.size() >= total) {
			return Flux.fromIterable(inline);
		}
		String id = Objects.requireNonNull(cellset.id(), "cellset ID");
		long cells = total;
		if (axes.stream().skip(2).anyMatch(axis -> cardinality(axis) > 1)) {
			long from = inline.size();
			return Flux.fromIterable(inline)
				.concatWith(Flux.range(0, (int) ((cells - from + pageSize - 1) / pageSize))
					.flatMapSequential(
							page -> cells(id, cellQuery.skip((int) (from + (long) page * pageSize)).top(pageSize)),
							PAGES_IN_FLIGHT));
		}
		long columns = axes.isEmpty() ? 1 : cardinality(axes.get(0));
		long rowsPerPage = Math.max(1, pageSize / columns);
		long firstRow = inline.size() / columns;
		long rows = cells / columns;
		List<CellsetCell> wholeRows = inline.subList(0, (int) (firstRow * columns));
		Flux<CellsetCell> rest = Flux.range(0, (int) ((rows - firstRow + rowsPerPage - 1) / rowsPerPage))
			.flatMapSequential(page -> {
				long top = firstRow + page * rowsPerPage;
				long bottom = Math.min(top + rowsPerPage, rows) - 1;
				return partition(id, top * columns, bottom * columns + columns - 1, cellQuery);
			}, PAGES_IN_FLIGHT);
		return Flux.fromIterable(wholeRows).concatWith(rest);
	}

	private static long cardinality(CellsetAxis axis) {
		return axis.cardinality() == null ? 1 : axis.cardinality();
	}

	private static ODataPath cellset(String id) {
		return CELLSETS.key(Objects.requireNonNull(id, "id"));
	}

	private static ODataPath axis(String id, int axis) {
		return cellset(id).nav("Axes").key(axis);
	}

}

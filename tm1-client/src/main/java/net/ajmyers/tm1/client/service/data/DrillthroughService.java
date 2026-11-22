package net.ajmyers.tm1.client.service.data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.ajmyers.tm1.client.Tm1Operation;
import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.model.Cellset;
import net.ajmyers.tm1.client.model.Drillthrough;
import net.ajmyers.tm1.client.model.ProcessParameterPatch;
import net.ajmyers.tm1.client.model.Rowset;
import net.ajmyers.tm1.client.model.RowsetRow;
import net.ajmyers.tm1.client.model.Tm1Operations;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.service.Tm1Endpoint;
import net.ajmyers.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * Drillthrough: from a cell to the detail behind it, through a drillthrough process
 * (<code>&#125;Drill_…</code>). A cube drillthrough returns a cellset, of a view the
 * process returns; a relational one returns rows from an ODBC source, directly or kept as
 * a {@code Rowset}.
 *
 * <p>
 * A cell's drillthroughs come from the cube's {@code DrillthroughRules}, which name the
 * process; {@link #scripts} lists them for a cell of a cellset. TM1 (11.8) passes such a
 * process the cube's name and the cell's element on each of its dimensions, so the
 * process must declare that many parameters (the cube's name, then one per dimension, in
 * order); otherwise running the cell's drillthrough fails with "process parameter count
 * error" (code 248). Run directly ({@link #executeProcess}), it takes the parameters
 * given. The returned cellsets and rowsets stay on the server until deleted, like any
 * cellset.
 *
 * <p>
 * {@code $metadata} declares a relational row ({@code DrillthroughRow}) with no
 * properties and not open, so its columns would all be lost in a record: relational rows
 * come as {@code JsonNode}. A rowset's rows ({@code RowsetRow}) are open, and keep theirs
 * in {@code dynamicProperties}.
 *
 * <p>
 * Drill from a cell of a query to the detail behind it:
 * {@snippet class = examples.DrillthroughServiceExamples region = cell}
 *
 * <p>
 * Run a drillthrough process directly, with its parameters:
 * {@snippet class = examples.DrillthroughServiceExamples region = process}
 *
 * <p>
 * Read the rows of a relational drillthrough as a rowset:
 * {@snippet class = examples.DrillthroughServiceExamples region = rowset}
 */
public final class DrillthroughService {

	private static final ODataPath ROWSETS = ODataPath.of("Rowsets");

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public DrillthroughService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET Cellsets('id')/Cells(n)/DrillthroughScripts}: the drillthroughs a cell
	 * has, streamed, each a {@code CubeDrillthrough} or {@code RelationalDrillthrough}.
	 * @param cellset the cellset's ID
	 * @param ordinal the cell's ordinal
	 * @param query what to select
	 * @return the drillthroughs, by name
	 */
	@Tm1Endpoint("Navigation CellsetCell/DrillthroughScripts")
	public Flux<Drillthrough> scripts(String cellset, long ordinal, ODataQuery query) {
		return session.stream(Tm1Request.list(scripts(cellset, ordinal), query, Drillthrough.class));
	}

	/**
	 * {@code tm1.Execute} on a cell's cube drillthrough: the cellset of the view its
	 * process returns, left on the server until deleted. Needs schema 10.2.2.6. The
	 * process must take the cube's name and one parameter per dimension (see the class
	 * description).
	 * @param cellset the cellset's ID
	 * @param ordinal the cell's ordinal
	 * @param drillthrough the drillthrough's name, from {@link #scripts}
	 * @param query what to expand of the cellset
	 * @return the cellset
	 */
	@Tm1Endpoint("Action CubeDrillthrough/Execute")
	public Mono<Cellset> execute(String cellset, long ordinal, String drillthrough, ODataQuery query) {
		Tm1Operation operation = Tm1Operations.CUBE_DRILLTHROUGH_EXECUTE;
		return session.execute(Tm1Request
			.post(script(cellset, ordinal, drillthrough).action(operation.name()), Map.of(), Cellset.class)
			.withQuery(query)
			.requires(operation.capability()));
	}

	/**
	 * {@code tm1.Execute} on a cell's relational drillthrough: its rows, streamed. Needs
	 * schema 10.2.2.6.
	 * @param cellset the cellset's ID
	 * @param ordinal the cell's ordinal
	 * @param drillthrough the drillthrough's name, from {@link #scripts}
	 * @return the rows, as JSON
	 */
	@Tm1Endpoint("Action RelationalDrillthrough/Execute")
	public Flux<JsonNode> executeRelational(String cellset, long ordinal, String drillthrough) {
		Tm1Operation operation = Tm1Operations.RELATIONAL_DRILLTHROUGH_EXECUTE;
		return session.stream(Tm1Request
			.postList(script(cellset, ordinal, drillthrough).action(operation.name()), Map.of(), JsonNode.class)
			.requires(operation.capability()));
	}

	/**
	 * {@code tm1.ExecuteWithRowset} on a cell's relational drillthrough: its rows, kept
	 * as a rowset to read with {@link #rows}. Needs schema 11.6.0.
	 * @param cellset the cellset's ID
	 * @param ordinal the cell's ordinal
	 * @param drillthrough the drillthrough's name, from {@link #scripts}
	 * @return the rowset, with its {@code ID}
	 */
	@Tm1Endpoint("Action RelationalDrillthrough/ExecuteWithRowset")
	public Mono<Rowset> executeRelationalWithRowset(String cellset, long ordinal, String drillthrough) {
		Tm1Operation operation = Tm1Operations.RELATIONAL_DRILLTHROUGH_EXECUTE_WITH_ROWSET;
		return session.execute(
				Tm1Request.post(script(cellset, ordinal, drillthrough).action(operation.name()), Map.of(), Rowset.class)
					.requires(operation.capability()));
	}

	/**
	 * {@code ExecuteCubeDrillthrough}: runs a cube drillthrough process directly, with
	 * its parameters, and returns the cellset of the view it returns. Needs schema
	 * 11.2.0.
	 * @param process the drillthrough process's name, such as
	 * <code>&#125;Drill_Sales</code>
	 * @param parameters the process's parameters, by name and value
	 * @param query what to expand of the cellset
	 * @return the cellset, left on the server until deleted
	 */
	@Tm1Endpoint("ActionImport ExecuteCubeDrillthrough")
	public Mono<Cellset> executeProcess(String process, List<ProcessParameterPatch> parameters, ODataQuery query) {
		Tm1Operation operation = Tm1Operations.EXECUTE_CUBE_DRILLTHROUGH;
		return session.execute(Tm1Request.post(root(operation), processBody(process, parameters), Cellset.class)
			.withQuery(query)
			.requires(operation.capability()));
	}

	/**
	 * {@code ExecuteRelationalDrillthrough}: runs a relational drillthrough process
	 * directly. Needs schema 11.2.0.
	 * @param process the drillthrough process's name
	 * @param parameters the process's parameters
	 * @return the rows, as JSON, streamed
	 */
	@Tm1Endpoint("ActionImport ExecuteRelationalDrillthrough")
	public Flux<JsonNode> executeRelationalProcess(String process, List<ProcessParameterPatch> parameters) {
		Tm1Operation operation = Tm1Operations.EXECUTE_RELATIONAL_DRILLTHROUGH;
		return session.stream(Tm1Request.postList(root(operation), processBody(process, parameters), JsonNode.class)
			.requires(operation.capability()));
	}

	/**
	 * {@code ExecuteRelationalDrillthroughWithRowset}: runs a relational drillthrough
	 * process directly, keeping its rows as a rowset. Needs schema 11.6.0.
	 * @param process the drillthrough process's name
	 * @param parameters the process's parameters
	 * @return the rowset, with its {@code ID}
	 */
	@Tm1Endpoint("ActionImport ExecuteRelationalDrillthroughWithRowset")
	public Mono<Rowset> executeRelationalProcessWithRowset(String process, List<ProcessParameterPatch> parameters) {
		Tm1Operation operation = Tm1Operations.EXECUTE_RELATIONAL_DRILLTHROUGH_WITH_ROWSET;
		return session.execute(Tm1Request.post(root(operation), processBody(process, parameters), Rowset.class)
			.requires(operation.capability()));
	}

	/**
	 * {@code GET Rowsets('id')}.
	 * @param id the rowset's ID
	 * @param query what to expand
	 * @return the rowset
	 */
	@Tm1Endpoint("EntitySet Rowsets")
	public Mono<Rowset> rowset(String id, ODataQuery query) {
		return session.execute(Tm1Request.get(ROWSETS.key(Objects.requireNonNull(id, "id")), query, Rowset.class));
	}

	/**
	 * {@code GET Rowsets('id')/Rows}, streamed: each row's columns are in its
	 * {@code dynamicProperties}.
	 * @param id the rowset's ID
	 * @param query what to select, filter, and page ({@code $top}, {@code $skip})
	 * @return the rows
	 */
	@Tm1Endpoint("Navigation Rowset/Rows")
	public Flux<RowsetRow> rows(String id, ODataQuery query) {
		return session
			.stream(Tm1Request.list(ROWSETS.key(Objects.requireNonNull(id, "id")).nav("Rows"), query, RowsetRow.class));
	}

	/**
	 * {@code DELETE Rowsets('id')}: frees the rowset on the server.
	 * @param id the rowset's ID
	 * @return completes when it's deleted
	 */
	@Tm1Endpoint("EntitySet Rowsets")
	public Mono<Void> deleteRowset(String id) {
		return session.execute(Tm1Request.delete(ROWSETS.key(Objects.requireNonNull(id, "id"))));
	}

	private static Map<String, Object> processBody(String process, List<ProcessParameterPatch> parameters) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("DrillthroughProcess@odata.bind",
				ODataPath.of("Processes").key(Objects.requireNonNull(process, "process")).toString());
		if (!parameters.isEmpty()) {
			body.put("Parameters", List.copyOf(parameters));
		}
		return body;
	}

	private static ODataPath root(Tm1Operation operation) {
		return ODataPath.of(Objects.requireNonNull(operation.importName()));
	}

	private static ODataPath scripts(String cellset, long ordinal) {
		return ODataPath.of("Cellsets")
			.key(Objects.requireNonNull(cellset, "cellset"))
			.nav("Cells")
			.key(ordinal)
			.nav("DrillthroughScripts");
	}

	private static ODataPath script(String cellset, long ordinal, String drillthrough) {
		return scripts(cellset, ordinal).key(Objects.requireNonNull(drillthrough, "drillthrough"));
	}

}

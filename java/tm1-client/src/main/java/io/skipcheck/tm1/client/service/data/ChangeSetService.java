package io.skipcheck.tm1.client.service.data;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.reactivestreams.Publisher;

import io.skipcheck.tm1.client.Tm1Operation;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.Tm1Operations;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * Change sets: groups of cell writes that can be undone together.
 *
 * <p>
 * A write joins a change set through TM1's {@code !ChangeSet} query option, which a
 * session view from {@link Tm1Session#withChangeSet} adds to every request
 * ({@code $metadata} doesn't say how; TM1 11.8 does it this way). {@link #within} does it
 * all: begins a change set, gives the work a session that records into it, and ends it
 * however the work finishes.
 *
 * <p>
 * Make some writes in a change set, keeping its ID:
 * {@snippet class = examples.ChangeSetServiceExamples region = within}
 *
 * <p>
 * Undo them later:
 * {@snippet class = examples.ChangeSetServiceExamples region = undo}
 */
public final class ChangeSetService {

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public ChangeSetService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code BeginChangeSet}: starts a change set. Needs schema 10.2.2.6.
	 * @return the change set's ID, for {@link Tm1Session#withChangeSet}
	 */
	@Tm1Endpoint("ActionImport BeginChangeSet")
	public Mono<String> begin() {
		return session.execute(request(Tm1Operations.BEGIN_CHANGE_SET, Map.of()))
			// A primitive result comes wrapped, as {"value": "3"}
			.map(result -> result.path("value").asString());
	}

	/**
	 * {@code EndChangeSet}: stops recording into a change set; it can still be undone.
	 * Needs schema 10.2.2.6.
	 * @param id the change set's ID
	 * @return TM1's return code
	 */
	@Tm1Endpoint("ActionImport EndChangeSet")
	public Mono<Long> end(String id) {
		return session
			.execute(request(Tm1Operations.END_CHANGE_SET, Map.of("ChangeSetID", Objects.requireNonNull(id, "id"))))
			.map(result -> result.path("value").asLong());
	}

	/**
	 * {@code UndoChangeSet}: reverts every write recorded in a change set. Needs schema
	 * 10.2.2.6.
	 * @param id the change set's ID
	 * @return completes when the writes are reverted
	 */
	@Tm1Endpoint("ActionImport UndoChangeSet")
	public Mono<Void> undo(String id) {
		Tm1Operation operation = Tm1Operations.UNDO_CHANGE_SET;
		return session.execute(Tm1Request
			.post(ODataPath.of(Objects.requireNonNull(operation.importName())),
					Map.of("ChangeSetID", Objects.requireNonNull(id, "id")), Void.class)
			.requires(operation.capability()));
	}

	/**
	 * Runs {@code work} in a new change set: begins it, gives the work a view of the
	 * session that records into it ({@code session.changeSet()} has the ID, for a later
	 * {@link #undo}), and ends it when the work completes, fails or is cancelled.
	 *
	 * {@snippet class = examples.ChangeSetServiceExamples region = within}
	 * @param <R> what the work gives
	 * @param work the writes to record, given the session to make them through
	 * @return what the work gives, with the change set ended at the end
	 */
	@Tm1Endpoint("ActionImport BeginChangeSet")
	@Tm1Endpoint("ActionImport EndChangeSet")
	public <R> Flux<R> within(Function<Tm1Session, Publisher<R>> work) {
		return Flux.usingWhen(begin(), id -> work.apply(session.withChangeSet(id)), id -> end(id),
				(id, error) -> end(id), id -> end(id));
	}

	private static Tm1Request<JsonNode> request(Tm1Operation operation, Map<String, ?> body) {
		return Tm1Request.post(ODataPath.of(Objects.requireNonNull(operation.importName())), body, JsonNode.class)
			.requires(operation.capability());
	}

}

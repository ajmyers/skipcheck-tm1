package io.skipcheck.tm1.client.service.security;

import java.util.Map;
import java.util.Objects;

import io.skipcheck.tm1.client.Tm1Operation;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.Session;
import io.skipcheck.tm1.client.model.Thread;
import io.skipcheck.tm1.client.model.Tm1Operations;
import io.skipcheck.tm1.client.model.User;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Server sessions: every session logged in to the server ({@code /Sessions}), this one's
 * ({@code ActiveSession}), their users and threads, and closing another session.
 *
 * <p>
 * To close this client's own session, close the {@code Tm1Session} or {@code Tm1Client}:
 * it sends {@code ActiveSession/tm1.Close}, and knows not to log in again. A closed
 * session stays listed in {@code Sessions} for a few seconds (5 to 9 in TM1 11.8).
 *
 * <p>
 * List who's logged in, expanding each session's user:
 * {@snippet class = examples.SessionServiceExamples region = list}
 *
 * <p>
 * Close other clients' sessions by their label, sparing this one:
 * {@snippet class = examples.SessionServiceExamples region = close}
 */
public final class SessionService {

	private static final ODataPath SESSIONS = ODataPath.of("Sessions");

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public SessionService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET Sessions}, streamed: every session on the server, for an admin.
	 * @param query what to select, expand ({@code User}, {@code Threads}), filter and
	 * order
	 * @return the sessions
	 */
	@Tm1Endpoint("EntitySet Sessions")
	public Flux<Session> list(ODataQuery query) {
		return session.stream(Tm1Request.list(SESSIONS, query, Session.class));
	}

	/**
	 * {@code GET Sessions(id)}.
	 * @param id the session's ID
	 * @param query what to select and expand
	 * @return the session, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("EntitySet Sessions")
	public Mono<Session> get(long id, ODataQuery query) {
		return session.execute(Tm1Request.get(SESSIONS.key(id), query, Session.class));
	}

	/**
	 * {@code GET ActiveSession}: this client's own session on the server.
	 * @param query what to select and expand
	 * @return the session
	 */
	@Tm1Endpoint("Singleton ActiveSession")
	public Mono<Session> activeSession(ODataQuery query) {
		return session.execute(Tm1Request.get(ODataPath.of("ActiveSession"), query, Session.class));
	}

	/**
	 * {@code GET Sessions(id)/User}.
	 * @param id the session's ID
	 * @param query what to select
	 * @return the session's user
	 */
	@Tm1Endpoint("Navigation Session/User")
	public Mono<User> user(long id, ODataQuery query) {
		return session.execute(Tm1Request.get(SESSIONS.key(id).nav("User"), query, User.class));
	}

	/**
	 * {@code GET Sessions(id)/Threads}, streamed: what the session is running now.
	 * @param id the session's ID
	 * @param query what to select, filter and order
	 * @return the session's threads
	 */
	@Tm1Endpoint("Navigation Session/Threads")
	public Flux<Thread> threads(long id, ODataQuery query) {
		return session.stream(Tm1Request.list(SESSIONS.key(id).nav("Threads"), query, Thread.class));
	}

	/**
	 * {@code tm1.Close}: ends another session; its client must log in again. Needs schema
	 * 10.2.2.5.
	 * @param id the session's ID
	 * @return completes when it's closed
	 */
	@Tm1Endpoint("Action Session/Close")
	public Mono<Void> close(long id) {
		Tm1Operation operation = Tm1Operations.SESSION_CLOSE;
		return session.execute(Tm1Request.post(SESSIONS.key(id).action(operation.name()), Map.of(), Void.class)
			.requires(operation.capability()));
	}

}

package net.ajmyers.tm1.client.service.security;

import java.util.Map;
import java.util.Objects;

import net.ajmyers.tm1.client.Tm1Operation;
import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.model.Session;
import net.ajmyers.tm1.client.model.Thread;
import net.ajmyers.tm1.client.model.Tm1Operations;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.service.Tm1Endpoint;
import net.ajmyers.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Server threads: what the server is doing right now ({@code /Threads}), and cancelling
 * an operation, which is how to stop a running process: cancelling a client's request
 * doesn't.
 */
public final class ThreadService {

	private static final ODataPath THREADS = ODataPath.of("Threads");

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public ThreadService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET Threads}, streamed. A process run through REST shows as its request:
	 * {@code Function} is {@code POST /api/v1/Processes('name')/tm1.ExecuteWithReturn},
	 * and TM1 (11.8) leaves {@code ObjectName} empty.
	 * @param query what to select, filter (such as on {@code Function}) and order
	 * @return the threads
	 */
	@Tm1Endpoint("EntitySet Threads")
	public Flux<Thread> list(ODataQuery query) {
		return session.stream(Tm1Request.list(THREADS, query, Thread.class));
	}

	/**
	 * {@code GET Threads(id)}.
	 * @param id the thread's ID
	 * @param query what to select and expand
	 * @return the thread, or a {@code Tm1NotFoundException} if it's gone
	 */
	@Tm1Endpoint("EntitySet Threads")
	public Mono<Thread> get(long id, ODataQuery query) {
		return session.execute(Tm1Request.get(THREADS.key(id), query, Thread.class));
	}

	/**
	 * {@code GET Threads(id)/Session}.
	 * @param id the thread's ID
	 * @param query what to select
	 * @return the thread's session
	 */
	@Tm1Endpoint("Navigation Thread/Session")
	public Mono<Session> session(long id, ODataQuery query) {
		return session.execute(Tm1Request.get(THREADS.key(id).nav("Session"), query, Session.class));
	}

	/**
	 * {@code tm1.CancelOperation}: asks the thread to stop what it's doing, such as a
	 * running process. TM1 (11.8) then fails the process's {@code ExecuteWithReturn}
	 * request with 500, {@code TM1UserException: Cancel} (code 174), rather than
	 * reporting it {@code Aborted}. Needs schema 10.2.2.1.
	 * @param id the thread's ID
	 * @return completes when the request is made; the thread stops soon after
	 */
	@Tm1Endpoint("Action Thread/CancelOperation")
	public Mono<Void> cancel(long id) {
		Tm1Operation operation = Tm1Operations.THREAD_CANCEL_OPERATION;
		return session.execute(Tm1Request.post(THREADS.key(id).action(operation.name()), Map.of(), Void.class)
			.requires(operation.capability()));
	}

}

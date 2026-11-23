// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import java.util.Objects;

import reactor.core.publisher.Mono;

/**
 * A JSON {@code $batch} being put together: {@link #session()} is a view of the session
 * whose requests are queued rather than sent, and {@link #run()} sends them together.
 * {@code Tm1Client.batch()} offers the same with a client.
 *
 * <p>
 * Each call through the view returns its own {@code Mono} or {@code Flux}, which
 * completes with that request's result, or fails with its error, once the batch has run;
 * one request's failure doesn't affect the others. Subscribe to them before or after
 * {@link #run()}: each result is kept.
 *
 * <p>
 * What TM1 (11.8) does here that you might not expect:
 * <ul>
 * <li>A batch is a list of independent requests: TM1 carries on past a failed one, and
 * refuses {@code atomicityGroup} and {@code dependsOn}, so one request can't use
 * another's result. A call that sends a second request after the first answers (such as
 * {@code CellsetService.stream}) can't join a batch: its second request comes after the
 * run, and fails.</li>
 * <li>TM1 answers 200 for the batch even when every request in it failed: each failure is
 * its request's own.</li>
 * <li>A thousand requests in one batch work; {@link #run(int)} splits a bigger
 * queue.</li>
 * <li>Raw bytes (document content) can't go in a JSON batch.</li>
 * </ul>
 *
 * <p>
 * A request that {@linkplain io.skipcheck.tm1.client.Tm1Request#requires requires}
 * something the server doesn't define is checked when the batch runs: its call fails with
 * {@link io.skipcheck.tm1.client.error.Tm1UnsupportedOperationException} and it isn't
 * sent, and the others still are.
 */
public final class Tm1SessionBatch {

	/** How many requests {@link #run()} sends in each {@code $batch}. */
	public static final int DEFAULT_PER_REQUEST = 500;

	private final BatchQueue queue;

	private final Tm1Session session;

	Tm1SessionBatch(BatchQueue queue, Tm1Session session) {
		this.queue = Objects.requireNonNull(queue, "queue");
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * The view whose requests join this batch. A request is queued when the call is made,
	 * not when its result is subscribed to.
	 * @return the view
	 */
	public Tm1Session session() {
		return session;
	}

	/**
	 * How many requests have joined.
	 * @return the number
	 */
	public int size() {
		return queue.size();
	}

	/**
	 * Sends the queued requests, {@value #DEFAULT_PER_REQUEST} to a {@code $batch}. A
	 * batch runs once; a call made through it afterwards fails.
	 * @return completes when every request has its result; fails only if it ran already
	 */
	public Mono<Void> run() {
		return run(DEFAULT_PER_REQUEST);
	}

	/**
	 * Sends the queued requests, at most {@code perRequest} to each {@code $batch}, one
	 * {@code $batch} after another.
	 * @param perRequest the most requests in one {@code $batch}
	 * @return completes when every request has its result; fails only if it ran already
	 * @throws IllegalArgumentException if {@code perRequest} isn't positive
	 */
	public Mono<Void> run(int perRequest) {
		if (perRequest < 1) {
			throw new IllegalArgumentException("A batch needs room for at least one request: " + perRequest);
		}
		return queue.run(perRequest);
	}

}

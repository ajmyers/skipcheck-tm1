package io.skipcheck.tm1.client;

import java.util.Objects;

import io.skipcheck.tm1.client.session.Tm1SessionBatch;
import reactor.core.publisher.Mono;

/**
 * A JSON {@code $batch} being put together, from {@link Tm1Client#batch()}: calls made
 * through {@link #client()} are queued rather than sent, each returning its own
 * {@code Mono} or {@code Flux}, and {@link #run()} sends them together. Every service's
 * methods work through it unchanged. See {@link Tm1SessionBatch} for what TM1 does with a
 * batch.
 *
 * <p>
 * Read three things in one round trip:
 * {@snippet class = examples.BatchExamples region = read}
 *
 * <p>
 * Create many elements in batches of 200, each failure kept to its own element:
 * {@snippet class = examples.BatchExamples region = many}
 */
public final class Tm1Batch {

	private final Tm1SessionBatch batch;

	private final Tm1Client client;

	Tm1Batch(Tm1SessionBatch batch) {
		this.batch = Objects.requireNonNull(batch, "batch");
		this.client = Tm1Client.of(batch.session());
	}

	/**
	 * The client whose calls join this batch. A call's request is queued when the call is
	 * made; its result is there once the batch has run.
	 * @return the client
	 */
	public Tm1Client client() {
		return client;
	}

	/**
	 * How many requests have joined.
	 * @return the number
	 */
	public int size() {
		return batch.size();
	}

	/**
	 * Sends the queued requests ({@link Tm1SessionBatch#run()}).
	 * @return completes when every call has its result
	 */
	public Mono<Void> run() {
		return batch.run();
	}

	/**
	 * Sends the queued requests, at most {@code perRequest} to each {@code $batch}
	 * ({@link Tm1SessionBatch#run(int)}).
	 * @param perRequest the most requests in one {@code $batch}
	 * @return completes when every call has its result
	 */
	public Mono<Void> run(int perRequest) {
		return batch.run(perRequest);
	}

}

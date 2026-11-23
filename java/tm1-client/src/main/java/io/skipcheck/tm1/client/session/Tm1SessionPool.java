// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;
import org.reactivestreams.Subscription;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Sessions on one TM1 server, one per {@link Tm1Identity}, for applications that act on
 * behalf of many users. In TM1 identity is the session, so the number of sessions grows
 * with the number of users; the pool keeps that bounded.
 *
 * {@snippet class = examples.Tm1SessionPoolExamples region = per-user}
 *
 * <ul>
 * <li><b>Get a session for each piece of work</b> and don't keep it: the pool may evict
 * it. Getting one never blocks and sends nothing; the session logs in on its first
 * request.</li>
 * <li><b>Eviction</b> happens when a session has been idle for {@code idleTimeout}, is
 * older than {@code maxAge}, or the pool is over {@code maxSize} (least recently used
 * first). It's checked on each {@link #session} call and every {@code sweepInterval}. A
 * session with a request running isn't idle, however long ago it was asked for.</li>
 * <li><b>An evicted session is closed after {@code closeGrace}</b>, or when the requests
 * running on it have finished, if that's later: closing it would end them on TM1. The
 * next request for that identity gets a new session.</li>
 * <li><b>{@link #close()}</b> closes every session at once, and the pool refuses new
 * ones.</li>
 * <li><b>Nothing falls back:</b> a refused identity fails, and the pool never uses
 * another identity in its place.</li>
 * </ul>
 *
 * <p>
 * Thread-safe. {@link #stats()} reports what the pool holds and has done, for metrics;
 * each eviction is also a {@code tm1.session.pool.evict} observation, tagged with its
 * reason.
 */
@Slf4j
public final class Tm1SessionPool {

	/** How often an evicted session with a request still running is looked at again. */
	private static final Duration BUSY_RECHECK = Duration.ofSeconds(1);

	private final Tm1Session.Builder sessions;

	private final Duration idleTimeout;

	private final Duration maxAge;

	private final int maxSize;

	private final Duration closeGrace;

	private final Scheduler scheduler;

	private final ObservationRegistry observations;

	/** Sessions in least-recently-used order. Guarded by this. */
	private final LinkedHashMap<Tm1Identity, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

	/** Evicted sessions waiting out their grace period, with the pending close. */
	private final Map<Tm1Session, Disposable> closing = new ConcurrentHashMap<>();

	private final Disposable sweep;

	/** Guarded by this. */
	private boolean closed;

	/** Guarded by this. */
	private @Nullable Mono<Void> closeAll;

	/** Counters, guarded by this. */
	private long created;

	private long evictedIdle;

	private long evictedAge;

	private long evictedSize;

	private final AtomicLong closedSessions = new AtomicLong();

	private Tm1SessionPool(Builder builder) {
		this.sessions = builder.sessions;
		this.idleTimeout = builder.idleTimeout;
		this.maxAge = builder.maxAge;
		this.maxSize = builder.maxSize;
		this.closeGrace = builder.closeGrace;
		this.scheduler = builder.scheduler;
		this.observations = builder.sessions.observations();
		// Sessions built by the pool remember refused logins on the pool's clock
		this.sessions.nanoTime(() -> scheduler.now(TimeUnit.NANOSECONDS));
		Sweeper sweeper = new Sweeper();
		Flux.interval(builder.sweepInterval, builder.sweepInterval, scheduler).subscribe(sweeper);
		this.sweep = sweeper;
	}

	/**
	 * A builder for a pool of sessions with these settings: server, credentials, timeouts
	 * and the rest. Each identity's session is built from a copy of them.
	 * @param sessions the settings every session starts from; the pool keeps a copy
	 * @return a new builder
	 */
	public static Builder builder(Tm1Session.Builder sessions) {
		return new Builder(Objects.requireNonNull(sessions, "sessions").copy());
	}

	/**
	 * The session for {@code identity}: the pool's, or a new one if it has none (or the
	 * one it had has expired). Sends nothing: a new session logs in on its first request.
	 * @param identity who the session is for
	 * @return the session
	 * @throws Tm1SessionClosedException if the pool is closed
	 */
	public Tm1Session session(Tm1Identity identity) {
		Objects.requireNonNull(identity, "identity");
		long now = now();
		synchronized (this) {
			if (closed) {
				throw new Tm1SessionClosedException(serviceRoot());
			}
			Entry entry = entries.get(identity);
			if (entry != null) {
				String reason = expiry(entry, now);
				if (reason != null) {
					evict(identity, entry, reason);
					entry = null;
				}
			}
			if (entry == null) {
				entry = new Entry(identity.applyTo(sessions.copy()).build(), now);
				entries.put(identity, entry);
				created++;
				log.debug("Opened a TM1 session at {} for {}", serviceRoot(), identity);
				evictOverSize(identity);
			}
			entry.lastUsed = now;
			return entry.session;
		}
	}

	/**
	 * The session for the pool's own credentials:
	 * {@code session(Tm1Identity.serviceAccount())}.
	 * @return the session
	 * @throws Tm1SessionClosedException if the pool is closed
	 */
	public Tm1Session session() {
		return session(Tm1Identity.serviceAccount());
	}

	/**
	 * Evicts every session that has been idle too long or is too old. Runs every
	 * {@code sweepInterval} on its own; call it to evict now.
	 */
	public void evictExpired() {
		long now = now();
		synchronized (this) {
			for (Map.Entry<Tm1Identity, Entry> e : new ArrayList<>(entries.entrySet())) {
				String reason = expiry(e.getValue(), now);
				if (reason != null) {
					evict(e.getKey(), e.getValue(), reason);
				}
			}
		}
	}

	/**
	 * What the pool holds and has done so far, for metrics.
	 * @return a snapshot
	 */
	public synchronized Stats stats() {
		return new Stats(entries.size(), closing.size(), created, evictedIdle, evictedAge, evictedSize,
				closedSessions.get());
	}

	/**
	 * Closes every session, including evicted ones still in their grace period, and
	 * refuses new ones. Every call returns the same result.
	 * @return completes when every session is closed
	 */
	public Mono<Void> close() {
		return Mono.defer(() -> {
			synchronized (this) {
				if (closeAll == null) {
					closed = true;
					sweep.dispose();
					List<Tm1Session> all = new ArrayList<>();
					entries.values().forEach(e -> all.add(e.session));
					entries.clear();
					for (Map.Entry<Tm1Session, Disposable> pending : Set.copyOf(closing.entrySet())) {
						pending.getValue().dispose();
						all.add(pending.getKey());
					}
					closing.clear();
					log.info("Closing {} TM1 session(s) at {}", all.size(), serviceRoot());
					closeAll = Flux.fromIterable(all)
						.flatMap(session -> session.close()
							.doOnSuccess(v -> closedSessions.incrementAndGet())
							.onErrorResume(e -> Mono.empty()))
						.then()
						.cache();
				}
				return closeAll;
			}
		});
	}

	/** The server the sessions are on. */
	private String serviceRoot() {
		return sessions.baseUrlOrUnset();
	}

	private long now() {
		return scheduler.now(TimeUnit.NANOSECONDS);
	}

	/** Why {@code entry} has expired at {@code now}, or null if it hasn't. */
	private @Nullable String expiry(Entry entry, long now) {
		if (now - entry.createdAt >= maxAge.toNanos()) {
			return "age";
		}
		if (entry.session.isBusy()) {
			// A request running on it is use, however long ago the session was asked for
			entry.lastUsed = now;
			return null;
		}
		if (now - entry.lastUsed >= idleTimeout.toNanos()) {
			return "idle";
		}
		return null;
	}

	/**
	 * Evicts least recently used sessions until the pool is within its size, never
	 * {@code keep}.
	 */
	private void evictOverSize(Tm1Identity keep) {
		while (entries.size() > maxSize) {
			Map.Entry<Tm1Identity, Entry> eldest = entries.entrySet().iterator().next();
			if (eldest.getKey().equals(keep)) {
				return;
			}
			evict(eldest.getKey(), eldest.getValue(), "size");
		}
	}

	/**
	 * Removes a session and closes it after the grace period. Called holding the lock.
	 */
	private void evict(Tm1Identity identity, Entry entry, String reason) {
		entries.remove(identity);
		switch (reason) {
			case "idle" -> evictedIdle++;
			case "age" -> evictedAge++;
			default -> evictedSize++;
		}
		Observation.createNotStarted("tm1.session.pool.evict", observations)
			.contextualName("tm1 pool evict")
			.lowCardinalityKeyValue(KeyValue.of("tm1.reason", reason))
			.start()
			.stop();
		log.debug("Evicting the TM1 session at {} for {} ({}); closing it in {}", serviceRoot(), identity, reason,
				closeGrace);
		Tm1Session session = entry.session;
		Closer closer = new Closer(session);
		closing.put(session, closer);
		Mono.delay(closeGrace, scheduler).then(closeWhenFree(session)).subscribe(closer);
	}

	/**
	 * Closes {@code session} once no request is running on it, looking again every
	 * {@link #BUSY_RECHECK}: closing it sooner would end those requests on TM1.
	 */
	private Mono<Void> closeWhenFree(Tm1Session session) {
		return Mono.defer(() -> session.isBusy() ? Mono.delay(BUSY_RECHECK, scheduler).then(closeWhenFree(session))
				: session.close());
	}

	/**
	 * What a pool holds and has done.
	 *
	 * @param held sessions in the pool now
	 * @param closing evicted sessions in their grace period, not yet closed
	 * @param created sessions created so far
	 * @param evictedIdle sessions evicted for being idle
	 * @param evictedAge sessions evicted for their age
	 * @param evictedSize sessions evicted to keep the pool within its size
	 * @param closed sessions closed on TM1 so far
	 */
	public record Stats(int held, int closing, long created, long evictedIdle, long evictedAge, long evictedSize,
			long closed) {
	}

	/** A session in the pool, with when it was created and last handed out. */
	private static final class Entry {

		final Tm1Session session;

		final long createdAt;

		/** Guarded by the pool. */
		long lastUsed;

		Entry(Tm1Session session, long now) {
			this.session = session;
			this.createdAt = now;
			this.lastUsed = now;
		}

	}

	/**
	 * Closes an evicted session once its grace period is over. A named subscriber rather
	 * than a bare {@code subscribe()}: the close is detached on purpose, and its failure
	 * is logged, not lost.
	 */
	private final class Closer extends BaseSubscriber<Void> {

		private final Tm1Session session;

		Closer(Tm1Session session) {
			this.session = session;
		}

		@Override
		protected void hookOnComplete() {
			closedSessions.incrementAndGet();
		}

		@Override
		protected void hookOnError(Throwable error) {
			log.warn("Couldn't close an evicted TM1 session at {}: {}", serviceRoot(), error.toString());
		}

		@Override
		protected void hookFinally(SignalType type) {
			closing.remove(session);
		}

	}

	/**
	 * Evicts expired sessions every sweep interval; a failure is logged and the sweep
	 * goes on.
	 */
	private final class Sweeper extends BaseSubscriber<Long> {

		@Override
		protected void hookOnSubscribe(Subscription subscription) {
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		protected void hookOnNext(Long tick) {
			try {
				evictExpired();
			}
			catch (RuntimeException e) {
				log.warn("Evicting expired TM1 sessions failed: {}", e.toString());
			}
		}

	}

	/** Builds a {@link Tm1SessionPool}. */
	public static final class Builder {

		private final Tm1Session.Builder sessions;

		private Duration idleTimeout = Duration.ofMinutes(10);

		private Duration maxAge = Duration.ofHours(8);

		private int maxSize = 100;

		private Duration closeGrace = Duration.ofSeconds(30);

		private Duration sweepInterval = Duration.ofMinutes(1);

		private Scheduler scheduler = Schedulers.parallel();

		private Builder(Tm1Session.Builder sessions) {
			this.sessions = sessions;
		}

		/**
		 * How long a session may go unused before it's evicted. Default 10 minutes. Keep
		 * it below TM1's own session timeout, so the pool closes sessions before TM1
		 * drops them.
		 * @param idleTimeout the idle limit
		 * @return this builder
		 */
		public Builder idleTimeout(Duration idleTimeout) {
			this.idleTimeout = positive(idleTimeout, "idleTimeout");
			return this;
		}

		/**
		 * How long a session may live, used or not, before it's evicted. Default 8 hours.
		 * A session also picks up security changes, such as new rights, only when it's
		 * new.
		 * @param maxAge the age limit
		 * @return this builder
		 */
		public Builder maxAge(Duration maxAge) {
			this.maxAge = positive(maxAge, "maxAge");
			return this;
		}

		/**
		 * The most sessions the pool holds; beyond it, the least recently used is
		 * evicted. Default 100. Evicted sessions in their grace period don't count.
		 * @param maxSize the limit, at least 1
		 * @return this builder
		 */
		public Builder maxSize(int maxSize) {
			if (maxSize < 1) {
				throw new IllegalArgumentException("maxSize must be at least 1: " + maxSize);
			}
			this.maxSize = maxSize;
			return this;
		}

		/**
		 * How long an evicted session stays open before it's closed, for a caller that
		 * got it just before and hasn't sent its request yet. A session with a request
		 * running stays open until that finishes, however long the grace period. Default
		 * 30 s; zero closes at once.
		 * @param closeGrace the grace period
		 * @return this builder
		 */
		public Builder closeGrace(Duration closeGrace) {
			Objects.requireNonNull(closeGrace, "closeGrace");
			if (closeGrace.isNegative()) {
				throw new IllegalArgumentException("closeGrace must not be negative: " + closeGrace);
			}
			this.closeGrace = closeGrace;
			return this;
		}

		/**
		 * How often the pool looks for expired sessions on its own, besides on each
		 * {@link Tm1SessionPool#session} call. Default 1 minute.
		 * @param sweepInterval the interval
		 * @return this builder
		 */
		public Builder sweepInterval(Duration sweepInterval) {
			this.sweepInterval = positive(sweepInterval, "sweepInterval");
			return this;
		}

		/**
		 * Where the pool's timers run, and its clock. Default
		 * {@link Schedulers#parallel()}; tests can pass a {@code VirtualTimeScheduler}.
		 * @param scheduler the scheduler
		 * @return this builder
		 */
		public Builder scheduler(Scheduler scheduler) {
			this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
			return this;
		}

		/**
		 * The pool. It starts its sweep at once, and opens sessions only when asked.
		 * @return the pool
		 */
		public Tm1SessionPool build() {
			return new Tm1SessionPool(this);
		}

		private static Duration positive(Duration value, String name) {
			Objects.requireNonNull(value, name);
			if (value.isNegative() || value.isZero()) {
				throw new IllegalArgumentException(name + " must be positive: " + value);
			}
			return value;
		}

	}

}

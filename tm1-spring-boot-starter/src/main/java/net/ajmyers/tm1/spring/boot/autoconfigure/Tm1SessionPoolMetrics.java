package net.ajmyers.tm1.spring.boot.autoconfigure;

import java.util.Objects;
import java.util.function.ToDoubleFunction;

import org.jspecify.annotations.Nullable;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import net.ajmyers.tm1.client.session.Tm1SessionPool;

/**
 * Metrics for a {@link Tm1SessionPool}, from its {@code stats()}: plan §16.1 asks for
 * sessions held, evicted and closed, since unbounded sessions caused a production
 * incident. Auto-configured when Micrometer is present; bind it yourself otherwise.
 *
 * <ul>
 * <li>{@code tm1.session.pool.held} (gauge): sessions in the pool</li>
 * <li>{@code tm1.session.pool.closing} (gauge): evicted sessions in their grace
 * period</li>
 * <li>{@code tm1.session.pool.created} (counter): sessions created</li>
 * <li>{@code tm1.session.pool.evicted} (counter, tagged {@code reason}: {@code idle},
 * {@code age}, {@code size}): sessions evicted</li>
 * <li>{@code tm1.session.pool.closed} (counter): sessions closed on TM1</li>
 * </ul>
 */
public final class Tm1SessionPoolMetrics implements MeterBinder {

	private final Tm1SessionPool pool;

	/**
	 * Metrics for {@code pool}.
	 * @param pool the pool to report on
	 */
	public Tm1SessionPoolMetrics(Tm1SessionPool pool) {
		this.pool = Objects.requireNonNull(pool, "pool");
	}

	@Override
	public void bindTo(MeterRegistry registry) {
		gauge(registry, "tm1.session.pool.held", "Sessions in the pool", p -> p.stats().held());
		gauge(registry, "tm1.session.pool.closing", "Evicted sessions waiting out their grace period",
				p -> p.stats().closing());
		counter(registry, "tm1.session.pool.created", "Sessions the pool created", p -> p.stats().created(), null);
		counter(registry, "tm1.session.pool.evicted", "Sessions the pool evicted", p -> p.stats().evictedIdle(),
				"idle");
		counter(registry, "tm1.session.pool.evicted", "Sessions the pool evicted", p -> p.stats().evictedAge(), "age");
		counter(registry, "tm1.session.pool.evicted", "Sessions the pool evicted", p -> p.stats().evictedSize(),
				"size");
		counter(registry, "tm1.session.pool.closed", "Sessions the pool closed on TM1", p -> p.stats().closed(), null);
	}

	private void gauge(MeterRegistry registry, String name, String description,
			ToDoubleFunction<Tm1SessionPool> value) {
		Gauge.builder(name, pool, value).description(description).register(registry);
	}

	private void counter(MeterRegistry registry, String name, String description,
			ToDoubleFunction<Tm1SessionPool> value, @Nullable String reason) {
		FunctionCounter.Builder<Tm1SessionPool> counter = FunctionCounter.builder(name, pool, value)
			.description(description);
		if (reason != null) {
			counter.tag("reason", reason);
		}
		counter.register(registry);
	}

}

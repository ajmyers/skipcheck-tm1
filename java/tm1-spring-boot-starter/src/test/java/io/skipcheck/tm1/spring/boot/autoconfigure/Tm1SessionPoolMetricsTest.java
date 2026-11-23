package io.skipcheck.tm1.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.skipcheck.tm1.client.session.Tm1Identity;
import io.skipcheck.tm1.client.session.Tm1SessionPool;

class Tm1SessionPoolMetricsTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(Tm1ConnectionDetailsAutoConfiguration.class,
				Tm1AutoConfiguration.class, Tm1MetricsAutoConfiguration.class))
		.withPropertyValues("tm1.address=http://tm1:8010", "tm1.user=admin");

	@Test
	void reportsThePoolsStats() {
		runner.withPropertyValues("tm1.pool.enabled=true").run(context -> {
			SimpleMeterRegistry registry = new SimpleMeterRegistry();
			context.getBean(Tm1SessionPoolMetrics.class).bindTo(registry);
			Tm1SessionPool pool = context.getBean(Tm1SessionPool.class);
			pool.session(Tm1Identity.impersonating("alice"));
			pool.session(Tm1Identity.impersonating("bob"));

			assertThat(registry.get("tm1.session.pool.held").gauge().value()).isEqualTo(2);
			assertThat(registry.get("tm1.session.pool.created").functionCounter().count()).isEqualTo(2);
			assertThat(registry.get("tm1.session.pool.evicted").tag("reason", "idle").functionCounter().count())
				.isZero();
			assertThat(registry.get("tm1.session.pool.closed").functionCounter().count()).isZero();
		});
	}

	@Test
	void reportsNothingWithoutAPool() {
		runner.run(context -> assertThat(context).doesNotHaveBean(Tm1SessionPoolMetrics.class));
	}

}

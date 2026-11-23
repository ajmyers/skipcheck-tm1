// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.skipcheck.tm1.client.session.Tm1Identity;
import io.skipcheck.tm1.client.session.Tm1SessionPool;

class Tm1SessionPoolMetricsTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(Tm1AutoConfiguration.class, Tm1MetricsAutoConfiguration.class))
		.withPropertyValues("tm1.address=http://tm1:8010", "tm1.user=admin");

	@Test
	void reportsThePoolsStats() {
		runner.withPropertyValues("tm1.pool.enabled=true").run(context -> {
			SimpleMeterRegistry registry = new SimpleMeterRegistry();
			context.getBean(Tm1SessionPoolMetrics.class).bindTo(registry);
			Tm1SessionPool pool = context.getBean(Tm1SessionPool.class);
			assertThat(registry.get("tm1.session.pool.held").tag("server", "default").gauge()).isNotNull();
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
	void tagsEveryPoolWithItsServer() {
		runner
			.withPropertyValues("tm1.pool.enabled=true", "tm1.servers.planning.address=http://planning:8010",
					"tm1.servers.planning.user=admin", "tm1.servers.planning.pool.enabled=true",
					"tm1.servers.reporting.address=http://reporting:8010", "tm1.servers.reporting.user=admin")
			.run(context -> {
				SimpleMeterRegistry registry = new SimpleMeterRegistry();
				context.getBeansOfType(Tm1SessionPoolMetrics.class)
					.values()
					.forEach(metrics -> metrics.bindTo(registry));
				context.getBean("planningTm1SessionPool", Tm1SessionPool.class)
					.session(Tm1Identity.impersonating("alice"));

				// The default server's pool, and planning's; reporting has none
				assertThat(context).hasBean("defaultTm1SessionPoolMetrics")
					.hasBean("planningTm1SessionPoolMetrics")
					.doesNotHaveBean("reportingTm1SessionPoolMetrics");
				assertThat(registry.get("tm1.session.pool.held").tag("server", "planning").gauge().value())
					.isEqualTo(1);
				assertThat(registry.get("tm1.session.pool.held").tag("server", "default").gauge().value()).isZero();
				assertThat(registry.find("tm1.session.pool.held").gauges()).hasSize(2);
			});
	}

	@Test
	void reportsTheDefaultAndANamedServersPoolsToPrometheus() {
		runner
			.withPropertyValues("tm1.pool.enabled=true", "tm1.servers.planning.address=http://planning:8010",
					"tm1.servers.planning.user=admin", "tm1.servers.planning.pool.enabled=true")
			.run(context -> {
				// The registry that cares most about one meter name's tag keys
				PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
				context.getBeansOfType(Tm1SessionPoolMetrics.class)
					.values()
					.forEach(metrics -> metrics.bindTo(registry));

				assertThat(registry.find("tm1.session.pool.held").gauges()).hasSize(2);
				assertThat(registry.find("tm1.session.pool.evicted").functionCounters()).hasSize(6);
				assertThat(registry.scrape()).contains("tm1_session_pool_held{server=\"default\"}")
					.contains("tm1_session_pool_held{server=\"planning\"}");
			});
	}

	@Test
	void reportsANamedServersPoolWithoutADefaultServer() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(Tm1AutoConfiguration.class, Tm1MetricsAutoConfiguration.class))
			.withPropertyValues("tm1.servers.planning.address=http://planning:8010", "tm1.servers.planning.user=admin",
					"tm1.servers.planning.pool.enabled=true")
			.run(context -> assertThat(context).hasBean("planningTm1SessionPoolMetrics")
				.doesNotHaveBean("defaultTm1SessionPoolMetrics"));
	}

	@Test
	void reportsNothingWithoutAPool() {
		runner.run(context -> assertThat(context).doesNotHaveBean(Tm1SessionPoolMetrics.class));
	}

}

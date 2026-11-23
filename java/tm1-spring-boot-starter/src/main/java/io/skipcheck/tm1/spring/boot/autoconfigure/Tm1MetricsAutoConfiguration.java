// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import io.skipcheck.tm1.client.session.Tm1SessionPool;

/**
 * {@link Tm1SessionPoolMetrics} for the pool, when Micrometer is on the classpath. Boot
 * binds {@code MeterBinder} beans to its meter registry. Ordered after
 * {@link Tm1AutoConfiguration}, which creates the pool.
 */
@AutoConfiguration(after = Tm1AutoConfiguration.class)
@ConditionalOnClass(name = "io.micrometer.core.instrument.binder.MeterBinder")
@ConditionalOnBean(Tm1SessionPool.class)
public final class Tm1MetricsAutoConfiguration {

	/** Not instantiated by applications; Spring creates it. */
	Tm1MetricsAutoConfiguration() {
	}

	@Bean
	@ConditionalOnMissingBean
	Tm1SessionPoolMetrics tm1SessionPoolMetrics(Tm1SessionPool pool) {
		return new Tm1SessionPoolMetrics(pool);
	}

}

// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

import io.micrometer.core.instrument.Tags;
import io.skipcheck.tm1.client.session.Tm1SessionPool;

/**
 * {@link Tm1SessionPoolMetrics} for each server's pool, when Micrometer is on the
 * classpath: {@code <name>Tm1SessionPoolMetrics}, tagged {@code server} with the server's
 * name, {@code default} included. Boot binds {@code MeterBinder} beans to its meter
 * registry. A bean of yours under that name takes the place of the starter's. Ordered
 * after {@link Tm1AutoConfiguration}, which creates the pools.
 */
@AutoConfiguration(after = Tm1AutoConfiguration.class)
@ConditionalOnClass(name = "io.micrometer.core.instrument.binder.MeterBinder")
@Import(Tm1MetricsAutoConfiguration.PoolMetricsRegistrar.class)
public final class Tm1MetricsAutoConfiguration {

	/** Not instantiated by applications; Spring creates it. */
	Tm1MetricsAutoConfiguration() {
	}

	/**
	 * Registers the metrics of each pool that has a server's qualifier, which every
	 * server's pool has. Each is a default candidate, unlike a named server's pool, so
	 * Boot's meter registry finds it with the other binders.
	 */
	static final class PoolMetricsRegistrar implements ImportBeanDefinitionRegistrar {

		private final BeanFactory beanFactory;

		PoolMetricsRegistrar(BeanFactory beanFactory) {
			this.beanFactory = beanFactory;
		}

		@Override
		public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
			for (String pool : ((ListableBeanFactory) beanFactory).getBeanNamesForType(Tm1SessionPool.class, true,
					false)) {
				String server = server(registry.getBeanDefinition(pool));
				if (server == null) {
					continue;
				}
				String metrics = Tm1ServersRegistrar.beanName(server, Tm1SessionPoolMetrics.class);
				if (registry.containsBeanDefinition(metrics)) {
					continue;
				}
				RootBeanDefinition definition = new RootBeanDefinition(Tm1SessionPoolMetrics.class,
						() -> new Tm1SessionPoolMetrics(beanFactory.getBean(pool, Tm1SessionPool.class),
								Tags.of("server", server)));
				definition.addQualifier(new AutowireCandidateQualifier(Qualifier.class, server));
				registry.registerBeanDefinition(metrics, definition);
			}
		}

		private static @Nullable String server(BeanDefinition definition) {
			if (definition instanceof AbstractBeanDefinition abstractDefinition) {
				AutowireCandidateQualifier qualifier = abstractDefinition.getQualifier(Qualifier.class.getName());
				if (qualifier != null && qualifier.getAttribute("value") instanceof String server) {
					return server;
				}
			}
			return null;
		}

	}

}

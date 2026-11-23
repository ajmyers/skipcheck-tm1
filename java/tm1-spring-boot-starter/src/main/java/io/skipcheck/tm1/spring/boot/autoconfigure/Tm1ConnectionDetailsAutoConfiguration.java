package io.skipcheck.tm1.spring.boot.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * {@link Tm1ConnectionDetails} from the {@code tm1.*} properties, when
 * {@code tm1.address} or {@code tm1.base-url} is set and no other connection details are
 * defined, such as by {@code @ServiceConnection}. Its own auto-configuration, ordered
 * before {@link Tm1AutoConfiguration}, so the session's {@code @ConditionalOnBean} sees
 * it.
 */
@AutoConfiguration(
		afterName = "org.springframework.boot.testcontainers.service.connection.ServiceConnectionAutoConfiguration")
@EnableConfigurationProperties(Tm1Properties.class)
public final class Tm1ConnectionDetailsAutoConfiguration {

	/** Not instantiated by applications; Spring creates it. */
	Tm1ConnectionDetailsAutoConfiguration() {
	}

	@Bean
	@ConditionalOnMissingBean(Tm1ConnectionDetails.class)
	@Conditional(OnTm1ServerConfigured.class)
	PropertiesTm1ConnectionDetails tm1ConnectionDetails(Tm1Properties properties, ObjectProvider<SslBundles> bundles) {
		return new PropertiesTm1ConnectionDetails(properties, bundles.getIfAvailable());
	}

}

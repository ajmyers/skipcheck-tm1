// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.session.Tm1Session;

/**
 * The application.yml files the Getting started page shows (src/examples/java/examples)
 * really configure a client: a renamed property fails here, not in a reader's
 * application.
 */
class GettingStartedPropertiesTest {

	@Test
	void theGettingStartedPropertiesConnectToTm1() {
		runner("application.yml").run(context -> {
			assertThat(context.getBean(Tm1Session.class).serviceRoot()).isEqualTo("http://localhost:8010/api/v1/");
			assertThat(context.getBean(Tm1ConnectionDetails.class).getAuthenticator().describe())
				.isEqualTo("Basic admin");
		});
	}

	@Test
	void theSeveralServersPropertiesConnectToEachServer() {
		runner("application-servers.yml").run(context -> {
			assertThat(context.getBean("planningTm1Client", Tm1Client.class).session().serviceRoot())
				.isEqualTo("http://localhost:8010/api/v1/");
			assertThat(context.getBean("reportingTm1Client", Tm1Client.class).session().serviceRoot())
				.isEqualTo("http://localhost:8011/api/v1/");
		});
	}

	private static ApplicationContextRunner runner(String file) {
		// Flattened the way Spring Boot reads application.yml: tm1.address, tm1.user, ...
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new FileSystemResource("src/examples/java/examples/" + file));
		Properties properties = Objects.requireNonNull(yaml.getObject());
		String[] values = properties.stringPropertyNames()
			.stream()
			.map(name -> name + "=" + properties.getProperty(name))
			.toArray(String[]::new);
		return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(Tm1AutoConfiguration.class))
			.withPropertyValues(values);
	}

}

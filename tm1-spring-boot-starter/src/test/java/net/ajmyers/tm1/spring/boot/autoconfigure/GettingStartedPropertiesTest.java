package net.ajmyers.tm1.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

import net.ajmyers.tm1.client.session.Tm1Session;

/**
 * The application.yml the Getting started page shows (src/examples/java/examples) really
 * configures a client: a renamed property fails here, not in a reader's application.
 */
class GettingStartedPropertiesTest {

	@Test
	void theGettingStartedPropertiesConnectToTm1() {
		// Flattened the way Spring Boot reads application.yml: tm1.address, tm1.user, ...
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new FileSystemResource("src/examples/java/examples/application.yml"));
		Properties properties = Objects.requireNonNull(yaml.getObject());
		String[] values = properties.stringPropertyNames()
			.stream()
			.map(name -> name + "=" + properties.getProperty(name))
			.toArray(String[]::new);

		new ApplicationContextRunner()
			.withConfiguration(
					AutoConfigurations.of(Tm1ConnectionDetailsAutoConfiguration.class, Tm1AutoConfiguration.class))
			.withPropertyValues(values)
			.run(context -> {
				assertThat(context.getBean(Tm1Session.class).serviceRoot()).isEqualTo("http://localhost:8010/api/v1/");
				assertThat(context.getBean(Tm1ConnectionDetails.class).getAuthenticator().describe())
					.isEqualTo("Basic admin");
			});
	}

}

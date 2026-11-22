package net.ajmyers.tm1.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import net.ajmyers.tm1.client.session.Tm1Session;

/**
 * The application.properties the Getting started page shows (src/examples/java/examples)
 * really configures a client: a renamed property fails here, not in a reader's
 * application.
 */
class GettingStartedPropertiesTest {

	@Test
	void theGettingStartedPropertiesConnectToTm1() throws IOException {
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(Path.of("src/examples/java/examples/application.properties"))) {
			properties.load(reader);
		}
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

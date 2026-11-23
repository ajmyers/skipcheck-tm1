// SPDX-License-Identifier: Apache-2.0

package examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * Runs the Getting started application (HelloTm1Application) against a real TM1, and
 * records what it prints for the docs, which show it under its code (tm1-client's
 * ExampleOutput). Run it after changing the application, and commit the recording in
 * api-site/example-output with it.
 *
 * <p>
 * The application's settings (application.yml) are those of tm1-server's empty database,
 * so this starts a server of its own with one, rather than use the shared server, which
 * other tests fill.
 */
@RequiresTm1
class HelloTm1ApplicationTm1Test {

	@Test
	void printsTheVersionAndTheCubes() {
		try (Tm1ServerContainer tm1 = new Tm1ServerContainer(Tm1Source.discover().orElseThrow());
				ExampleOutput output = new ExampleOutput("tm1-spring-boot-starter", "HelloTm1Application")) {
			tm1.start();
			output.record("hello-app", () -> {
				SpringApplication application = new SpringApplication(HelloTm1Application.class);
				application.addInitializers(context -> context.getBeanFactory()
					.registerSingleton("onlyTheApplication", new OnlyTheApplication()));
				// Spring's banner and logs would be printed with the application's output
				ConfigurableApplicationContext context = application.run("--tm1.base-url=" + tm1.getRestUrl(),
						"--tm1.user=" + tm1.getUsername(), "--tm1.password=" + tm1.getPassword(),
						"--spring.main.banner-mode=off", "--logging.level.root=off");
				context.close();
			});
			assertThat(output.output("hello-app")).containsExactly("Hello from TM1 " + tm1.getTm1Version());
		}
	}

	/**
	 * The application scans its package, examples, which holds the other examples' beans
	 * too, such as their own connection details. An application of the reader's has only
	 * its own, so the scan leaves the others out.
	 */
	static final class OnlyTheApplication extends TypeExcludeFilter {

		@Override
		public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
			String type = reader.getClassMetadata().getClassName();
			return type.startsWith("examples.") && !type.startsWith(HelloTm1Application.class.getName());
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof OnlyTheApplication;
		}

		@Override
		public int hashCode() {
			return OnlyTheApplication.class.hashCode();
		}

	}

}

package examples;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import net.ajmyers.tm1.client.auth.Tm1Authenticator;
import net.ajmyers.tm1.spring.boot.autoconfigure.Tm1ConnectionDetails;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link Tm1ConnectionDetails}, quoted in its Javadoc: Spring configuration,
 * compiled but never run.
 */
final class Tm1ConnectionDetailsExamples {

	/** Where the application gets its users' CAM passports, such as from Cognos. */
	interface CamPassports {

		Mono<String> current();

	}

	// @start region = own-bean
	// In place of the tm1.* connection properties: a CAM passport, fetched for each login
	@Configuration(proxyBeanMethods = false)
	static class Tm1Connection {

		@Bean
		Tm1ConnectionDetails tm1ConnectionDetails(CamPassports passports) {
			return new Tm1ConnectionDetails() {

				@Override
				public String getServiceRoot() {
					return "https://tm1.example.com:8010/api/v1/";
				}

				@Override
				public Tm1Authenticator getAuthenticator() {
					return Tm1Authenticator.camPassport(passports::current);
				}

			};
		}

	}
	// @end

	private Tm1ConnectionDetailsExamples() {
	}

}

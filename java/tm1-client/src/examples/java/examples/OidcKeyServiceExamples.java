package examples;

import io.skipcheck.tm1.client.Tm1Client;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.security.OidcKeyService}, quoted in
 * its Javadoc.
 */
final class OidcKeyServiceExamples {

	Mono<Void> refresh(Tm1Client tm1) {
		// @start region = refresh
		// After the provider rotates its signing keys: drop every cached key, so TM1
		// fetches them again
		return tm1.oidcKeys().keys().collectList().flatMap(keys -> tm1.oidcKeys().remove(keys));
		// @end
	}

	private OidcKeyServiceExamples() {
	}

}

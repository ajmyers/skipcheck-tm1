// SPDX-License-Identifier: Apache-2.0

package examples;

import java.util.List;
import java.util.function.Supplier;

import io.skipcheck.tm1.client.auth.Tm1Authenticator;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link Tm1Authenticator}, quoted in its Javadoc.
 */
final class Tm1AuthenticatorExamples {

	List<Tm1Authenticator> kinds(Supplier<Mono<String>> passports) {
		// @start region = kinds
		// TM1's own security
		Tm1Authenticator basic = Tm1Authenticator.basic("admin", "apple");
		// CAM, with a user and password
		Tm1Authenticator cam = Tm1Authenticator.camNamespace("alice", "secret", "LDAP");
		// CAM, with a passport
		Tm1Authenticator passport = Tm1Authenticator.camPassport(passports);
		// A passport from Cognos
		Tm1Authenticator cognos = Tm1Authenticator.cognosLogon("http://cognos:9300", "LDAP", "alice", "secret");
		// @end
		return List.of(basic, cam, passport, cognos);
	}

	private Tm1AuthenticatorExamples() {
	}

}

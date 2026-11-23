// SPDX-License-Identifier: Apache-2.0

package examples;

import java.util.function.Supplier;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.auth.CognosPassports;
import io.skipcheck.tm1.client.auth.Tm1Authenticator;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link CognosPassports}, quoted in its Javadoc.
 */
final class CognosPassportsExamples {

	Tm1Client logon(String password) {
		// @start region = logon
		// The client logs on to Cognos for a passport at every TM1 login, so TM1 never
		// sees the password and an expired passport is never reused
		return Tm1Client.builder()
			.address("https://tm1.example.com:8010")
			.authenticator(Tm1Authenticator.cognosLogon("https://cognos.example.com:9300", "LDAP", "alice", password))
			.build();
		// @end
	}

	Tm1Client idToken(Supplier<Mono<String>> currentIdToken) {
		// @start region = id-token
		// An application that signed its user in with OpenID Connect hands over their ID
		// token; Cognos's OIDC namespace turns it into a passport
		return Tm1Client.builder()
			.address("https://tm1.example.com:8010")
			.authenticator(Tm1Authenticator.cognosIdToken("https://cognos.example.com:9300", "OIDC", currentIdToken))
			.build();
		// @end
	}

	private CognosPassportsExamples() {
	}

}

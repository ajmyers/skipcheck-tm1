// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class Tm1AuthenticatorTest {

	@Test
	void writesBasicCredentials() {
		Tm1Authenticator basic = Tm1Authenticator.basic("admin", "apple");
		StepVerifier.create(basic.authorization()).expectNext("Basic " + base64("admin:apple")).verifyComplete();
		StepVerifier.create(Tm1Authenticator.basic("admin", null).authorization())
			.expectNext("Basic " + base64("admin:"))
			.verifyComplete();
		assertThat(basic.describe()).isEqualTo("Basic admin");
	}

	@Test
	void writesCamNamespaceCredentials() {
		Tm1Authenticator cam = Tm1Authenticator.camNamespace("alice", "secret", "LDAP");
		StepVerifier.create(cam.authorization())
			.expectNext("CAMNamespace " + base64("alice:secret:LDAP"))
			.verifyComplete();
		assertThat(cam.describe()).isEqualTo("CAMNamespace LDAP/alice");
	}

	@Test
	void asksForAFreshPassportOnEveryLogin() {
		AtomicInteger issued = new AtomicInteger();
		Tm1Authenticator cam = Tm1Authenticator
			.camPassport(() -> Mono.fromSupplier(() -> "p" + issued.incrementAndGet()));
		StepVerifier.create(cam.authorization()).expectNext("CAMPassport p1").verifyComplete();
		StepVerifier.create(cam.authorization()).expectNext("CAMPassport p2").verifyComplete();
		assertThat(cam.describe()).isEqualTo("CAMPassport");
	}

	@Test
	void neverShowsCredentialsInToString() {
		assertThat(Tm1Authenticator.basic("admin", "apple").toString()).doesNotContain("apple")
			.doesNotContain(base64("admin:apple"));
		assertThat(Tm1Authenticator.camNamespace("alice", "secret", "LDAP").toString()).doesNotContain("secret");
		assertThat(Tm1Authenticator.camPassport(() -> Mono.just("passport-value")).toString())
			.doesNotContain("passport-value");
	}

	private static String base64(String text) {
		return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

}

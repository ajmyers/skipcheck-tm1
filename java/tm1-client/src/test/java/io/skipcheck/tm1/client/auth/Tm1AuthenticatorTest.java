// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
	void writesBearerCredentials() {
		Tm1Authenticator bearer = Tm1Authenticator.bearer("eyJ.token.sig");
		StepVerifier.create(bearer.authorization()).expectNext("Bearer eyJ.token.sig").verifyComplete();
		assertThat(bearer.describe()).isEqualTo("Bearer");
		assertThat(bearer.applicationUser()).isEmpty();
		assertThat(bearer).isEqualTo(Tm1Authenticator.bearer("eyJ.token.sig"))
			.hasSameHashCodeAs(Tm1Authenticator.bearer("eyJ.token.sig"))
			.isNotEqualTo(Tm1Authenticator.bearer("other-token"));
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Authenticator.bearer("  "));
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Authenticator.bearer("bad\r\ntoken"));
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Authenticator.bearer("Bearer eyJ.token"));
	}

	@Test
	void asksForAFreshBearerTokenOnEveryLogin() {
		AtomicInteger issued = new AtomicInteger();
		Tm1Authenticator bearer = Tm1Authenticator
			.bearer(() -> Mono.fromSupplier(() -> "t" + issued.incrementAndGet()));
		StepVerifier.create(bearer.authorization()).expectNext("Bearer t1").verifyComplete();
		StepVerifier.create(bearer.authorization()).expectNext("Bearer t2").verifyComplete();
		assertThat(bearer.describe()).isEqualTo("Bearer");
		assertThat(bearer).isNotEqualTo(Tm1Authenticator.bearer(() -> Mono.just("t1")));
		StepVerifier.create(Tm1Authenticator.bearer(() -> Mono.just("  ")).authorization())
			.expectError(IllegalArgumentException.class)
			.verify();
		StepVerifier.create(Tm1Authenticator.bearer(() -> Mono.just("bad\ntoken")).authorization())
			.expectError(IllegalArgumentException.class)
			.verify();
	}

	@Test
	void writesApplicationCredentials() {
		Tm1Authenticator app = Tm1Authenticator.application("svc-client", "s3cr3t", "analyst");
		StepVerifier.create(app.authorization()).expectNext("Basic " + base64("svc-client:s3cr3t")).verifyComplete();
		assertThat(app.applicationUser()).contains("analyst");
		assertThat(app.describe()).isEqualTo("Application svc-client as analyst");
		assertThat(app).isEqualTo(Tm1Authenticator.application("svc-client", "s3cr3t", "analyst"))
			.hasSameHashCodeAs(Tm1Authenticator.application("svc-client", "s3cr3t", "analyst"))
			.isNotEqualTo(Tm1Authenticator.application("svc-client", "s3cr3t", "other"))
			.isNotEqualTo(Tm1Authenticator.application("svc-client", "other", "analyst"))
			.isNotEqualTo(Tm1Authenticator.basic("svc-client", "s3cr3t"));
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Authenticator.application(" ", "s3cr3t", "analyst"));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> Tm1Authenticator.application("bad:id", "s3cr3t", "analyst"));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> Tm1Authenticator.application("bad\nid", "s3cr3t", "analyst"));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> Tm1Authenticator.application("svc-client", " ", "analyst"));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> Tm1Authenticator.application("svc-client", "s3cr3t", " "));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> Tm1Authenticator.application("svc-client", "s3cr3t", "bad\nuser"));
	}

	@Test
	void neverShowsCredentialsInToString() {
		assertThat(Tm1Authenticator.basic("admin", "apple").toString()).doesNotContain("apple")
			.doesNotContain(base64("admin:apple"));
		assertThat(Tm1Authenticator.camNamespace("alice", "secret", "LDAP").toString()).doesNotContain("secret");
		assertThat(Tm1Authenticator.camPassport(() -> Mono.just("passport-value")).toString())
			.doesNotContain("passport-value");
		assertThat(Tm1Authenticator.bearer("secret-jwt").toString()).doesNotContain("secret-jwt");
		assertThat(Tm1Authenticator.bearer(() -> Mono.just("secret-jwt")).toString()).doesNotContain("secret-jwt");
		assertThat(Tm1Authenticator.application("svc-client", "s3cr3t", "analyst").toString()).doesNotContain("s3cr3t")
			.doesNotContain(base64("svc-client:s3cr3t"));
	}

	private static String base64(String text) {
		return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

}

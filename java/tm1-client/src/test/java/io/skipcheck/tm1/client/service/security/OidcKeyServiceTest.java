// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1ErrorKind;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;

/**
 * OidcKeyService against a fake TM1. Every call is recorded to
 * {@code spec/conformance/calls/OidcKeyServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay.
 */
class OidcKeyServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void listsTheCachedKeys() {
		tm1().json("{\"@odata.context\":\"$metadata#Collection(Edm.String)\",\"value\":[\"k1\",\"k2\"]}");
		List<String> keys = conformance.call("oidcKeys", "keys");
		assertThat(keys).containsExactly("k1", "k2");
		assertThat(tm1().last().key()).isEqualTo("GET GetOIDCKeys()");

		tm1().json("{\"value\":[]}");
		List<String> none = conformance.call("oidcKeys", "keys");
		assertThat(none).isEmpty();
	}

	@Test
	void removesKeys() {
		tm1().status(204);
		conformance.call("oidcKeys", "remove", List.of("k1", "k/2 '3'"));
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key()).isEqualTo("POST RemoveOIDCKeyFromCache");
		assertThat(post.header("Content-Type")).isEqualTo("application/json");
		assertThat(post.text()).isEqualTo("{\"KeyId\":[\"k1\",\"k/2 '3'\"]}");

		tm1().status(204);
		conformance.call("oidcKeys", "remove", List.of());
		assertThat(tm1().last().text()).isEqualTo("{\"KeyId\":[]}");
	}

	@Test
	void anUncachedKeyIsNotFound() {
		tm1().error(400, "55", "ObjectNotFound: the key isn't cached");
		Throwable error = conformance.fail("oidcKeys", "remove", List.of("not-cached"));
		assertThat(error).isInstanceOfSatisfying(Tm1NotFoundException.class, e -> {
			assertThat(e.kind()).isEqualTo(Tm1ErrorKind.NOT_FOUND);
			assertThat(e.status()).isEqualTo(400);
		});
	}

	@Test
	void refusesAMissingKey() {
		Throwable error = conformance.fail("oidcKeys", "remove", Arrays.asList("k1", null));
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}

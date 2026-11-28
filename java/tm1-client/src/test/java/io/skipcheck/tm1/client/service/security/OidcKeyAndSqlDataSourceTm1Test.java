// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.LiveServer;
import io.skipcheck.tm1.client.LiveTm1;
import io.skipcheck.tm1.client.ServerAssumptions;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * OidcKeyService and SqlDataSourceService. The test server checks no OIDC tokens and its
 * host has no ODBC sources, so both are empty; the test pins how TM1 says so.
 */
@LiveTm1
class OidcKeyAndSqlDataSourceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	@BeforeAll
	static void connect(LiveServer server) {
		tm1 = server.clientBuilder().build();
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	@Test
	void readsAndClearsTheOidcKeyCache() {
		assertThat(tm1.oidcKeys().keys().collectList().block(WAIT)).isEmpty();
		tm1.oidcKeys().remove(List.of()).block(WAIT);
		if (ServerAssumptions.isV12(tm1)) {
			tm1.oidcKeys().remove(List.of("not-cached")).block(WAIT);
		}
		else {
			assertThatThrownBy(() -> tm1.oidcKeys().remove(List.of("not-cached")).block(WAIT))
				.as("TM1 answers 400 ObjectNotFound, read as not found")
				.isInstanceOf(Tm1NotFoundException.class)
				.hasMessageContaining("ObjectNotFound");
		}
	}

	@Test
	void listsTheHostsOdbcSources() {
		assertThat(tm1.sqlDataSources().list(ODataQuery.none()).collectList().block(WAIT)).isEmpty();
		assertThatThrownBy(() -> tm1.sqlDataSources().get("none", ODataQuery.none()).block(WAIT))
			.isInstanceOf(Tm1NotFoundException.class);
	}

}

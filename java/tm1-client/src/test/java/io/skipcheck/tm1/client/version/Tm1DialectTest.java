// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.session.Tm1Session;

class Tm1DialectTest {

	@Test
	void findsTheTm111ServiceRootFromAnAddress() {
		assertThat(Tm1Dialect.V11.serviceRoot("http://tm1:8010")).isEqualTo("http://tm1:8010/api/v1/");
		assertThat(Tm1Dialect.V11.serviceRoot("https://tm1.example.com:12354/"))
			.isEqualTo("https://tm1.example.com:12354/api/v1/");
		assertThat(Tm1Dialect.V11.serviceRoot("http://tm1:8010/api/v1")).as("already a root")
			.isEqualTo("http://tm1:8010/api/v1/");
		assertThat(Tm1Dialect.V11.serviceRoot("http://proxy/tm1/sales/")).as("behind a proxy path")
			.isEqualTo("http://proxy/tm1/sales/api/v1/");
	}

	@Test
	void refusesWhatIsntAnAddress() {
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Dialect.V11.serviceRoot("tm1:8010/x"));
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Dialect.V11.serviceRoot("/api/v1/"));
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Dialect.V11.serviceRoot("http://tm1:8010?x=1"));
	}

	@Test
	void letsASessionBeBuiltFromAnAddress() {
		Tm1Session session = Tm1Session.builder().address("http://tm1:8010").basicAuth("admin", "").build();
		assertThat(session.serviceRoot()).isEqualTo("http://tm1:8010/api/v1/");
	}

}

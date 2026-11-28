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

	@Test
	void findsTheTm112ServiceRootAndCloseUrl() {
		Tm1Dialect sales = Tm1Dialect.v12("sales");
		assertThat(sales.name()).isEqualTo("TM1 12");
		assertThat(sales).isEqualTo(Tm1Dialect.v12("tm1", "sales"))
			.hasSameHashCodeAs(Tm1Dialect.v12("tm1", "sales"))
			.hasToString("Tm1Dialect[TM1 12, tm1/sales]");
		assertThat(sales.serviceRoot("http://engine:4444"))
			.isEqualTo("http://engine:4444/tm1/api/v1/Databases('sales')/");
		assertThat(sales.serviceRoot("https://engine.example.com:4444/"))
			.isEqualTo("https://engine.example.com:4444/tm1/api/v1/Databases('sales')/");
		assertThat(sales.serviceRoot("http://engine:4444/tm1/api/v1")).as("already an instance root")
			.isEqualTo("http://engine:4444/tm1/api/v1/Databases('sales')/");
		assertThat(sales.serviceRoot("http://engine:4444/tm1/api/v1/Databases('sales')/")).as("already a database root")
			.isEqualTo("http://engine:4444/tm1/api/v1/Databases('sales')/");
		assertThat(Tm1Dialect.v12("prod", "O'Neil Sales").serviceRoot("https://proxy/pa/"))
			.as("named instance, proxy path and encoded database name")
			.isEqualTo("https://proxy/pa/prod/api/v1/Databases('O''Neil%20Sales')/");
		assertThat(Tm1Dialect.v12("café").serviceRoot("http://engine:4444/tm1/api/v1/Databases('caf%c3%a9')/"))
			.as("already a database root with lowercase percent-encoding")
			.isEqualTo("http://engine:4444/tm1/api/v1/Databases('caf%C3%A9')/");

		assertThat(Tm1Dialect.V11.closeUrl("http://tm1:8010/api/v1/"))
			.isEqualTo("http://tm1:8010/api/v1/ActiveSession/tm1.Close");
		assertThat(sales.closeUrl("http://engine:4444/tm1/api/v1/Databases('sales')/"))
			.isEqualTo("http://engine:4444/tm1/api/v1/ActiveSession/tm1s.Close");
		assertThat(sales.closeUrl("https://proxy/pa/tm1/api/v1/Databases('O''Neil%20Sales')"))
			.isEqualTo("https://proxy/pa/tm1/api/v1/ActiveSession/tm1s.Close");

		Tm1Session dialectFirst = Tm1Session.builder()
			.dialect(sales)
			.address("http://engine:4444")
			.basicAuth("admin", "")
			.build();
		assertThat(dialectFirst.serviceRoot()).isEqualTo("http://engine:4444/tm1/api/v1/Databases('sales')/");

		Tm1Session addressFirst = Tm1Session.builder()
			.address("http://engine:4444")
			.dialect(sales)
			.basicAuth("admin", "")
			.build();
		assertThat(addressFirst.serviceRoot()).isEqualTo("http://engine:4444/tm1/api/v1/Databases('sales')/");
	}

	@Test
	void refusesInvalidTm112DialectInputs() {
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Dialect.v12("  "));
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Dialect.v12("", "sales"));
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Dialect.v12("a/b", "sales"));
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Dialect.v12("..", "sales"));
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Dialect.v12("a[b", "sales"));
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Dialect.v12("tm1", " "));
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Dialect.v12("sales").serviceRoot("engine:4444"));
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Dialect.v12("sales").serviceRoot("ftp://engine:4444"));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> Tm1Dialect.v12("sales").serviceRoot("http://engine:4444?"));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> Tm1Dialect.v12("sales").serviceRoot("http://engine:4444#"));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> Tm1Dialect.v12("sales").serviceRoot("http:///tm1/api/v1"));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> Tm1Dialect.v12("sales").serviceRoot("http://engine:4444/tm1/api/v1/Databases('other')/"));
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Dialect.v12("sales").closeUrl("http://engine:4444/"));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> Tm1Dialect.v12("sales").closeUrl("http://engine:4444/tm1/api/v1/"));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> Tm1Dialect.v12("sales").closeUrl("http://engine:4444/api/v1/"));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> Tm1Dialect.v12("sales").closeUrl("http://engine:4444/api/v1/Databases('sales')/"));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> Tm1Dialect.v12("sales").closeUrl("http://engine:4444//api/v1/Databases('sales')/"));
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Dialect.V11.closeUrl("not a url"));
	}

	@Test
	void findsTheTm112AuthSessionUrl() {
		Tm1Dialect sales = Tm1Dialect.v12("sales");
		assertThat(Tm1Dialect.V11.authSessionUrl("http://tm1:8010/api/v1/")).isEmpty();
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Dialect.V11.authSessionUrl("not a url"));
		assertThat(sales.authSessionUrl("http://engine:4444/tm1/api/v1/Databases('sales')/"))
			.contains("http://engine:4444/tm1/auth/v1/session");
		assertThat(Tm1Dialect.v12("prod", "O'Neil Sales")
			.authSessionUrl("https://proxy/pa/prod/api/v1/Databases('O''Neil%20Sales')"))
			.contains("https://proxy/pa/prod/auth/v1/session");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> sales.authSessionUrl("http://engine:4444/api/v1/Databases('sales')/"));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> sales.authSessionUrl("http://engine:4444//api/v1/Databases('sales')/"));
		assertThatIllegalArgumentException().isThrownBy(() -> sales.authSessionUrl("http://engine:4444/tm1/api/v1/"));
	}

}

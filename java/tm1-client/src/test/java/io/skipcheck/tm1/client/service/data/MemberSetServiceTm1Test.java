// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.LiveServer;
import io.skipcheck.tm1.client.LiveTm1;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.CellsetAxis;
import io.skipcheck.tm1.client.odata.ODataQuery;

/** MemberSetService, on the }Clients dimension every TM1 has. */
@LiveTm1
class MemberSetServiceTm1Test {

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
	void keepsASetExpressionsResultByOrdinalButListsNone() {
		CellsetAxis set = tm1.memberSets()
			.execute("{TM1SUBSETALL([}Clients].[}Clients])}",
					ODataQuery.none().expand("Tuples", t -> t.expand("Members", m -> m.select("Name"))))
			.block(WAIT);
		assertThat(set.tuples())
			.anySatisfy(t -> assertThat(t.members()).extracting(m -> m.name()).containsExactly("Admin"));
		int ordinal = set.ordinal();
		assertThat(tm1.memberSets().get(ordinal, ODataQuery.none()).block(WAIT).cardinality())
			.isEqualTo(set.cardinality());
		assertThat(tm1.memberSets().list(ODataQuery.none()).collectList().block(WAIT)).as("TM1 lists none").isEmpty();

		tm1.memberSets().delete(ordinal).block(WAIT);
		assertThatThrownBy(() -> tm1.memberSets().get(ordinal, ODataQuery.none()).block(WAIT))
			.isInstanceOf(Tm1NotFoundException.class);
	}

}

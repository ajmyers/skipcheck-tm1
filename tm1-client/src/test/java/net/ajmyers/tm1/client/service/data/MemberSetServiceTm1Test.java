package net.ajmyers.tm1.client.service.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.error.Tm1NotFoundException;
import net.ajmyers.tm1.client.model.CellsetAxis;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;

/** MemberSetService, on the }Clients dimension every TM1 has. */
@RequiresTm1
class MemberSetServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	@BeforeAll
	static void connect(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
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

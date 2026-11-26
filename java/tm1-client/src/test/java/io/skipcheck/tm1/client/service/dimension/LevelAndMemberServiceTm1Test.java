// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.dimension;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.LiveServer;
import io.skipcheck.tm1.client.LiveTm1;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.DimensionCreate;
import io.skipcheck.tm1.client.model.EdgeCreate;
import io.skipcheck.tm1.client.model.ElementCreate;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.model.HierarchyCreate;
import io.skipcheck.tm1.client.model.Level;
import io.skipcheck.tm1.client.model.Member;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * LevelService, on World = Europe (France, Spain) + Asia (Japan). MemberServiceTm1Test
 * has the members' own tests.
 */
@LiveTm1
class LevelAndMemberServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	private static String d;

	@BeforeAll
	static void createDimension(LiveServer server) {
		tm1 = server.clientBuilder().build();
		d = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		tm1.dimensions()
			.create(DimensionCreate.builder()
				.name(d)
				.hierarchies(List.of(HierarchyCreate.builder()
					.name(d)
					.elements(List.of(consolidated("World"), consolidated("Europe"), consolidated("Asia"),
							numeric("France"), numeric("Spain"), numeric("Japan")))
					.edges(List.of(edge("World", "Europe"), edge("World", "Asia"), edge("Europe", "France"),
							edge("Europe", "Spain"), edge("Asia", "Japan")))
					.build()))
				.build())
			.block(WAIT);
	}

	@AfterAll
	static void deleteDimension() {
		tm1.dimensions().delete(d).block(WAIT);
		tm1.close().block(WAIT);
	}

	@Test
	void listsLevelsAndTheirMembers() {
		List<Level> levels = tm1.levels().list(d, d, ODataQuery.none()).collectList().block(WAIT);
		assertThat(levels).extracting(Level::number).containsExactly(0, 1, 2);
		assertThat(levels).extracting(Level::cardinality).containsExactly(1, 2, 3);
		assertThat(tm1.levels().get(d, d, 2, ODataQuery.none()).block(WAIT).cardinality()).isEqualTo(3);
		assertThat(
				names(tm1.levels().members(d, d, 2, ODataQuery.none().select(Member.NAME)).collectList().block(WAIT)))
			.containsExactlyInAnyOrder("France", "Spain", "Japan");
		assertThat(tm1.levels().hierarchy(d, d, 0, ODataQuery.none()).block(WAIT).name()).isEqualTo(d);
	}

	private static List<String> names(List<Member> members) {
		return members.stream().map(Member::name).toList();
	}

	private static ElementCreate numeric(String name) {
		return ElementCreate.builder().name(name).type(ElementType.NUMERIC).build();
	}

	private static ElementCreate consolidated(String name) {
		return ElementCreate.builder().name(name).type(ElementType.CONSOLIDATED).build();
	}

	private static EdgeCreate edge(String parent, String component) {
		return EdgeCreate.builder().parentName(parent).componentName(component).weight(1.0).build();
	}

}

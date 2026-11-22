package net.ajmyers.tm1.client.service.dimension;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.model.DimensionPatch;
import net.ajmyers.tm1.client.model.EdgePatch;
import net.ajmyers.tm1.client.model.ElementPatch;
import net.ajmyers.tm1.client.model.ElementType;
import net.ajmyers.tm1.client.model.HierarchyPatch;
import net.ajmyers.tm1.client.model.Level;
import net.ajmyers.tm1.client.model.Member;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;

/** LevelService and MemberService, on World = Europe (France, Spain) + Asia (Japan). */
@RequiresTm1
class LevelAndMemberServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	private static String d;

	@BeforeAll
	static void createDimension(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
		d = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		tm1.dimensions()
			.create(DimensionPatch.builder()
				.name(d)
				.hierarchies(List.of(HierarchyPatch.builder()
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

	@Test
	void navigatesMembers() {
		assertThat(tm1.members().list(d, d, ODataQuery.none()).collectList().block(WAIT)).hasSize(6)
			.allSatisfy(m -> assertThat(m).isInstanceOf(Member.Plain.class));
		assertThat(tm1.members().get(d, d, "Europe", ODataQuery.none()).block(WAIT).uniqueName())
			.isEqualTo("[" + d + "].[" + d + "].[Europe]");
		assertThat(tm1.members().parent(d, d, "France", ODataQuery.none()).block(WAIT).name()).isEqualTo("Europe");
		assertThat(tm1.members().parent(d, d, "World", ODataQuery.none()).blockOptional(WAIT)).as("a root has none")
			.isEmpty();
		assertThat(names(tm1.members().children(d, d, "Europe", ODataQuery.none()).collectList().block(WAIT)))
			.containsExactly("France", "Spain");
		assertThat(tm1.members().element(d, d, "Japan", ODataQuery.none()).block(WAIT).type())
			.isEqualTo(ElementType.NUMERIC);
		assertThat(tm1.members().level(d, d, "Japan", ODataQuery.none()).block(WAIT).number()).isEqualTo(2);
		assertThat(tm1.members().hierarchy(d, d, "Japan", ODataQuery.none()).block(WAIT).name()).isEqualTo(d);
	}

	private static List<String> names(List<Member> members) {
		return members.stream().map(Member::name).toList();
	}

	private static ElementPatch numeric(String name) {
		return ElementPatch.builder().name(name).type(ElementType.NUMERIC).build();
	}

	private static ElementPatch consolidated(String name) {
		return ElementPatch.builder().name(name).type(ElementType.CONSOLIDATED).build();
	}

	private static EdgePatch edge(String parent, String component) {
		return EdgePatch.builder().parentName(parent).componentName(component).weight(1.0).build();
	}

}

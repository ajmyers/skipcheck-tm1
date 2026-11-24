// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.dimension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.error.Tm1PreconditionFailedException;
import io.skipcheck.tm1.client.model.DimensionCreate;
import io.skipcheck.tm1.client.model.EdgePatch;
import io.skipcheck.tm1.client.model.Element;
import io.skipcheck.tm1.client.model.ElementCreate;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.model.Hierarchy;
import io.skipcheck.tm1.client.model.HierarchyCreate;
import io.skipcheck.tm1.client.model.HierarchyPatch;
import io.skipcheck.tm1.client.model.Member;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

@RequiresTm1
class HierarchyServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	private static HierarchyService hierarchies;

	private String dimension;

	@BeforeAll
	static void connect(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
		hierarchies = tm1.hierarchies();
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	@BeforeEach
	void createDimension() {
		dimension = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		tm1.dimensions()
			.create(DimensionCreate.builder().name(dimension).hierarchies(List.of(structure(dimension))).build())
			.block(WAIT);
	}

	@AfterEach
	void deleteDimension() {
		tm1.dimensions().delete(dimension).block(WAIT);
	}

	@Test
	void createsListsReadsAndDeletesAHierarchy() {
		assertThat(hierarchies.create(dimension, structure("Alt")).block(WAIT).name()).isEqualTo("Alt");
		assertThat(hierarchies.list(dimension, ODataQuery.none().select(Hierarchy.NAME))
			.map(Hierarchy::name)
			.collectList()
			.block(WAIT)).contains(dimension, "Alt");
		assertThat(
				hierarchies.get(dimension, "Alt", ODataQuery.none().expand(Hierarchy.ELEMENTS)).block(WAIT).elements())
			.hasSize(4);
		assertThat(hierarchies.defaultHierarchy(dimension, ODataQuery.none()).block(WAIT).name()).isEqualTo(dimension);
		assertThat(hierarchies.dimension(dimension, "Alt", ODataQuery.none()).block(WAIT).name()).isEqualTo(dimension);

		hierarchies.delete(dimension, "Alt").block(WAIT);
		assertThat(hierarchies.list(dimension, ODataQuery.none()).map(Hierarchy::name).collectList().block(WAIT))
			.doesNotContain("Alt");
	}

	@Test
	void refusesToReplaceElementsThroughAPlainUpdate() {
		assertThatIllegalArgumentException().isThrownBy(
				() -> hierarchies.update(dimension, dimension, HierarchyPatch.builder().elements(List.of()).build()))
			.withMessageContaining("replaceStructure");
		assertThatIllegalArgumentException().as("binding the collection changes the structure too")
			.isThrownBy(() -> hierarchies.update(dimension, dimension,
					HierarchyPatch.builder()
						.bindElements(ODataPath.of("Dimensions")
							.key(dimension)
							.nav("Hierarchies")
							.key(dimension)
							.nav("Elements")
							.key("North"))
						.build()));
		assertThatIllegalArgumentException().isThrownBy(() -> hierarchies.update(dimension, dimension,
				HierarchyPatch.builder().edges(List.of(edge("Total", "East"))).build(), "W/\"x\""));
	}

	@Test
	void replacesTheStructureDeletingWhatItDoesntList() {
		hierarchies
			.replaceStructure(dimension, dimension, List.of(consolidated("Total"), numeric("North")),
					List.of(edge("Total", "North")))
			.block(WAIT);
		assertThat(elements()).extracting(Element::name).containsExactlyInAnyOrder("Total", "North");
	}

	@Test
	void updatesOnlyWhenTheEtagIsCurrentAndIgnoresVisible() {
		String etag = hierarchies.get(dimension, dimension, ODataQuery.none()).block(WAIT).etag();
		hierarchies.update(dimension, dimension, HierarchyPatch.builder().elementsSortType("ByInput").build(), etag)
			.block(WAIT);
		// HierarchyPatch doesn't offer Visible, as TM1 ignores it: sent as it is, to pin
		// it
		tm1.session()
			.execute(Tm1Request.patch(ODataPath.of("Dimensions").key(dimension).nav("Hierarchies").key(dimension),
					Map.of("Visible", false)))
			.block(WAIT);
		assertThat(hierarchies.get(dimension, dimension, ODataQuery.none()).block(WAIT).visible())
			.as("TM1 accepts Visible in a PATCH and ignores it")
			.isTrue();

		hierarchies.replaceStructure(dimension, dimension, List.of(numeric("North")), List.of()).block(WAIT);
		assertThatThrownBy(
				() -> hierarchies.update(dimension, dimension, HierarchyPatch.builder().build(), etag).block(WAIT))
			.isInstanceOf(Tm1PreconditionFailedException.class);
	}

	@Test
	void readsTheAllDefaultAndRootMembers() {
		assertThat(hierarchies.allMember(dimension, dimension, ODataQuery.none()).blockOptional(WAIT))
			.as("no all member with two roots: TM1 sends 204")
			.isEmpty();
		assertThat(hierarchies.defaultMember(dimension, dimension, ODataQuery.none()).block(WAIT).name()).isNotBlank();
		assertThat(roots()).containsExactly("Total", "East");

		hierarchies
			.replaceStructure(dimension, dimension, List.of(consolidated("Total"), numeric("North")),
					List.of(edge("Total", "North")))
			.block(WAIT);
		assertThat(hierarchies.allMember(dimension, dimension, ODataQuery.none()).block(WAIT).name())
			.as("with one root, the all member is that root")
			.isEqualTo("Total");
	}

	@Test
	void savesACopy() {
		assertThat(hierarchies.saveAs(dimension, dimension, "Copy").block(WAIT).name()).isEqualTo("Copy");
		assertThat(hierarchies.saveAs(dimension, dimension, "Copy", true, false).block(WAIT).name()).isEqualTo("Copy");
		assertThat(
				hierarchies.get(dimension, "Copy", ODataQuery.none().expand(Hierarchy.ELEMENTS)).block(WAIT).elements())
			.hasSize(4);
	}

	@Test
	void placesARootBeforeAnotherOrLastWithoutBefore() {
		hierarchies.setElement(dimension, dimension, "East", "Total").block(WAIT);
		assertThat(roots()).containsExactly("East", "Total");
		hierarchies.setElement(dimension, dimension, "East").block(WAIT);
		assertThat(roots()).as("no Before puts it last, though $metadata says first").containsExactly("Total", "East");
	}

	private List<String> roots() {
		return hierarchies.rootMembers(dimension, dimension, ODataQuery.none().select(Member.NAME))
			.map(Member::name)
			.collectList()
			.block(WAIT);
	}

	private List<Element> elements() {
		return hierarchies.get(dimension, dimension, ODataQuery.none().expand(Hierarchy.ELEMENTS))
			.block(WAIT)
			.elements();
	}

	private static HierarchyCreate structure(String name) {
		return HierarchyCreate.builder()
			.name(name)
			.elements(List.of(consolidated("Total"), numeric("North"), numeric("South"), numeric("East")))
			.edges(List.of(edge("Total", "North"), edge("Total", "South")))
			.build();
	}

	private static ElementCreate numeric(String name) {
		return ElementCreate.builder().name(name).type(ElementType.NUMERIC).build();
	}

	private static ElementCreate consolidated(String name) {
		return ElementCreate.builder().name(name).type(ElementType.CONSOLIDATED).build();
	}

	private static EdgePatch edge(String parent, String component) {
		return EdgePatch.builder().parentName(parent).componentName(component).weight(1.0).build();
	}

}

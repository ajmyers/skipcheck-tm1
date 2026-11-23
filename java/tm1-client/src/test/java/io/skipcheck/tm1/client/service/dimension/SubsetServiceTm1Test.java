// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.dimension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.error.Tm1PreconditionFailedException;
import io.skipcheck.tm1.client.model.DimensionPatch;
import io.skipcheck.tm1.client.model.Element;
import io.skipcheck.tm1.client.model.ElementPatch;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.model.HierarchyPatch;
import io.skipcheck.tm1.client.model.Subset;
import io.skipcheck.tm1.client.model.SubsetPatch;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

/** SubsetService, on a dimension of A, B, C and D. */
@RequiresTm1
class SubsetServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	private static SubsetService subsets;

	private String d;

	@BeforeAll
	static void connect(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
		subsets = tm1.subsets();
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	@BeforeEach
	void createDimension() {
		d = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		tm1.dimensions()
			.create(DimensionPatch.builder()
				.name(d)
				.hierarchies(List.of(HierarchyPatch.builder()
					.name(d)
					.elements(List.of(numeric("A"), numeric("B"), numeric("C"), numeric("D")))
					.build()))
				.build())
			.block(WAIT);
	}

	@AfterEach
	void deleteDimension() {
		tm1.dimensions().delete(d).block(WAIT);
	}

	@Test
	void createsStaticAndDynamicSubsetsReadsUpdatesAndDeletesThem() {
		subsets
			.create(d, d, SubsetScope.PUBLIC,
					SubsetPatch.builder().name("Static").bindElements(el("A"), el("B")).build())
			.block(WAIT);
		subsets
			.create(d, d, SubsetScope.PUBLIC,
					SubsetPatch.builder().name("Dynamic").expression("{TM1SUBSETALL([" + d + "].[" + d + "])}").build())
			.block(WAIT);
		assertThat(
				subsets.list(d, d, SubsetScope.PUBLIC, ODataQuery.none()).map(Subset::name).collectList().block(WAIT))
			.containsExactlyInAnyOrder("Static", "Dynamic");
		assertThat(elements(SubsetScope.PUBLIC, "Static")).containsExactly("A", "B");
		assertThat(elements(SubsetScope.PUBLIC, "Dynamic")).containsExactlyInAnyOrder("A", "B", "C", "D");

		String etag = subsets.get(d, d, SubsetScope.PUBLIC, "Dynamic", ODataQuery.none()).block(WAIT).etag();
		subsets
			.update(d, d, SubsetScope.PUBLIC, "Dynamic",
					SubsetPatch.builder().expression("{[" + d + "].[" + d + "].[C]}").build(), etag)
			.block(WAIT);
		assertThat(elements(SubsetScope.PUBLIC, "Dynamic")).containsExactly("C");
		assertThatThrownBy(() -> subsets
			.update(d, d, SubsetScope.PUBLIC, "Dynamic", SubsetPatch.builder().expression("{}").build(), etag)
			.block(WAIT)).isInstanceOf(Tm1PreconditionFailedException.class);
		assertThat(subsets.hierarchy(d, d, SubsetScope.PUBLIC, "Static", ODataQuery.none()).block(WAIT).name())
			.isEqualTo(d);

		subsets.delete(d, d, SubsetScope.PUBLIC, "Static").block(WAIT);
		assertThat(
				subsets.list(d, d, SubsetScope.PUBLIC, ODataQuery.none()).map(Subset::name).collectList().block(WAIT))
			.containsExactly("Dynamic");
	}

	@Test
	void appendsAndClearsElementsByNameAndRefusesThemInAnUpdate() {
		subsets.create(d, d, SubsetScope.PUBLIC, SubsetPatch.builder().name("S").bindElements(el("A")).build())
			.block(WAIT);
		subsets.addElements(d, d, SubsetScope.PUBLIC, "S", List.of("C", "B")).block(WAIT);
		assertThat(elements(SubsetScope.PUBLIC, "S")).as("appended, in order").containsExactly("A", "C", "B");

		subsets.clearElements(d, d, SubsetScope.PUBLIC, "S").block(WAIT);
		assertThat(elements(SubsetScope.PUBLIC, "S")).isEmpty();

		assertThatIllegalArgumentException()
			.isThrownBy(() -> subsets.update(d, d, SubsetScope.PUBLIC, "S",
					SubsetPatch.builder().bindElements(el("D")).build()))
			.withMessageContaining("addElements");
	}

	@Test
	void placesElementsLastBeforeOrAfterOthers() {
		subsets.create(d, d, SubsetScope.PUBLIC, SubsetPatch.builder().name("S").bindElements(el("A"), el("B")).build())
			.block(WAIT);
		subsets.setElement(d, d, SubsetScope.PUBLIC, "S", "C").block(WAIT);
		assertThat(elements(SubsetScope.PUBLIC, "S")).as("no position puts it last").containsExactly("A", "B", "C");
		subsets.setElementBefore(d, d, SubsetScope.PUBLIC, "S", "D", "B").block(WAIT);
		assertThat(elements(SubsetScope.PUBLIC, "S")).containsExactly("A", "D", "B", "C");
		subsets.setElementAfter(d, d, SubsetScope.PUBLIC, "S", "A", "B").block(WAIT);
		assertThat(elements(SubsetScope.PUBLIC, "S")).as("moved, not added again").containsExactly("D", "B", "A", "C");
	}

	@Test
	void keepsPrivateSubsetsApartAndSavesCopies() {
		subsets.create(d, d, SubsetScope.PRIVATE, SubsetPatch.builder().name("Mine").bindElements(el("D")).build())
			.block(WAIT);
		assertThat(
				subsets.list(d, d, SubsetScope.PRIVATE, ODataQuery.none()).map(Subset::name).collectList().block(WAIT))
			.containsExactly("Mine");
		assertThat(subsets.list(d, d, SubsetScope.PUBLIC, ODataQuery.none()).collectList().block(WAIT)).isEmpty();

		subsets
			.create(d, d, SubsetScope.PUBLIC,
					SubsetPatch.builder().name("All").expression("{TM1SUBSETALL([" + d + "].[" + d + "])}").build())
			.block(WAIT);
		assertThat(subsets.saveAs(d, d, SubsetScope.PUBLIC, "All", "Copy").block(WAIT).expression()).isNotBlank();
		Subset frozen = subsets.saveAs(d, d, SubsetScope.PUBLIC, "All", "Frozen", true, true).block(WAIT);
		assertThat(frozen.expression()).as("static").isNullOrEmpty();
		assertThat(elements(SubsetScope.PRIVATE, "Frozen")).containsExactlyInAnyOrder("A", "B", "C", "D");
	}

	@Test
	void createsSessionSubsetsUnderTheServersName() {
		Subset defined = subsets
			.createSessionSubset(d, d,
					SubsetPatch.builder().name("Wanted").expression("{[" + d + "].[" + d + "].[B]}").build())
			.block(WAIT);
		assertThat(defined.name()).as("TM1 ignores the name it's given").isNotEqualTo("Wanted");
		assertThat(elements(SubsetScope.SESSION, defined.name())).containsExactly("B");

		subsets.create(d, d, SubsetScope.PUBLIC, SubsetPatch.builder().name("S").bindElements(el("C"), el("D")).build())
			.block(WAIT);
		Subset copied = subsets.createSessionSubsetFrom(d, d, SubsetScope.PUBLIC, "S").block(WAIT);
		assertThat(elements(SubsetScope.SESSION, copied.name())).containsExactly("C", "D");
		assertThat(subsets.list(d, d, SubsetScope.SESSION, ODataQuery.none()).collectList().block(WAIT))
			.as("TM1 lists no session subsets, though each reads by its name")
			.isEmpty();
	}

	private List<String> elements(SubsetScope scope, String subset) {
		return subsets.elements(d, d, scope, subset, ODataQuery.none().select(Element.NAME))
			.map(Element::name)
			.collectList()
			.block(WAIT);
	}

	private ODataPath el(String name) {
		return ODataPath.of("Dimensions").key(d).nav("Hierarchies").key(d).nav("Elements").key(name);
	}

	private static ElementPatch numeric(String name) {
		return ElementPatch.builder().name(name).type(ElementType.NUMERIC).build();
	}

}

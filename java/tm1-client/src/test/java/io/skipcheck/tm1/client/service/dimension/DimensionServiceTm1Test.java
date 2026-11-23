// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.dimension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.error.Tm1PreconditionFailedException;
import io.skipcheck.tm1.client.model.Dimension;
import io.skipcheck.tm1.client.model.DimensionPatch;
import io.skipcheck.tm1.client.model.ElementPatch;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.model.Hierarchy;
import io.skipcheck.tm1.client.model.HierarchyPatch;
import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

@RequiresTm1
class DimensionServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	private static DimensionService dimensions;

	@BeforeAll
	static void connect(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
		dimensions = tm1.dimensions();
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	@Test
	void createsReadsListsAndDeletesADimension() {
		String name = unique();
		Dimension created = dimensions.create(dimension(name)).block(WAIT);
		assertThat(created.name()).isEqualTo(name);

		Dimension read = dimensions
			.get(name, ODataQuery.none().expand(Dimension.HIERARCHIES, h -> h.expand(Hierarchy.ELEMENTS)))
			.block(WAIT);
		assertThat(read.etag()).isNotBlank();
		assertThat(read.hierarchies()).singleElement()
			.satisfies(h -> assertThat(h.elements()).extracting(e -> e.name())
				.containsExactlyInAnyOrder("North", "South"));

		assertThat(dimensions
			.list(ODataQuery.none().select(Dimension.NAME).filter(Filter.startsWith(Dimension.NAME, name)))
			.map(Dimension::name)
			.collectList()
			.block(WAIT)).containsExactly(name);

		dimensions.delete(name).block(WAIT);
		assertThatThrownBy(() -> dimensions.get(name, ODataQuery.none()).block(WAIT))
			.isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void updatesOnlyWhenTheEtagIsCurrent() {
		String name = unique();
		dimensions.create(dimension(name)).block(WAIT);
		try {
			String etag = dimensions.get(name, ODataQuery.none()).block(WAIT).etag();
			DimensionPatch patch = DimensionPatch.builder().name(name).build();
			dimensions.update(name, patch, etag).block(WAIT);

			dimensions.addAllLeavesHierarchy(name).block(WAIT);
			assertThat(dimensions.get(name, ODataQuery.none()).block(WAIT).etag()).as("a new hierarchy changes it")
				.isNotEqualTo(etag);
			assertThatThrownBy(() -> dimensions.update(name, patch, etag).block(WAIT))
				.isInstanceOf(Tm1PreconditionFailedException.class);
		}
		finally {
			dimensions.delete(name).block(WAIT);
		}
	}

	@Test
	void ignoresHierarchiesAndTheAllLeavesNameInAPatch() {
		String name = unique();
		dimensions.create(dimension(name)).block(WAIT);
		try {
			dimensions
				.update(name,
						DimensionPatch.builder()
							.allLeavesHierarchyName("AllLeaves")
							.hierarchies(List.of(HierarchyPatch.builder()
								.name(name)
								.elements(List.of(element("North"), element("South"), element("East")))
								.build()))
							.build())
				.block(WAIT);
			Dimension read = dimensions
				.get(name, ODataQuery.none().expand(Dimension.HIERARCHIES, h -> h.expand(Hierarchy.ELEMENTS)))
				.block(WAIT);
			assertThat(read.allLeavesHierarchyName()).as("TM1 accepts the PATCH and ignores it").isEmpty();
			assertThat(read.hierarchies()).singleElement().satisfies(h -> assertThat(h.elements()).hasSize(2));
		}
		finally {
			dimensions.delete(name).block(WAIT);
		}
	}

	@Test
	void savesACopyAndAddsAnAllLeavesHierarchy() {
		String name = unique();
		dimensions.create(dimension(name)).block(WAIT);
		try {
			assertThat(dimensions.saveAs(name, name + "-copy").block(WAIT).name()).isEqualTo(name + "-copy");
			assertThat(dimensions.saveAs(name, name + "-copy", true, false).block(WAIT).name())
				.isEqualTo(name + "-copy");
			dimensions.delete(name + "-copy").block(WAIT);

			Hierarchy leaves = dimensions.addAllLeavesHierarchy(name).block(WAIT);
			assertThat(leaves.name()).isEqualTo("Leaves");
			assertThat(dimensions.get(name, ODataQuery.none().expand(Dimension.HIERARCHIES)).block(WAIT).hierarchies())
				.extracting(Hierarchy::name)
				.contains(name, "Leaves");
		}
		finally {
			dimensions.delete(name).block(WAIT);
		}
	}

	@Test
	void locksAndReserves() {
		String name = unique();
		dimensions.create(dimension(name)).block(WAIT);
		try {
			dimensions.lock(name).then(dimensions.unlock(name)).block(WAIT);
			dimensions.reserve(name).then(dimensions.release(name)).block(WAIT);
		}
		finally {
			dimensions.delete(name).block(WAIT);
		}
	}

	private static DimensionPatch dimension(String name) {
		return DimensionPatch.builder()
			.name(name)
			.hierarchies(List
				.of(HierarchyPatch.builder().name(name).elements(List.of(element("North"), element("South"))).build()))
			.build();
	}

	private static ElementPatch element(String name) {
		return ElementPatch.builder().name(name).type(ElementType.NUMERIC).build();
	}

	private static String unique() {
		return "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
	}

}

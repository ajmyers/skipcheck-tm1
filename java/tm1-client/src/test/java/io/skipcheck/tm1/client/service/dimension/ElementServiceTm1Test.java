// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.dimension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
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
import io.skipcheck.tm1.client.error.Tm1BadRequestException;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.error.Tm1PreconditionFailedException;
import io.skipcheck.tm1.client.model.DimensionCreate;
import io.skipcheck.tm1.client.model.Edge;
import io.skipcheck.tm1.client.model.EdgeCreate;
import io.skipcheck.tm1.client.model.Element;
import io.skipcheck.tm1.client.model.ElementCreate;
import io.skipcheck.tm1.client.model.ElementPatch;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.model.HierarchyCreate;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

/**
 * ElementService, on a dimension with Total = North + South, and East and Label alone
 * (EdgeServiceTm1Test has the edges' own tests).
 */
@RequiresTm1
class ElementServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	private static ElementService elements;

	private static EdgeService edges;

	private String d;

	@BeforeAll
	static void connect(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
		elements = tm1.elements();
		edges = tm1.edges();
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	@BeforeEach
	void createDimension() {
		d = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		tm1.dimensions()
			.create(DimensionCreate.builder()
				.name(d)
				.hierarchies(List.of(HierarchyCreate.builder()
					.name(d)
					.elements(List.of(element("Total", ElementType.CONSOLIDATED), element("North", ElementType.NUMERIC),
							element("South", ElementType.NUMERIC), element("East", ElementType.NUMERIC),
							element("Label", ElementType.STRING)))
					.edges(List.of(edge("Total", "North"), edge("Total", "South")))
					.build()))
				.build())
			.block(WAIT);
	}

	@AfterEach
	void deleteDimension() {
		tm1.dimensions().delete(d).block(WAIT);
	}

	@Test
	void createsReadsUpdatesAndDeletesAnElement() {
		assertThat(elements.create(d, d, element("West", ElementType.NUMERIC)).block(WAIT).name()).isEqualTo("West");
		assertThat(elements.list(d, d, ODataQuery.none().select(Element.NAME))
			.map(Element::name)
			.collectList()
			.block(WAIT)).containsExactlyInAnyOrder("Total", "North", "South", "East", "Label", "West");

		String etag = elements.get(d, d, "West", ODataQuery.none()).block(WAIT).etag();
		elements.update(d, d, "West", ElementPatch.builder().type(ElementType.STRING).build(), etag).block(WAIT);
		assertThat(elements.get(d, d, "West", ODataQuery.none()).block(WAIT).type()).isEqualTo(ElementType.STRING);
		assertThatThrownBy(
				() -> elements.update(d, d, "West", ElementPatch.builder().type(ElementType.NUMERIC).build(), etag)
					.block(WAIT))
			.isInstanceOf(Tm1PreconditionFailedException.class);

		elements.delete(d, d, "West").block(WAIT);
		assertThatThrownBy(() -> elements.get(d, d, "West", ODataQuery.none()).block(WAIT))
			.isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void refusesNameAndAttributesInAnUpdateAsTm1IgnoresOrRejectsThem() {
		// ElementPatch offers neither: TM1 ignores the name and refuses Attributes
		assertThat(Arrays.stream(ElementPatch.Builder.class.getMethods()).map(Method::getName)).doesNotContain("name",
				"attributes");

		ODataPath east = ODataPath.of("Dimensions").key(d).nav("Hierarchies").key(d).nav("Elements").key("East");
		tm1.session().execute(Tm1Request.patch(east, Map.of("Name", "Est"))).block(WAIT);
		assertThat(elements.get(d, d, "East", ODataQuery.none()).block(WAIT).name()).as("TM1 ignores the new name")
			.isEqualTo("East");
		assertThatThrownBy(() -> tm1.session()
			.execute(Tm1Request.patch(east, Map.of("Attributes", Map.of("Caption", "E"))))
			.block(WAIT)).as("TM1 rejects attributes").isInstanceOf(Tm1BadRequestException.class);
	}

	@Test
	void navigatesParentsComponentsEdgesAndTheHierarchy() {
		assertThat(elements.parents(d, d, "North", ODataQuery.none()).map(Element::name).collectList().block(WAIT))
			.containsExactly("Total");
		assertThat(components("Total")).containsExactly("North", "South");
		assertThat(elements.edges(d, d, "Total", ODataQuery.none()).collectList().block(WAIT))
			.extracting(Edge::parentName, Edge::componentName)
			.containsExactly(tuple("Total", "North"), tuple("Total", "South"));
		assertThat(elements.hierarchy(d, d, "North", ODataQuery.none()).block(WAIT).name()).isEqualTo(d);
	}

	@Test
	void setsComponentsLastOrBeforeAnotherWithAWeight() {
		elements.setComponent(d, d, "Total", "East").block(WAIT);
		assertThat(components("Total")).as("no Before puts it last").containsExactly("North", "South", "East");
		elements.create(d, d, element("West", ElementType.NUMERIC)).block(WAIT);
		elements.setComponent(d, d, "Total", "West", "North", 2.0).block(WAIT);
		assertThat(components("Total")).containsExactly("West", "North", "South", "East");
		assertThat(edges.get(d, d, "Total", "West", ODataQuery.none()).block(WAIT).weight()).isEqualTo(2.0);
		elements.create(d, d, element("Far", ElementType.NUMERIC)).block(WAIT);
		elements.setComponent(d, d, "Total", "Far", -1.0).block(WAIT);
		assertThat(edges.get(d, d, "Total", "Far", ODataQuery.none()).block(WAIT).weight()).isEqualTo(-1.0);
	}

	@Test
	void locksAndReservesAnElement() {
		elements.lock(d, d, "North").then(elements.unlock(d, d, "North")).block(WAIT);
		elements.reserve(d, d, "North").then(elements.release(d, d, "North")).block(WAIT);
	}

	private List<String> components(String parent) {
		return elements.components(d, d, parent, ODataQuery.none()).map(Element::name).collectList().block(WAIT);
	}

	private static ElementCreate element(String name, ElementType type) {
		return ElementCreate.builder().name(name).type(type).build();
	}

	private static EdgeCreate edge(String parent, String component) {
		return EdgeCreate.builder().parentName(parent).componentName(component).weight(1.0).build();
	}

}

package net.ajmyers.tm1.client.service.dimension;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.model.DimensionPatch;
import net.ajmyers.tm1.client.model.EdgePatch;
import net.ajmyers.tm1.client.model.Element;
import net.ajmyers.tm1.client.model.ElementPatch;
import net.ajmyers.tm1.client.model.ElementType;
import net.ajmyers.tm1.client.model.HierarchyPatch;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;

/** EdgeService, on a dimension with Total = North + South, and East alone. */
@RequiresTm1
class EdgeServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	private static EdgeService edges;

	private String d;

	@BeforeAll
	static void connect(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
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
			.create(DimensionPatch.builder()
				.name(d)
				.hierarchies(List.of(HierarchyPatch.builder()
					.name(d)
					.elements(List.of(element("Total", ElementType.CONSOLIDATED), element("North", ElementType.NUMERIC),
							element("South", ElementType.NUMERIC), element("East", ElementType.NUMERIC)))
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
	void createsReadsReweightsAndDeletesEdges() {
		assertThat(edges.list(d, d, ODataQuery.none()).collectList().block(WAIT)).hasSize(2);
		assertThat(edges.get(d, d, "Total", "North", ODataQuery.none()).block(WAIT).weight()).isEqualTo(1.0);
		edges.update(d, d, "Total", "North", EdgePatch.builder().weight(3.0).build()).block(WAIT);
		assertThat(edges.get(d, d, "Total", "North", ODataQuery.none()).block(WAIT).weight()).isEqualTo(3.0);

		assertThat(edges.parent(d, d, "Total", "North", ODataQuery.none()).block(WAIT).name()).isEqualTo("Total");
		assertThat(edges.component(d, d, "Total", "North", ODataQuery.none()).block(WAIT).name()).isEqualTo("North");
		assertThat(edges.hierarchy(d, d, "Total", "North", ODataQuery.none()).block(WAIT).name()).isEqualTo(d);

		assertThat(edges.create(d, d, edge("Total", "East")).block(WAIT).componentName()).isEqualTo("East");
		edges.delete(d, d, "Total", "North").block(WAIT);
		assertThat(components("Total")).containsExactly("South", "East");
		assertThat(tm1.elements().parents(d, d, "North", ODataQuery.none()).collectList().block(WAIT))
			.as("still an element, now a root")
			.isEmpty();
	}

	private List<String> components(String parent) {
		return tm1.elements().components(d, d, parent, ODataQuery.none()).map(Element::name).collectList().block(WAIT);
	}

	private static ElementPatch element(String name, ElementType type) {
		return ElementPatch.builder().name(name).type(type).build();
	}

	private static EdgePatch edge(String parent, String component) {
		return EdgePatch.builder().parentName(parent).componentName(component).weight(1.0).build();
	}

}

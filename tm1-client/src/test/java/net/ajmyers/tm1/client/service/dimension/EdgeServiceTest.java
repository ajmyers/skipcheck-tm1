package net.ajmyers.tm1.client.service.dimension;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import net.ajmyers.tm1.client.conformance.Conformance;
import net.ajmyers.tm1.client.conformance.ConformanceTm1;
import net.ajmyers.tm1.client.error.Tm1ApiException;
import net.ajmyers.tm1.client.error.Tm1ErrorKind;
import net.ajmyers.tm1.client.error.Tm1NotFoundException;
import net.ajmyers.tm1.client.model.Edge;
import net.ajmyers.tm1.client.model.EdgePatch;
import net.ajmyers.tm1.client.model.Element;
import net.ajmyers.tm1.client.model.Hierarchy;
import net.ajmyers.tm1.client.odata.Filter;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;

/**
 * EdgeService against a fake TM1: what each method sends, and how it reads TM1's answer.
 * Every call is recorded to {@code spec/conformance/calls/EdgeServiceTest.json}, which
 * the Python, .NET and TypeScript clients replay.
 */
class EdgeServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private static final String EDGES = "Dimensions('Region')/Hierarchies('Region')/Edges";

	private static final String TOTAL_WEST = EDGES + "(ParentName='Total',ComponentName='West')";

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void listsEdges() {
		tm1().json("{\"value\":[{\"ParentName\":\"Total\",\"ComponentName\":\"West\",\"Weight\":1.0},"
				+ "{\"ParentName\":\"Total\",\"ComponentName\":\"East\",\"Weight\":-1.0}]}");
		List<Edge> edges = conformance.call("edges", "list", "Region", "Region",
				ODataQuery.none().filter(Filter.raw("ParentName eq 'Total'")).orderBy(Edge.COMPONENT_NAME));
		assertThat(edges).extracting(Edge::componentName).containsExactly("West", "East");
		assertThat(edges).extracting(Edge::weight).containsExactly(1.0, -1.0);
		assertThat(tm1().last().target()).startsWith(EDGES + "?$filter=").endsWith("&$orderby=ComponentName");

		tm1().json("{\"value\":[]}");
		List<Edge> none = conformance.call("edges", "list", "Region", "Region", ODataQuery.none());
		assertThat(none).isEmpty();
		assertThat(tm1().last().key()).isEqualTo("GET " + EDGES);
	}

	@Test
	void followsNextLinksAcrossPages() {
		tm1().json("{\"value\":[{\"ParentName\":\"Total\",\"ComponentName\":\"a\"}],\"@odata.nextLink\":\"" + EDGES
				+ "?$skiptoken=2\"}");
		tm1().json("{\"value\":[{\"ParentName\":\"Total\",\"ComponentName\":\"b\"}]}");
		List<Edge> edges = conformance.call("edges", "list", "Region", "Region",
				ODataQuery.none().select("ComponentName"));
		assertThat(edges).extracting(Edge::componentName).containsExactly("a", "b");
		assertThat(tm1().requests()).extracting(ConformanceTm1.Recorded::target)
			.containsExactly(EDGES + "?$select=ComponentName", EDGES + "?$skiptoken=2");
	}

	@Test
	void getsAnEdgeByItsTwoPartKey() {
		tm1().json(
				"{\"@odata.etag\":\"W/\\\"3\\\"\",\"ParentName\":\"Total\",\"ComponentName\":\"West\",\"Weight\":2.5}");
		Edge edge = conformance.call("edges", "get", "Region", "Region", "Total", "West",
				ODataQuery.none().select("Weight").expand("Parent", p -> p.select("Name")));
		assertThat(edge.weight()).isEqualTo(2.5);
		assertThat(edge.etag()).isEqualTo("W/\"3\"");
		assertThat(tm1().last().target()).isEqualTo(TOTAL_WEST + "?$select=Weight&$expand=Parent($select%3DName)");
	}

	@Test
	void quotesKeysWithApostrophesAndOtherCharacters() {
		tm1().json("{\"ParentName\":\"Bob's Total\",\"ComponentName\":\"A&B\"}");
		conformance.call("edges", "get", "Bob's", "Bob's", "Bob's Total", "A&B", ODataQuery.none());
		assertThat(tm1().last().target()).isEqualTo(
				"Dimensions('Bob''s')/Hierarchies('Bob''s')/Edges(ParentName='Bob''s%20Total',ComponentName='A%26B')");
	}

	@Test
	void encodesReservedAndNonAsciiCharactersInTheKey() {
		tm1().json("{\"ParentName\":\"Zürich/Nord\",\"ComponentName\":\"a+b=c?\"}");
		conformance.call("edges", "get", "Région", "Région", "Zürich/Nord", "a+b=c?", ODataQuery.none());
		assertThat(tm1().last().target()).startsWith("Dimensions('R").contains("/Edges(ParentName='Z");
	}

	@Test
	void aMissingEdgeIsNotFound() {
		tm1().error(404, "278", "Edge can not be found.");
		Throwable error = conformance.fail("edges", "get", "Region", "Region", "Total", "Nope", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void createsAnEdge() {
		tm1().json(201, "{\"ParentName\":\"Total\",\"ComponentName\":\"West\",\"Weight\":1.0}");
		Edge created = conformance.call("edges", "create", "Region", "Region",
				EdgePatch.builder().parentName("Total").componentName("West").weight(1.0).build());
		assertThat(created.componentName()).isEqualTo("West");
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key()).isEqualTo("POST " + EDGES);
		assertThat(post.header("Content-Type")).isEqualTo("application/json");
		assertThat(post.text()).isEqualTo("{\"ParentName\":\"Total\",\"ComponentName\":\"West\",\"Weight\":1.0}");

		ODataPath elements = ODataPath.of("Dimensions").key("Region").nav("Hierarchies").key("Region").nav("Elements");
		tm1().json(201, "{\"ParentName\":\"Total\",\"ComponentName\":\"East\",\"Weight\":-0.5}");
		conformance.call("edges", "create", "Region", "Region",
				EdgePatch.builder()
					.bindParent(elements.key("Total"))
					.bindComponent(elements.key("East"))
					.weight(-0.5)
					.build());
		assertThat(tm1().last().text()).isEqualTo("{\"Parent@odata.bind\":\"Dimensions('Region')/Hierarchies('Region')"
				+ "/Elements('Total')\",\"Component@odata.bind\":\"Dimensions('Region')/Hierarchies('Region')"
				+ "/Elements('East')\",\"Weight\":-0.5}");
	}

	@Test
	void anEdgeThatWouldMakeACycleIsRefused() {
		tm1().error(400, "278", "Circular reference: 'Total' is a descendant of 'West'.");
		Throwable error = conformance.fail("edges", "create", "Region", "Region",
				EdgePatch.builder().parentName("West").componentName("Total").build());
		assertThat(error).isInstanceOfSatisfying(Tm1ApiException.class, e -> {
			assertThat(e.kind()).isEqualTo(Tm1ErrorKind.OTHER);
			assertThat(e.status()).isEqualTo(400);
		});
	}

	@Test
	void updatesAnEdgesWeight() {
		tm1().status(204);
		Object none = conformance.call("edges", "update", "Region", "Region", "Total", "West",
				EdgePatch.builder().weight(-1.0).build());
		assertThat(none).isNull();
		assertThat(tm1().last().key()).isEqualTo("PATCH " + TOTAL_WEST);
		assertThat(tm1().last().text()).isEqualTo("{\"Weight\":-1.0}");
		assertThat(tm1().last().header("If-Match")).isNull();

		tm1().status(204);
		conformance.call("edges", "update", "Region", "Region", "Total", "West",
				EdgePatch.builder().weight(null).build());
		assertThat(tm1().last().text()).isEqualTo("{\"Weight\":null}");
	}

	@Test
	void deletesAnEdge() {
		tm1().status(204);
		conformance.call("edges", "delete", "Region", "Region", "Total", "West");
		assertThat(tm1().last().key()).isEqualTo("DELETE " + TOTAL_WEST);

		tm1().error(404, "278", "Edge can not be found.");
		Throwable error = conformance.fail("edges", "delete", "Region", "Region", "Total", "Nope");
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void readsAnEdgesParentComponentAndHierarchy() {
		tm1().json("{\"Name\":\"Total\",\"Type\":\"Consolidated\"}");
		Element parent = conformance.call("edges", "parent", "Region", "Region", "Total", "West",
				ODataQuery.none().select("Name", "Type"));
		assertThat(parent.name()).isEqualTo("Total");
		assertThat(tm1().last().key()).isEqualTo("GET " + TOTAL_WEST + "/Parent?$select=Name,Type");

		tm1().json("{\"Name\":\"West\",\"Type\":\"Numeric\"}");
		Element component = conformance.call("edges", "component", "Region", "Region", "Total", "West",
				ODataQuery.none());
		assertThat(component.name()).isEqualTo("West");
		assertThat(tm1().last().key()).isEqualTo("GET " + TOTAL_WEST + "/Component");

		tm1().json("{\"Name\":\"Region\"}");
		Hierarchy hierarchy = conformance.call("edges", "hierarchy", "Region", "Region", "Total", "West",
				ODataQuery.none().select("Name"));
		assertThat(hierarchy.name()).isEqualTo("Region");
		assertThat(tm1().last().key()).isEqualTo("GET " + TOTAL_WEST + "/Hierarchy?$select=Name");
	}

	@Test
	void refusesAMissingEdgeOrPatchSendingNothing() {
		assertThat(conformance.fail("edges", "create", "Region", "Region", null))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("edges", "update", "Region", "Region", "Total", "West", null))
			.isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

	@Test
	void refusesMissingNamesSendingNothing() {
		assertThat(conformance.fail("edges", "delete", "Region", "Region", null, "West"))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("edges", "delete", "Region", "Region", "Total", null))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("edges", "get", null, "Region", "Total", "West", ODataQuery.none()))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("edges", "update", "Region", null, "Total", "West",
				EdgePatch.builder().weight(1.0).build()))
			.isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}

package io.skipcheck.tm1.client.service.dimension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1BadRequestException;
import io.skipcheck.tm1.client.error.Tm1ConflictException;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.error.Tm1PreconditionFailedException;
import io.skipcheck.tm1.client.model.AttributesPatch;
import io.skipcheck.tm1.client.model.Edge;
import io.skipcheck.tm1.client.model.Element;
import io.skipcheck.tm1.client.model.ElementPatch;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.model.Hierarchy;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * ElementService against a fake TM1: what each method sends, and how it reads TM1's
 * answer. Every call is recorded to
 * {@code spec/conformance/calls/ElementServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay.
 */
class ElementServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private static final String E = "Dimensions('Region')/Hierarchies('Region')/Elements";

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void listsElementsWithAFilter() {
		tm1().json("{\"value\":[{\"Name\":\"Total\",\"Type\":\"Consolidated\",\"Level\":1}]}");
		List<Element> found = conformance.call("elements", "list", "Region", "Region",
				ODataQuery.none()
					.select(Element.NAME, Element.TYPE, Element.LEVEL)
					.filter(Element.TYPE.eq(ElementType.CONSOLIDATED))
					.orderBy(Element.NAME));
		assertThat(found).extracting(Element::type).containsExactly(ElementType.CONSOLIDATED);
		assertThat(tm1().last().target()).isEqualTo(E + "?$select=Name,Type,Level"
				+ "&$filter=(Type%20eq%20ibm.tm1.api.v1.ElementType'Consolidated')&$orderby=Name");
	}

	@Test
	void followsNextLinksAcrossPages() {
		tm1().json("{\"value\":[{\"Name\":\"a\"}],\"@odata.nextLink\":\"" + E + "?$skiptoken=1\"}");
		tm1().json("{\"value\":[{\"Name\":\"b\"}]}");
		List<Element> all = conformance.call("elements", "list", "Region", "Region", ODataQuery.none().top(100));
		assertThat(all).extracting(Element::name).containsExactly("a", "b");
		assertThat(tm1().requests()).extracting(ConformanceTm1.Recorded::target)
			.containsExactly(E + "?$top=100", E + "?$skiptoken=1");
	}

	@Test
	void getsAnElementWithItsEtagAndAttributes() {
		tm1().json("{\"@odata.etag\":\"W/\\\"3\\\"\",\"Name\":\"West\",\"Type\":\"Numeric\","
				+ "\"Attributes\":{\"Caption\":\"The West\"}}");
		Element element = conformance.call("elements", "get", "Region", "Region", "West",
				ODataQuery.none().select(Element.NAME, Element.TYPE, Element.ATTRIBUTES));
		assertThat(element.etag()).isEqualTo("W/\"3\"");
		assertThat(element.type()).isEqualTo(ElementType.NUMERIC);
		assertThat(tm1().last().key()).isEqualTo("GET " + E + "('West')?$select=Name,Type,Attributes");
	}

	@Test
	void escapesSpecialCharactersInNames() {
		for (String name : List.of("O'Brien", "North & South", "1/2", "100%", "a+b", "Zürich", "#?")) {
			tm1().json("{\"Name\":\"x\"}");
			conformance.call("elements", "get", "Region", "Region", name, ODataQuery.none());
		}
		assertThat(tm1().last().target()).isEqualTo(E + "('%23%3F')");
	}

	@Test
	void aMissingElementIsNotFound() {
		tm1().error(404, "278", "'Nope' can not be found in collection of type 'Element'.");
		Throwable error = conformance.fail("elements", "get", "Region", "Region", "Nope", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void createsAnElement() {
		tm1().json(201, "{\"Name\":\"West\",\"Type\":\"Numeric\"}");
		Element created = conformance.call("elements", "create", "Region", "Region",
				ElementPatch.builder().name("West").type(ElementType.NUMERIC).build());
		assertThat(created.name()).isEqualTo("West");
		assertThat(tm1().last().key()).isEqualTo("POST " + E);
		assertThat(tm1().last().text()).isEqualTo("{\"Name\":\"West\",\"Type\":\"Numeric\"}");

		tm1().error(409, "278", "Element 'West' already exists.");
		assertThat(conformance.fail("elements", "create", "Region", "Region",
				ElementPatch.builder().name("West").type(ElementType.NUMERIC).build()))
			.isInstanceOf(Tm1ConflictException.class);
	}

	@Test
	void updatesWithAndWithoutAnEtag() {
		tm1().status(204);
		Object none = conformance.call("elements", "update", "Region", "Region", "West",
				ElementPatch.builder().type(ElementType.STRING).build());
		assertThat(none).isNull();
		assertThat(tm1().last().key()).isEqualTo("PATCH " + E + "('West')");
		assertThat(tm1().last().text()).isEqualTo("{\"Type\":\"String\"}");
		assertThat(tm1().last().header("If-Match")).isNull();

		tm1().status(204);
		conformance.call("elements", "update", "Region", "Region", "West",
				ElementPatch.builder().type(ElementType.NUMERIC).build(), "W/\"3\"");
		assertThat(tm1().last().header("If-Match")).isEqualTo("W/\"3\"");

		tm1().error(412, "278", "stale");
		assertThat(conformance.fail("elements", "update", "Region", "Region", "West",
				ElementPatch.builder().type(ElementType.NUMERIC).build(), "W/\"0\""))
			.isInstanceOf(Tm1PreconditionFailedException.class);
	}

	@Test
	void changingTheTypeOfAConsolidationWithChildrenIsABadRequest() {
		tm1().error(400, "278", "Cannot change the type of a consolidated element with children.");
		Throwable error = conformance.fail("elements", "update", "Region", "Region", "Total",
				ElementPatch.builder().type(ElementType.NUMERIC).build());
		assertThat(error).isInstanceOf(Tm1BadRequestException.class);
	}

	@Test
	void anUpdateWithANameOrAttributesIsRefused() {
		assertThat(conformance.fail("elements", "update", "Region", "Region", "West",
				ElementPatch.builder().name("East").build()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("renamed");
		assertThat(conformance.fail("elements", "update", "Region", "Region", "West",
				ElementPatch.builder().name("East").build(), "W/\"1\""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(conformance.fail("elements", "update", "Region", "Region", "West",
				ElementPatch.builder().attributes(AttributesPatch.builder().build()).build()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("ElementAttributes");
		assertThat(tm1().requests()).isEmpty();
	}

	@Test
	void deletesAnElement() {
		tm1().status(204);
		Object none = conformance.call("elements", "delete", "Region", "Region", "West");
		assertThat(none).isNull();
		assertThat(tm1().last().key()).isEqualTo("DELETE " + E + "('West')");

		tm1().error(404, "278", "'Nope' can not be found in collection of type 'Element'.");
		assertThat(conformance.fail("elements", "delete", "Region", "Region", "Nope"))
			.isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void navigatesParentsComponentsEdgesAndTheHierarchy() {
		tm1().json("{\"value\":[{\"Name\":\"Total\"}]}");
		List<Element> parents = conformance.call("elements", "parents", "Region", "Region", "North",
				ODataQuery.none().select(Element.NAME));
		assertThat(parents).extracting(Element::name).containsExactly("Total");
		assertThat(tm1().last().key()).isEqualTo("GET " + E + "('North')/Parents?$select=Name");

		tm1().json("{\"value\":[{\"Name\":\"North\"},{\"Name\":\"South\"}]}");
		List<Element> components = conformance.call("elements", "components", "Region", "Region", "Total",
				ODataQuery.none());
		assertThat(components).extracting(Element::name).containsExactly("North", "South");
		assertThat(tm1().last().key()).isEqualTo("GET " + E + "('Total')/Components");

		tm1().json("{\"value\":[{\"ParentName\":\"Total\",\"ComponentName\":\"North\",\"Weight\":1},"
				+ "{\"ParentName\":\"Total\",\"ComponentName\":\"South\",\"Weight\":-1.5}]}");
		List<Edge> edges = conformance.call("elements", "edges", "Region", "Region", "Total",
				ODataQuery.none().orderBy("ComponentName"));
		assertThat(edges).extracting(Edge::weight).containsExactly(1.0, -1.5);
		assertThat(tm1().last().key()).isEqualTo("GET " + E + "('Total')/Edges?$orderby=ComponentName");

		tm1().json("{\"Name\":\"Region\"}");
		Hierarchy hierarchy = conformance.call("elements", "hierarchy", "Region", "Region", "North",
				ODataQuery.none().select("Name"));
		assertThat(hierarchy.name()).isEqualTo("Region");
		assertThat(tm1().last().key()).isEqualTo("GET " + E + "('North')/Hierarchy?$select=Name");
	}

	@Test
	void aRootHasNoParents() {
		tm1().json("{\"value\":[]}");
		List<Element> parents = conformance.call("elements", "parents", "Region", "Region", "Total", ODataQuery.none());
		assertThat(parents).isEmpty();
	}

	@Test
	void setsAComponentLastOrBeforeAnotherWithAWeight() {
		tm1().status(204);
		Object none = conformance.call("elements", "setComponent", "Region", "Region", "Total", "West");
		assertThat(none).isNull();
		assertThat(tm1().last().key()).isEqualTo("POST " + E + "('Total')/tm1.SetComponent");
		assertThat(tm1().last().text()).isEqualTo("{\"Element@odata.bind\":\"" + E + "('West')\"}");

		tm1().status(204);
		conformance.call("elements", "setComponent", "Region", "Region", "Total", "West", -1.0);
		assertThat(tm1().last().text()).isEqualTo("{\"Element@odata.bind\":\"" + E + "('West')\",\"Weight\":-1.0}");

		tm1().status(204);
		conformance.call("elements", "setComponent", "Region", "Region", "Total", "West", "North", 2.0);
		assertThat(tm1().last().text()).isEqualTo("{\"Element@odata.bind\":\"" + E
				+ "('West')\",\"Before@odata.bind\":\"" + E + "('North')\",\"Weight\":2.0}");

		tm1().status(204);
		conformance.call("elements", "setComponent", "Region", "Region", "Total", "O'Neil", 0.5);
		assertThat(tm1().last().text()).isEqualTo("{\"Element@odata.bind\":\"" + E + "('O''Neil')\",\"Weight\":0.5}");
	}

	@Test
	void settingAComponentThatWouldCycleIsABadRequest() {
		tm1().error(400, "278", "Circular reference.");
		Throwable error = conformance.fail("elements", "setComponent", "Region", "Region", "North", "Total");
		assertThat(error).isInstanceOf(Tm1BadRequestException.class);
	}

	@Test
	void locksUnlocksReservesAndReleases() {
		for (String operation : List.of("lock", "unlock", "reserve", "release")) {
			tm1().status(204);
			Object none = conformance.call("elements", operation, "Region", "Region", "West");
			assertThat(none).isNull();
			String action = Character.toUpperCase(operation.charAt(0)) + operation.substring(1);
			assertThat(tm1().last().key()).isEqualTo("POST " + E + "('West')/tm1." + action);
			assertThat(tm1().last().text()).isEqualTo("{}");
		}
	}

	@Test
	void refusesMissingNames() {
		assertThat(conformance.fail("elements", "get", "Region", "Region", null, ODataQuery.none()))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("elements", "list", "Region", null, ODataQuery.none()))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("elements", "setComponent", "Region", "Region", "Total", null))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("elements", "lock", "Region", "Region", null))
			.isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

	@Test
	void theOverloadWithBeforeNeedsOne() {
		// Not recorded: the other clients have one method, where no before means last
		assertThatNullPointerException()
			.isThrownBy(
					() -> conformance.client().elements().setComponent("Region", "Region", "Total", "West", null, 1.0))
			.withMessage("before");
	}

}

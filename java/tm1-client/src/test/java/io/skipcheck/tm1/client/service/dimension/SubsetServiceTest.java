// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.dimension;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1ApiException;
import io.skipcheck.tm1.client.error.Tm1ErrorKind;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.error.Tm1PreconditionFailedException;
import io.skipcheck.tm1.client.model.Element;
import io.skipcheck.tm1.client.model.ElementCreate;
import io.skipcheck.tm1.client.model.Hierarchy;
import io.skipcheck.tm1.client.model.Subset;
import io.skipcheck.tm1.client.model.SubsetCreate;
import io.skipcheck.tm1.client.model.SubsetPatch;
import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * SubsetService against a fake TM1: what each method sends, and how it reads TM1's
 * answer. Every call is recorded to
 * {@code spec/conformance/calls/SubsetServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay.
 */
class SubsetServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private static final String HIERARCHY = "Dimensions('Region')/Hierarchies('Region')";

	private static final ODataPath ELEMENTS = ODataPath.of("Dimensions")
		.key("Region")
		.nav("Hierarchies")
		.key("Region")
		.nav("Elements");

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void listsTheSubsetsOfEachScope() {
		tm1().json("{\"value\":[{\"Name\":\"West Coast\"}]}");
		ODataQuery query = ODataQuery.none()
			.select(Subset.NAME)
			.filter(Filter.startsWith(Subset.NAME, "West"))
			.orderBy(Subset.NAME);
		List<Subset> public_ = conformance.call("subsets", "list", "Region", "Region", SubsetScope.PUBLIC, query);
		assertThat(public_).extracting(Subset::name).containsExactly("West Coast");
		assertThat(tm1().last().key())
			.isEqualTo("GET " + HIERARCHY + "/Subsets?$select=Name&$filter=startswith(Name,'West')&$orderby=Name");

		tm1().json("{\"value\":[{\"Name\":\"Mine\"}]}");
		List<Subset> private_ = conformance.call("subsets", "list", "Region", "Region", SubsetScope.PRIVATE,
				ODataQuery.none());
		assertThat(private_).extracting(Subset::name).containsExactly("Mine");
		assertThat(tm1().last().key()).isEqualTo("GET " + HIERARCHY + "/PrivateSubsets");

		tm1().json("{\"value\":[]}");
		List<Subset> session = conformance.call("subsets", "list", "Region", "Region", SubsetScope.SESSION,
				ODataQuery.none().top(5));
		assertThat(session).isEmpty();
		assertThat(tm1().last().key()).isEqualTo("GET " + HIERARCHY + "/SessionSubsets?$top=5");
	}

	@Test
	void followsNextLinksAcrossPages() {
		tm1().json("{\"value\":[{\"Name\":\"a\"}],\"@odata.nextLink\":\"" + HIERARCHY + "/Subsets?$skiptoken=2\"}");
		tm1().json("{\"value\":[{\"Name\":\"b\"}]}");
		List<Subset> subsets = conformance.call("subsets", "list", "Region", "Region", SubsetScope.PUBLIC,
				ODataQuery.none().select("Name"));
		assertThat(subsets).extracting(Subset::name).containsExactly("a", "b");
		assertThat(tm1().requests()).extracting(ConformanceTm1.Recorded::target)
			.containsExactly(HIERARCHY + "/Subsets?$select=Name", HIERARCHY + "/Subsets?$skiptoken=2");
	}

	@Test
	void getsASubsetWithItsEtagAndElements() {
		tm1().json("{\"@odata.etag\":\"W/\\\"7\\\"\",\"Name\":\"Key Regions\",\"Expression\":null,"
				+ "\"Elements\":[{\"Name\":\"North\"},{\"Name\":\"South\"}]}");
		Subset subset = conformance.call("subsets", "get", "Region", "Region", SubsetScope.PUBLIC, "Key Regions",
				ODataQuery.none().select("Name", "Expression").expand("Elements", e -> e.select("Name")));
		assertThat(subset.etag()).isEqualTo("W/\"7\"");
		assertThat(subset.elements()).extracting(Element::name).containsExactly("North", "South");
		assertThat(tm1().last().target()).isEqualTo(
				HIERARCHY + "/Subsets('Key%20Regions')?$select=Name,Expression&$expand=Elements($select%3DName)");

		tm1().json("{\"Name\":\"Mine\"}");
		conformance.call("subsets", "get", "Region", "Region", SubsetScope.PRIVATE, "Mine", ODataQuery.none());
		assertThat(tm1().last().key()).isEqualTo("GET " + HIERARCHY + "/PrivateSubsets('Mine')");
	}

	@Test
	void quotesNamesWithApostrophesAndOtherCharacters() {
		tm1().json("{\"Name\":\"Joe's #1\"}");
		conformance.call("subsets", "get", "Bob's Region", "Alt & Co", SubsetScope.PUBLIC, "Joe's #1",
				ODataQuery.none());
		assertThat(tm1().last().target())
			.isEqualTo("Dimensions('Bob''s%20Region')/Hierarchies('Alt%20%26%20Co')/Subsets('Joe''s%20%231')");

		tm1().status(204);
		conformance.call("subsets", "addElements", "Bob's Region", "Bob's Region", SubsetScope.PUBLIC, "s",
				List.of("O'Neil", "50%"));
		assertThat(tm1().last().text()).isEqualTo("{\"Elements@odata.bind\":["
				+ "\"Dimensions('Bob''s%20Region')/Hierarchies('Bob''s%20Region')/Elements('O''Neil')\","
				+ "\"Dimensions('Bob''s%20Region')/Hierarchies('Bob''s%20Region')/Elements('50%25')\"]}");
	}

	@Test
	void encodesReservedAndNonAsciiCharacters() {
		tm1().json("{\"Name\":\"a/b?c+d=e\"}");
		conformance.call("subsets", "get", "Région", "東京", SubsetScope.PRIVATE, "a/b?c+d=e", ODataQuery.none());
		assertThat(tm1().last().target()).startsWith("Dimensions('R").contains("PrivateSubsets('a");

		tm1().status(204);
		conformance.call("subsets", "setElementAfter", "Région", "Région", SubsetScope.PUBLIC, "a/b", "Zürich",
				"#1 & ~2");
		assertThat(tm1().last().key()).startsWith("POST Dimensions('R");
	}

	@Test
	void aMissingSubsetIsNotFound() {
		tm1().error(404, "278", "'Nope' can not be found in collection of type 'Subset'.");
		Throwable error = conformance.fail("subsets", "get", "Region", "Region", SubsetScope.PUBLIC, "Nope",
				ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);

		tm1().error(404, "278", "'Nope' can not be found in collection of type 'Subset'.");
		Throwable elements = conformance.fail("subsets", "elements", "Region", "Region", SubsetScope.PUBLIC, "Nope",
				ODataQuery.none());
		assertThat(elements).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void createsStaticAndDynamicSubsets() {
		tm1().json(201, "{\"Name\":\"Key Regions\"}");
		Subset created = conformance.call("subsets", "create", "Region", "Region", SubsetScope.PUBLIC,
				SubsetCreate.builder()
					.name("Key Regions")
					.bindElements(ELEMENTS.key("North"), ELEMENTS.key("South"))
					.build());
		assertThat(created.name()).isEqualTo("Key Regions");
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key()).isEqualTo("POST " + HIERARCHY + "/Subsets");
		assertThat(post.header("Content-Type")).isEqualTo("application/json");
		assertThat(post.text()).isEqualTo("{\"Name\":\"Key Regions\",\"Elements@odata.bind\":[\"" + HIERARCHY
				+ "/Elements('North')\",\"" + HIERARCHY + "/Elements('South')\"]}");

		tm1().json(201, "{\"Name\":\"Leaves\",\"Expression\":\"{TM1SUBSETALL([Region])}\"}");
		Subset dynamic = conformance.call("subsets", "create", "Region", "Region", SubsetScope.PRIVATE,
				SubsetCreate.builder().name("Leaves").expression("{TM1SUBSETALL([Region])}").build());
		assertThat(dynamic.expression()).isEqualTo("{TM1SUBSETALL([Region])}");
		assertThat(tm1().last().key()).isEqualTo("POST " + HIERARCHY + "/PrivateSubsets");
		assertThat(tm1().last().text()).isEqualTo("{\"Name\":\"Leaves\",\"Expression\":\"{TM1SUBSETALL([Region])}\"}");
	}

	@Test
	void aSubsetThatExistsIsRefused() {
		tm1().error(400, "278", "Subset 'Leaves' already exists.");
		Throwable error = conformance.fail("subsets", "create", "Region", "Region", SubsetScope.PUBLIC,
				SubsetCreate.builder().name("Leaves").expression("{}").build());
		assertThat(error).isInstanceOfSatisfying(Tm1ApiException.class,
				e -> assertThat(e.kind()).isEqualTo(Tm1ErrorKind.ALREADY_EXISTS));
	}

	@Test
	void updatesWithAndWithoutAnEtag() {
		tm1().status(204);
		Object none = conformance.call("subsets", "update", "Region", "Region", SubsetScope.PUBLIC, "Leaves",
				SubsetPatch.builder().expression("{[Region].[North]}").build());
		assertThat(none).isNull();
		ConformanceTm1.Recorded patch = tm1().last();
		assertThat(patch.key()).isEqualTo("PATCH " + HIERARCHY + "/Subsets('Leaves')");
		assertThat(patch.text()).isEqualTo("{\"Expression\":\"{[Region].[North]}\"}");
		assertThat(patch.header("If-Match")).isNull();

		tm1().status(204);
		conformance.call("subsets", "update", "Region", "Region", SubsetScope.SESSION, "Leaves",
				SubsetPatch.builder().expression(null).build(), "W/\"4\"");
		assertThat(tm1().last().key()).isEqualTo("PATCH " + HIERARCHY + "/SessionSubsets('Leaves')");
		assertThat(tm1().last().text()).isEqualTo("{\"Expression\":null}");
		assertThat(tm1().last().header("If-Match")).isEqualTo("W/\"4\"");
	}

	@Test
	void aStaleEtagIsRefused() {
		tm1().error(412, "278", "stale");
		Throwable error = conformance.fail("subsets", "update", "Region", "Region", SubsetScope.PUBLIC, "Leaves",
				SubsetPatch.builder().expression("{}").build(), "W/\"0\"");
		assertThat(error).isInstanceOfSatisfying(Tm1PreconditionFailedException.class,
				e -> assertThat(e.kind()).isEqualTo(Tm1ErrorKind.PRECONDITION_FAILED));
	}

	@Test
	void refusesAnUpdateWithElementsSendingNothing() {
		Throwable set = conformance.fail("subsets", "update", "Region", "Region", SubsetScope.PUBLIC, "s",
				SubsetPatch.builder().elements(List.of(ElementCreate.builder().name("West").build())).build());
		assertThat(set).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("addElements");

		Throwable bound = conformance.fail("subsets", "update", "Region", "Region", SubsetScope.PUBLIC, "s",
				SubsetPatch.builder().bindElements(ELEMENTS.key("West")).build(), "W/\"1\"");
		assertThat(bound).isInstanceOf(IllegalArgumentException.class);
		assertThat(tm1().requests()).isEmpty();
	}

	@Test
	void addsClearsAndListsElements() {
		tm1().status(204);
		conformance.call("subsets", "addElements", "Region", "Region", SubsetScope.PUBLIC, "s",
				List.of("West", "East"));
		assertThat(tm1().last().key()).isEqualTo("PATCH " + HIERARCHY + "/Subsets('s')");
		assertThat(tm1().last().text()).isEqualTo("{\"Elements@odata.bind\":[\"" + HIERARCHY + "/Elements('West')\",\""
				+ HIERARCHY + "/Elements('East')\"]}");

		tm1().status(204);
		conformance.call("subsets", "addElements", "Region", "Region", SubsetScope.PRIVATE, "s", List.of());
		assertThat(tm1().last().text()).isEqualTo("{\"Elements@odata.bind\":[]}");

		tm1().status(204);
		conformance.call("subsets", "clearElements", "Region", "Region", SubsetScope.PRIVATE, "s");
		assertThat(tm1().last().key()).isEqualTo("DELETE " + HIERARCHY + "/PrivateSubsets('s')/Elements/$ref");

		tm1().json("{\"value\":[{\"Name\":\"West\",\"Type\":\"Numeric\"}]}");
		List<Element> elements = conformance.call("subsets", "elements", "Region", "Region", SubsetScope.PUBLIC, "s",
				ODataQuery.none().select("Name", "Type"));
		assertThat(elements).extracting(Element::name).containsExactly("West");
		assertThat(tm1().last().key()).isEqualTo("GET " + HIERARCHY + "/Subsets('s')/Elements?$select=Name,Type");
	}

	@Test
	void readsASubsetsHierarchy() {
		tm1().json("{\"Name\":\"Region\"}");
		Hierarchy hierarchy = conformance.call("subsets", "hierarchy", "Region", "Region", SubsetScope.SESSION, "s",
				ODataQuery.none().select("Name"));
		assertThat(hierarchy.name()).isEqualTo("Region");
		assertThat(tm1().last().key()).isEqualTo("GET " + HIERARCHY + "/SessionSubsets('s')/Hierarchy?$select=Name");
	}

	@Test
	void deletesASubsetOfEachScope() {
		for (SubsetScope scope : SubsetScope.values()) {
			tm1().status(204);
			conformance.call("subsets", "delete", "Region", "Region", scope, "Leaves");
			assertThat(tm1().last().key()).isEqualTo("DELETE " + HIERARCHY + "/" + scope.navigation() + "('Leaves')");
		}
	}

	@Test
	void savesACopy() {
		tm1().json("{\"Name\":\"Copy\",\"Expression\":\"{TM1SUBSETALL([Region])}\"}");
		Subset copy = conformance.call("subsets", "saveAs", "Region", "Region", SubsetScope.PUBLIC, "s", "Copy");
		assertThat(copy.name()).isEqualTo("Copy");
		assertThat(tm1().last().key()).isEqualTo("POST " + HIERARCHY + "/Subsets('s')/tm1.SaveAs");
		assertThat(tm1().last().text()).isEqualTo("{\"Name\":\"Copy\"}");

		tm1().json("{\"Name\":\"Frozen\"}");
		conformance.call("subsets", "saveAs", "Region", "Region", SubsetScope.PRIVATE, "s", "Frozen", true, false);
		assertThat(tm1().last().key()).isEqualTo("POST " + HIERARCHY + "/PrivateSubsets('s')/tm1.SaveAs");
		assertThat(tm1().last().text()).isEqualTo("{\"Name\":\"Frozen\",\"MakePrivate\":true,\"MakeStatic\":false}");

		tm1().json("{\"Name\":\"Frozen\"}");
		conformance.call("subsets", "saveAs", "Region", "Region", SubsetScope.PUBLIC, "s", "Frozen", false, true);
		assertThat(tm1().last().text()).isEqualTo("{\"Name\":\"Frozen\",\"MakePrivate\":false,\"MakeStatic\":true}");
	}

	@Test
	void setsElementsLastBeforeOrAfterOthers() {
		tm1().status(204);
		conformance.call("subsets", "setElement", "Region", "Region", SubsetScope.PUBLIC, "s", "West");
		assertThat(tm1().last().key()).isEqualTo("POST " + HIERARCHY + "/Subsets('s')/tm1.SetElement");
		assertThat(tm1().last().text()).isEqualTo("{\"Element@odata.bind\":\"" + HIERARCHY + "/Elements('West')\"}");

		tm1().status(204);
		conformance.call("subsets", "setElementBefore", "Region", "Region", SubsetScope.PUBLIC, "s", "West", "East's");
		assertThat(tm1().last().text()).isEqualTo("{\"Element@odata.bind\":\"" + HIERARCHY
				+ "/Elements('West')\",\"Before@odata.bind\":\"" + HIERARCHY + "/Elements('East''s')\"}");

		tm1().status(204);
		conformance.call("subsets", "setElementAfter", "Region", "Region", SubsetScope.PRIVATE, "s", "West", "East");
		assertThat(tm1().last().key()).isEqualTo("POST " + HIERARCHY + "/PrivateSubsets('s')/tm1.SetElement");
		assertThat(tm1().last().text()).isEqualTo("{\"Element@odata.bind\":\"" + HIERARCHY
				+ "/Elements('West')\",\"After@odata.bind\":\"" + HIERARCHY + "/Elements('East')\"}");
	}

	@Test
	void aMissingElementIsNotFound() {
		tm1().error(404, "278", "'Nope' can not be found in collection of type 'Element'.");
		Throwable error = conformance.fail("subsets", "setElement", "Region", "Region", SubsetScope.PUBLIC, "s",
				"Nope");
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void createsSessionSubsetsFromADefinitionOrAnotherSubset() {
		tm1().json("{\"Name\":\"yExesF4AAIABAAAQ\",\"Expression\":\"{[Region].[West]}\"}");
		Subset defined = conformance.call("subsets", "createSessionSubset", "Region", "Region",
				SubsetCreate.builder().name("Wanted").expression("{[Region].[West]}").build());
		assertThat(defined.name()).isEqualTo("yExesF4AAIABAAAQ");
		assertThat(tm1().last().key()).isEqualTo("POST " + HIERARCHY + "/tm1.CreateSessionSubset");
		assertThat(tm1().last().text())
			.isEqualTo("{\"Subset\":{\"Name\":\"Wanted\",\"Expression\":\"{[Region].[West]}\"}}");

		tm1().json("{\"Name\":\"yExesF4AAIABAAAR\"}");
		conformance.call("subsets", "createSessionSubset", "Region", "Region",
				SubsetCreate.builder().bindElements(ELEMENTS.key("West")).build());
		assertThat(tm1().last().text())
			.isEqualTo("{\"Subset\":{\"Elements@odata.bind\":[\"" + HIERARCHY + "/Elements('West')\"]}}");

		tm1().json("{\"Name\":\"yExesF4AAIABAAAS\"}");
		conformance.call("subsets", "createSessionSubsetFrom", "Region", "Region", SubsetScope.PRIVATE, "Mine");
		assertThat(tm1().last().key()).isEqualTo("POST " + HIERARCHY + "/tm1.CreateSessionSubset");
		assertThat(tm1().last().text())
			.isEqualTo("{\"Subset@odata.bind\":\"" + HIERARCHY + "/PrivateSubsets('Mine')\"}");
	}

	@Test
	void refusesAMissingScopeSendingNothing() {
		assertThat(conformance.fail("subsets", "delete", "Region", "Region", null, "s"))
			.isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

	@Test
	void refusesMissingBodiesSendingNothing() {
		assertThat(conformance.fail("subsets", "create", "Region", "Region", SubsetScope.PUBLIC, null))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("subsets", "update", "Region", "Region", SubsetScope.PUBLIC, "s", null))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("subsets", "addElements", "Region", "Region", SubsetScope.PUBLIC, "s", null))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("subsets", "addElements", "Region", "Region", SubsetScope.PUBLIC, "s",
				Arrays.asList("West", null)))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("subsets", "createSessionSubset", "Region", "Region", null))
			.isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

	@Test
	void refusesMissingNamesSendingNothing() {
		assertThat(conformance.fail("subsets", "delete", "Region", "Region", SubsetScope.PUBLIC, null))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("subsets", "delete", null, "Region", SubsetScope.PUBLIC, "s"))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("subsets", "setElementBefore", "Region", "Region", SubsetScope.PUBLIC, "s", "West",
				null))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("subsets", "setElementAfter", "Region", "Region", SubsetScope.PUBLIC, "s", null,
				"East"))
			.isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}

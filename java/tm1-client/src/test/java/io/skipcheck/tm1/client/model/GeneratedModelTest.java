// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import tools.jackson.databind.json.JsonMapper;

/** The generated model reads TM1's JSON; full round trips come with #67. */
class GeneratedModelTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void readsAnEntityWithItsEtagAndExpandedNavigationIgnoringWhatItDoesntKnow() {
		Cube cube = JSON.readValue("""
				{"@odata.context":"$metadata#Cubes/$entity","@odata.etag":"W/\\"abc\\"","Name":"Sales",
				 "LastDataUpdate":"2026-11-21T10:15:30Z","SomethingNewer":42,
				 "Dimensions":[{"Name":"Region"},{"Name":"Month"}]}
				""", Cube.class);
		assertThat(cube.etag()).isEqualTo("W/\"abc\"");
		assertThat(cube.name()).isEqualTo("Sales");
		assertThat(cube.lastDataUpdate()).isEqualTo(OffsetDateTime.of(2026, 11, 21, 10, 15, 30, 0, ZoneOffset.UTC));
		assertThat(cube.dimensions()).extracting(Dimension::name).containsExactly("Region", "Month");
		assertThat(cube.rules()).as("not selected").isNull();
	}

	@Test
	void readsEnumsAndFallsBackForAValueItDoesntKnow() {
		assertThat(JSON.readValue("{\"Type\":\"Consolidated\"}", Element.class).type())
			.isEqualTo(ElementType.CONSOLIDATED);
		assertThat(JSON.readValue("{\"Type\":\"SomethingNew\"}", Element.class).type()).isEqualTo(ElementType.UNKNOWN);
		assertThat(JSON.writeValueAsString(ElementType.NUMERIC)).isEqualTo("\"Numeric\"");
	}

	@Test
	void readsAHierarchyByOdataTypeWhereverItIsAndFallsBackForTypesItDoesntKnow() {
		assertThat(JSON.readValue("{\"@odata.type\":\"#ibm.tm1.api.v1.NativeView\",\"Name\":\"a\"}", View.class))
			.isInstanceOf(NativeView.class);
		assertThat(JSON.readValue("{\"Name\":\"b\",\"MDX\":\"SELECT\",\"@odata.type\":\"#tm1.MDXView\"}", View.class))
			.as("by alias, and with the type after other properties")
			.isEqualTo(new MDXView(null, "b", null, null, null, "SELECT", Map.of()));
		assertThat(
				JSON.readValue("{\"@odata.type\":\"#ibm.tm1.api.v1.FutureView\",\"Name\":\"c\",\"X\":1}", View.class))
			.as("a subtype a newer server added")
			.isInstanceOfSatisfying(View.Unknown.class, v -> {
				assertThat(v.name()).isEqualTo("c");
				assertThat(v.dynamicProperties()).as("views are open: what it doesn't know is kept")
					.containsOnlyKeys("X");
			});
		assertThat(JSON.readValue("{\"Name\":\"d\"}", View.class)).isInstanceOf(View.Unknown.class);
	}

	@Test
	void readsAValueOfAConcreteRootAsPlain() {
		assertThat(JSON.readValue("{\"Name\":\"m\"}", Member.class)).isInstanceOf(Member.Plain.class);
		assertThat(JSON.readValue("{\"@odata.type\":\"#ibm.tm1.api.v1.TupleMember\",\"Name\":\"t\"}", Member.class))
			.isInstanceOf(TupleMember.class);
	}

	@Test
	void sendsASubtypeWithItsOdataTypeAndWithoutNulls() {
		assertThat(JSON.writeValueAsString(new MDXView(null, "v", null, null, null, "SELECT", null)))
			.isEqualTo("{\"@odata.type\":\"#ibm.tm1.api.v1.MDXView\",\"Name\":\"v\",\"MDX\":\"SELECT\"}");
	}

	@Test
	void readsASubtypeAskedForByName() {
		assertThat(JSON.readValue("{\"@odata.type\":\"#ibm.tm1.api.v1.MDXView\",\"Name\":\"e\"}", MDXView.class).name())
			.isEqualTo("e");
		assertThat(JSON.readValue("{\"Name\":\"f\"}", MDXView.class).name()).as("without @odata.type").isEqualTo("f");
	}

	@Test
	void writesQueriesWithFieldConstantsAndEnumLiterals() {
		ODataQuery query = ODataQuery.none()
			.select(Element.NAME, Element.TYPE)
			.filter(Element.TYPE.eq(ElementType.CONSOLIDATED))
			.expand(Element.PARENTS, p -> p.select(Element.NAME))
			.orderBy(Element.NAME);
		assertThat(query).isEqualTo(ODataQuery.none()
			.select("Name", "Type")
			.filter(Filter.raw("(Type eq ibm.tm1.api.v1.ElementType'Consolidated')"))
			.expand("Parents", p -> p.select("Name"))
			.orderBy("Name"));
		assertThat(MDXView.MDX.path()).isEqualTo("MDX");
		assertThat(MDXView.NAME).as("inherited from View").isSameAs(View.NAME);
		assertThatIllegalStateException().isThrownBy(ElementType.UNKNOWN::odataLiteral);
	}

	@Test
	void sendsOnlyWhatAPatchSetsInOrderWithExplicitNullsAndBindings() {
		CubeCreate patch = CubeCreate.builder()
			.rules(null)
			.bindDimensions(ODataPath.of("Dimensions").key("Region"))
			.build();
		assertThat(JSON.writeValueAsString(patch))
			.isEqualTo("{\"Rules\":null,\"Dimensions@odata.bind\":[\"Dimensions('Region')\"]}");
		assertThat(patch)
			.isEqualTo(
					CubeCreate.builder().rules(null).bindDimensions(ODataPath.of("Dimensions").key("Region")).build())
			.isNotEqualTo(
					CubeCreate.builder().rules(null).bindDimensions(ODataPath.of("Dimensions").key("Product")).build());
	}

	@Test
	void nestsPatchesForDeepInsertsAndWritesEnumsAsTm1Does() {
		// A deep insert is a create, nested creates and all
		DimensionCreate dimension = DimensionCreate.builder()
			.name("Region")
			.hierarchies(List.of(HierarchyCreate.builder()
				.name("Region")
				.elements(List.of(ElementCreate.builder().name("North").type(ElementType.NUMERIC).build()))
				.build()))
			.build();
		assertThat(JSON.writeValueAsString(dimension)).isEqualTo(
				"{\"Name\":\"Region\",\"Hierarchies\":[{\"Name\":\"Region\",\"Elements\":[{\"Name\":\"North\",\"Type\":\"Numeric\"}]}]}");
	}

	@Test
	void sendsASubtypesPatchWithItsOdataTypeAndKeepsItsBuilderWhenChaining() {
		MDXViewPatch patch = MDXViewPatch.builder().mdx("SELECT").build();
		assertThat(JSON.writeValueAsString(patch))
			.isEqualTo("{\"@odata.type\":\"#ibm.tm1.api.v1.MDXView\",\"MDX\":\"SELECT\"}");
		ViewPatch asRoot = patch;
		assertThat(asRoot.json()).containsKey("@odata.type");
		// A subtype's create, nested in its root's place
		MDXViewCreate view = MDXViewCreate.builder().name("v").mdx("SELECT").build();
		ViewCreate created = view;
		assertThat(created.json()).containsEntry("@odata.type", "#ibm.tm1.api.v1.MDXView");
		assertThat(JSON.writeValueAsString(CubeCreate.builder()
			.bindDimensions(ODataPath.of("Dimensions").key("Region"))
			.views(List.of(view))
			.build())).contains("#ibm.tm1.api.v1.MDXView");
	}

	@Test
	void keepsAttributeValuesBeyondTheCaption() {
		String json = "{\"Caption\":\"North\",\"Region Code\":\"N\",\"}TM1_DefaultDisplayValue\":\"North\",\"Rank\":3}";
		Attributes attributes = JSON.readValue(json, Attributes.class);
		assertThat(attributes.caption()).isEqualTo("North");
		assertThat(attributes.dynamicProperties()).containsOnlyKeys("Region Code", "}TM1_DefaultDisplayValue", "Rank");
		assertThat(attributes.dynamicProperties().get("Rank").asInt()).isEqualTo(3);
		assertThat(JSON.readTree(JSON.writeValueAsString(attributes))).isEqualTo(JSON.readTree(json));
		assertThat(
				JSON.writeValueAsString(AttributesPatch.builder().caption("Nord").property("Region Code", "N").build()))
			.isEqualTo("{\"Caption\":\"Nord\",\"Region Code\":\"N\"}");
	}

}

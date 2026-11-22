package net.ajmyers.tm1.client.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.client.odata.Filter;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import tools.jackson.databind.json.JsonMapper;

/** The generated model reads TM1's JSON (plan §8.1); full round trips come with #67. */
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
			.isEqualTo(new MDXView(null, "b", null, null, null, "SELECT"));
		assertThat(
				JSON.readValue("{\"@odata.type\":\"#ibm.tm1.api.v1.FutureView\",\"Name\":\"c\",\"X\":1}", View.class))
			.as("a subtype a newer server added")
			.isEqualTo(new View.Unknown(null, "c", null, null, null));
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
		assertThat(JSON.writeValueAsString(new MDXView(null, "v", null, null, null, "SELECT")))
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
		CubePatch patch = CubePatch.builder()
			.name("Sales")
			.rules(null)
			.bindDimensions(ODataPath.of("Dimensions").key("Region"), ODataPath.of("Dimensions").key("Month"))
			.build();
		assertThat(JSON.writeValueAsString(patch)).isEqualTo(
				"{\"Name\":\"Sales\",\"Rules\":null,\"Dimensions@odata.bind\":[\"Dimensions('Region')\",\"Dimensions('Month')\"]}");
		assertThat(patch)
			.isEqualTo(CubePatch.builder()
				.name("Sales")
				.rules(null)
				.bindDimensions(ODataPath.of("Dimensions").key("Region"), ODataPath.of("Dimensions").key("Month"))
				.build())
			.isNotEqualTo(CubePatch.builder().name("Sales").build());
	}

	@Test
	void nestsPatchesForDeepInsertsAndWritesEnumsAsTm1Does() {
		DimensionPatch dimension = DimensionPatch.builder()
			.name("Region")
			.hierarchies(List.of(HierarchyPatch.builder()
				.name("Region")
				.elements(List.of(ElementPatch.builder().name("North").type(ElementType.NUMERIC).build()))
				.build()))
			.build();
		assertThat(JSON.writeValueAsString(dimension)).isEqualTo(
				"{\"Name\":\"Region\",\"Hierarchies\":[{\"Name\":\"Region\",\"Elements\":[{\"Name\":\"North\",\"Type\":\"Numeric\"}]}]}");
	}

	@Test
	void sendsASubtypesPatchWithItsOdataTypeAndKeepsItsBuilderWhenChaining() {
		MDXViewPatch view = MDXViewPatch.builder().name("v").mdx("SELECT").build();
		assertThat(JSON.writeValueAsString(view))
			.isEqualTo("{\"@odata.type\":\"#ibm.tm1.api.v1.MDXView\",\"Name\":\"v\",\"MDX\":\"SELECT\"}");
		ViewPatch asRoot = view;
		assertThat(asRoot.json()).containsKey("@odata.type");
		assertThat(JSON.writeValueAsString(CubePatch.builder().views(List.of(view)).build()))
			.contains("#ibm.tm1.api.v1.MDXView");
	}

}

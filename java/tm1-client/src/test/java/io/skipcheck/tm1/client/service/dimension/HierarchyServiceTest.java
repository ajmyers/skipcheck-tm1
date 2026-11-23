package io.skipcheck.tm1.client.service.dimension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1ConflictException;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.error.Tm1PreconditionFailedException;
import io.skipcheck.tm1.client.model.Dimension;
import io.skipcheck.tm1.client.model.EdgePatch;
import io.skipcheck.tm1.client.model.ElementPatch;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.model.Hierarchy;
import io.skipcheck.tm1.client.model.HierarchyPatch;
import io.skipcheck.tm1.client.model.Member;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * HierarchyService against a fake TM1: what each method sends, and how it reads TM1's
 * answer. Every call is recorded to
 * {@code spec/conformance/calls/HierarchyServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay.
 */
class HierarchyServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private static final String H = "Dimensions('Region')/Hierarchies('Region')";

	private static final String E = H + "/Elements";

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void listsAndGetsHierarchies() {
		tm1().json("{\"value\":[{\"Name\":\"Region\"},{\"Name\":\"Leaves\"}]}");
		List<Hierarchy> all = conformance.call("hierarchies", "list", "Region",
				ODataQuery.none().select(Hierarchy.NAME));
		assertThat(all).extracting(Hierarchy::name).containsExactly("Region", "Leaves");
		assertThat(tm1().last().key()).isEqualTo("GET Dimensions('Region')/Hierarchies?$select=Name");

		tm1().json("{\"@odata.etag\":\"W/\\\"2\\\"\",\"Name\":\"By Manager\",\"Visible\":true}");
		Hierarchy hierarchy = conformance.call("hierarchies", "get", "Region", "By Manager",
				ODataQuery.none().expand(Hierarchy.ELEMENTS, e -> e.select("Name").top(10)));
		assertThat(hierarchy.etag()).isEqualTo("W/\"2\"");
		assertThat(tm1().last().target())
			.isEqualTo("Dimensions('Region')/Hierarchies('By%20Manager')?$expand=Elements($select%3DName;$top%3D10)");
	}

	@Test
	void followsNextLinksAcrossPages() {
		tm1().json(
				"{\"value\":[{\"Name\":\"a\"}],\"@odata.nextLink\":\"Dimensions('Region')/Hierarchies?$skiptoken=1\"}");
		tm1().json("{\"value\":[{\"Name\":\"b\"}]}");
		List<Hierarchy> all = conformance.call("hierarchies", "list", "Region", ODataQuery.none());
		assertThat(all).extracting(Hierarchy::name).containsExactly("a", "b");
		assertThat(tm1().requests()).extracting(ConformanceTm1.Recorded::target)
			.containsExactly("Dimensions('Region')/Hierarchies", "Dimensions('Region')/Hierarchies?$skiptoken=1");
	}

	@Test
	void escapesSpecialCharactersInNames() {
		tm1().json("{\"Name\":\"x\"}");
		conformance.call("hierarchies", "get", "O'Brien & Co", "50% / #1", ODataQuery.none());
		assertThat(tm1().last().target())
			.isEqualTo("Dimensions('O''Brien%20%26%20Co')/Hierarchies('50%25%20%2F%20%231')");
	}

	@Test
	void aMissingHierarchyIsNotFound() {
		tm1().error(404, "278", "'Nope' can not be found in collection of type 'Hierarchy'.");
		Throwable error = conformance.fail("hierarchies", "get", "Region", "Nope", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void createsAHierarchyWithItsStructure() {
		tm1().json(201, "{\"Name\":\"Alt\"}");
		Hierarchy created = conformance.call("hierarchies", "create", "Region",
				HierarchyPatch.builder()
					.name("Alt")
					.elements(List.of(ElementPatch.builder().name("Total").type(ElementType.CONSOLIDATED).build(),
							ElementPatch.builder().name("North").type(ElementType.NUMERIC).build()))
					.edges(List.of(EdgePatch.builder().parentName("Total").componentName("North").weight(1.0).build()))
					.build());
		assertThat(created.name()).isEqualTo("Alt");
		assertThat(tm1().last().key()).isEqualTo("POST Dimensions('Region')/Hierarchies");
		assertThat(tm1().last().text()).isEqualTo("{\"Name\":\"Alt\",\"Elements\":[{\"Name\":\"Total\","
				+ "\"Type\":\"Consolidated\"},{\"Name\":\"North\",\"Type\":\"Numeric\"}],"
				+ "\"Edges\":[{\"ParentName\":\"Total\",\"ComponentName\":\"North\",\"Weight\":1.0}]}");
	}

	@Test
	void creatingAHierarchyThatExistsConflicts() {
		tm1().error(409, "278", "Hierarchy 'Region' already exists.");
		Throwable error = conformance.fail("hierarchies", "create", "Region",
				HierarchyPatch.builder().name("Region").build());
		assertThat(error).isInstanceOf(Tm1ConflictException.class);
	}

	@Test
	void updatesWithAndWithoutAnEtag() {
		tm1().status(204);
		Object none = conformance.call("hierarchies", "update", "Region", "Region",
				HierarchyPatch.builder().visible(false).build());
		assertThat(none).isNull();
		assertThat(tm1().last().key()).isEqualTo("PATCH " + H);
		assertThat(tm1().last().text()).isEqualTo("{\"Visible\":false}");
		assertThat(tm1().last().header("If-Match")).isNull();

		tm1().status(204);
		conformance.call("hierarchies", "update", "Region", "Region", HierarchyPatch.builder().uniqueName("x").build(),
				"W/\"2\"");
		assertThat(tm1().last().header("If-Match")).isEqualTo("W/\"2\"");
		assertThat(tm1().last().text()).isEqualTo("{\"UniqueName\":\"x\"}");
	}

	@Test
	void aStaleEtagIsRefused() {
		tm1().error(412, "278", "stale");
		Throwable error = conformance.fail("hierarchies", "update", "Region", "Region",
				HierarchyPatch.builder().build(), "W/\"0\"");
		assertThat(error).isInstanceOf(Tm1PreconditionFailedException.class);
		assertThat(tm1().last().text()).isEqualTo("{}");
	}

	@Test
	void anUpdateWithStructureIsRefused() {
		ODataPath elements = ODataPath.of("Dimensions").key("Region").nav("Hierarchies").key("Region").nav("Elements");
		List<HierarchyPatch> patches = List.of(
				HierarchyPatch.builder().elements(List.of(ElementPatch.builder().name("a").build())).build(),
				HierarchyPatch.builder().edges(List.of()).build(),
				HierarchyPatch.builder().bindElements(elements.key("a")).build(),
				HierarchyPatch.builder().bindEdges().build());
		for (HierarchyPatch patch : patches) {
			assertThat(conformance.fail("hierarchies", "update", "Region", "Region", patch))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("replaceStructure");
		}
		assertThat(conformance.fail("hierarchies", "update", "Region", "Region",
				HierarchyPatch.builder().edges(List.of()).build(), "W/\"1\""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(tm1().requests()).isEmpty();
	}

	@Test
	void replacesTheStructure() {
		tm1().status(204);
		Object none = conformance.call("hierarchies", "replaceStructure", "Version", "Version",
				List.of(ElementPatch.builder().name("All Versions").type(ElementType.CONSOLIDATED).build(),
						ElementPatch.builder().name("Actual").type(ElementType.NUMERIC).build()),
				List.of(EdgePatch.builder().parentName("All Versions").componentName("Actual").weight(1.0).build()));
		assertThat(none).isNull();
		assertThat(tm1().last().key()).isEqualTo("PATCH Dimensions('Version')/Hierarchies('Version')");
		assertThat(tm1().last().text()).isEqualTo(
				"{\"Elements\":[{\"Name\":\"All Versions\",\"Type\":\"Consolidated\"},{\"Name\":\"Actual\",\"Type\":"
						+ "\"Numeric\"}],\"Edges\":[{\"ParentName\":\"All Versions\",\"ComponentName\":\"Actual\","
						+ "\"Weight\":1.0}]}");

		tm1().status(204);
		conformance.call("hierarchies", "replaceStructure", "Version", "Version",
				List.of(ElementPatch.builder().name("Actual").type(ElementType.NUMERIC).build()), List.of());
		assertThat(tm1().last().text())
			.isEqualTo("{\"Elements\":[{\"Name\":\"Actual\",\"Type\":\"Numeric\"}],\"Edges\":[]}");
	}

	@Test
	void deletesAHierarchy() {
		tm1().status(204);
		Object none = conformance.call("hierarchies", "delete", "Region", "Leaves");
		assertThat(none).isNull();
		assertThat(tm1().last().key()).isEqualTo("DELETE Dimensions('Region')/Hierarchies('Leaves')");

		tm1().error(404, "278", "'Nope' can not be found in collection of type 'Hierarchy'.");
		assertThat(conformance.fail("hierarchies", "delete", "Region", "Nope"))
			.isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void navigatesToTheDefaultHierarchyAndTheDimension() {
		tm1().json("{\"Name\":\"Region\"}");
		Hierarchy hierarchy = conformance.call("hierarchies", "defaultHierarchy", "Region",
				ODataQuery.none().select(Hierarchy.NAME));
		assertThat(hierarchy.name()).isEqualTo("Region");
		assertThat(tm1().last().key()).isEqualTo("GET Dimensions('Region')/DefaultHierarchy?$select=Name");

		tm1().json("{\"Name\":\"Region\"}");
		Dimension dimension = conformance.call("hierarchies", "dimension", "Region", "Leaves", ODataQuery.none());
		assertThat(dimension.name()).isEqualTo("Region");
		assertThat(tm1().last().key()).isEqualTo("GET Dimensions('Region')/Hierarchies('Leaves')/Dimension");
	}

	@Test
	void readsTheAllAndDefaultMembers() {
		tm1().json("{\"Name\":\"Total\",\"UniqueName\":\"[Region].[Region].[Total]\"}");
		Member all = conformance.call("hierarchies", "allMember", "Region", "Region",
				ODataQuery.none().select(Member.NAME, Member.UNIQUE_NAME));
		assertThat(all.name()).isEqualTo("Total");
		assertThat(tm1().last().key()).isEqualTo("GET " + H + "/AllMember?$select=Name,UniqueName");

		tm1().json("{\"Name\":\"North\"}");
		Member member = conformance.call("hierarchies", "defaultMember", "Region", "Region", ODataQuery.none());
		assertThat(member.name()).isEqualTo("North");
		assertThat(tm1().last().key()).isEqualTo("GET " + H + "/DefaultMember");
	}

	@Test
	void thereIsNoAllMemberWithSeveralRoots() {
		// TM1 (11.8) answers 204 when the hierarchy has several roots
		tm1().status(204);
		Member none = conformance.call("hierarchies", "allMember", "Region", "Region", ODataQuery.none());
		assertThat(none).isNull();
	}

	@Test
	void listsTheRootMembers() {
		tm1().json("{\"value\":[{\"Name\":\"Total\"},{\"Name\":\"East\"}]}");
		List<Member> roots = conformance.call("hierarchies", "rootMembers", "Region", "Region",
				ODataQuery.none().select(Member.NAME));
		assertThat(roots).extracting(Member::name).containsExactly("Total", "East");
		assertThat(tm1().last().key()).isEqualTo("GET " + H + "/tm1.RootMembers()?$select=Name");
	}

	@Test
	void savesACopy() {
		tm1().json(201, "{\"Name\":\"Copy\"}");
		Hierarchy copy = conformance.call("hierarchies", "saveAs", "Region", "Region", "Copy");
		assertThat(copy.name()).isEqualTo("Copy");
		assertThat(tm1().last().key()).isEqualTo("POST " + H + "/tm1.SaveAs");
		assertThat(tm1().last().text()).isEqualTo("{\"Name\":\"Copy\"}");

		tm1().json(201, "{\"Name\":\"Copy\"}");
		conformance.call("hierarchies", "saveAs", "Region", "Region", "Copy", true, false);
		assertThat(tm1().last().text())
			.isEqualTo("{\"Name\":\"Copy\",\"Overwrite\":true,\"KeepExistingAttributes\":false}");

		tm1().error(409, "278", "Hierarchy 'Copy' already exists.");
		assertThat(conformance.fail("hierarchies", "saveAs", "Region", "Region", "Copy"))
			.isInstanceOf(Tm1ConflictException.class);
	}

	@Test
	void setsAnElementLastOrBeforeAnother() {
		tm1().status(204);
		Object none = conformance.call("hierarchies", "setElement", "Region", "Region", "West");
		assertThat(none).isNull();
		assertThat(tm1().last().key()).isEqualTo("POST " + H + "/tm1.SetElement");
		assertThat(tm1().last().text()).isEqualTo("{\"Element@odata.bind\":\"" + E + "('West')\"}");

		tm1().status(204);
		conformance.call("hierarchies", "setElement", "Region", "Region", "O'Neil", "Total");
		assertThat(tm1().last().text()).isEqualTo(
				"{\"Element@odata.bind\":\"" + E + "('O''Neil')\",\"Before@odata.bind\":\"" + E + "('Total')\"}");
	}

	@Test
	void refusesMissingNames() {
		assertThat(conformance.fail("hierarchies", "get", "Region", null, ODataQuery.none()))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("hierarchies", "list", null, ODataQuery.none()))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("hierarchies", "setElement", "Region", "Region", null))
			.isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

	@Test
	void theOverloadWithBeforeNeedsOne() {
		// Not recorded: the other clients have one method, where no before means last
		assertThatNullPointerException()
			.isThrownBy(() -> conformance.client().hierarchies().setElement("Region", "Region", "West", null).block())
			.withMessage("element");
	}

}

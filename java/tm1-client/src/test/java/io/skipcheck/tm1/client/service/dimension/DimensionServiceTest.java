// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.dimension;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1BadRequestException;
import io.skipcheck.tm1.client.error.Tm1ConflictException;
import io.skipcheck.tm1.client.error.Tm1ErrorKind;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.error.Tm1PreconditionFailedException;
import io.skipcheck.tm1.client.error.Tm1ServerException;
import io.skipcheck.tm1.client.model.Dimension;
import io.skipcheck.tm1.client.model.DimensionPatch;
import io.skipcheck.tm1.client.model.EdgePatch;
import io.skipcheck.tm1.client.model.ElementPatch;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.model.Hierarchy;
import io.skipcheck.tm1.client.model.HierarchyPatch;
import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * DimensionService against a fake TM1: what each method sends, and how it reads TM1's
 * answer. Every call is recorded to
 * {@code spec/conformance/calls/DimensionServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay.
 */
class DimensionServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void listsAllModelAndControlDimensions() {
		tm1().json("{\"value\":[{\"Name\":\"Region\"},{\"Name\":\"}Clients\"}]}");
		List<Dimension> all = conformance.call("dimensions", "list", ODataQuery.none().top(2));
		assertThat(all).extracting(Dimension::name).containsExactly("Region", "}Clients");
		assertThat(tm1().last().key()).isEqualTo("GET Dimensions?$top=2");

		tm1().json("{\"value\":[{\"Name\":\"Region\"}]}");
		List<Dimension> model = conformance.call("dimensions", "modelDimensions",
				ODataQuery.none()
					.select(Dimension.NAME)
					.filter(Filter.startsWith(Dimension.NAME, "Reg"))
					.orderByDescending(Dimension.NAME));
		assertThat(model).extracting(Dimension::name).containsExactly("Region");
		assertThat(tm1().last().target())
			.isEqualTo("ModelDimensions()?$select=Name&$filter=startswith(Name,'Reg')&$orderby=Name%20desc");

		tm1().json("{\"value\":[{\"Name\":\"}Clients\"}]}");
		List<Dimension> control = conformance.call("dimensions", "controlDimensions", ODataQuery.none());
		assertThat(control).extracting(Dimension::name).containsExactly("}Clients");
		assertThat(tm1().last().key()).isEqualTo("GET ControlDimensions()");
	}

	@Test
	void followsNextLinksAcrossPages() {
		tm1().json("{\"value\":[{\"Name\":\"a\"}],\"@odata.nextLink\":\"Dimensions?$skiptoken=2\"}");
		tm1().json("{\"value\":[{\"Name\":\"b\"}]}");
		List<Dimension> dimensions = conformance.call("dimensions", "list", ODataQuery.none().select("Name"));
		assertThat(dimensions).extracting(Dimension::name).containsExactly("a", "b");
		assertThat(tm1().requests()).extracting(ConformanceTm1.Recorded::target)
			.containsExactly("Dimensions?$select=Name", "Dimensions?$skiptoken=2");
	}

	@Test
	void anEmptyListIsEmpty() {
		tm1().json("{\"value\":[]}");
		List<Dimension> none = conformance.call("dimensions", "modelDimensions", ODataQuery.none());
		assertThat(none).isEmpty();
	}

	@Test
	void getsADimensionWithItsHierarchiesAndEtag() {
		tm1().json("{\"@odata.etag\":\"W/\\\"1\\\"\",\"Name\":\"Region\",\"Hierarchies\":[{\"Name\":\"Region\","
				+ "\"Elements\":[{\"Name\":\"North\",\"Type\":\"Numeric\"}]}]}");
		Dimension dimension = conformance.call("dimensions", "get", "Region",
				ODataQuery.none()
					.select(Dimension.NAME)
					.expand(Dimension.HIERARCHIES, h -> h.select(Hierarchy.NAME).expand(Hierarchy.ELEMENTS)));
		assertThat(dimension.etag()).isEqualTo("W/\"1\"");
		assertThat(dimension.hierarchies()).singleElement()
			.satisfies(h -> assertThat(h.elements()).extracting(e -> e.name()).containsExactly("North"));
		ConformanceTm1.Recorded get = tm1().last();
		assertThat(get.target())
			.isEqualTo("Dimensions('Region')?$select=Name&$expand=Hierarchies($select%3DName;$expand%3DElements)");
		assertThat(get.header("Accept")).isEqualTo("application/json");
	}

	@Test
	void escapesSpecialCharactersInAName() {
		for (String name : List.of("O'Brien", "Sales & Costs", "a/b", "50% off?", "#1", "Région", "}Cubes")) {
			tm1().json("{\"Name\":\"x\"}");
			conformance.call("dimensions", "get", name, ODataQuery.none());
		}
		assertThat(tm1().last().target()).isEqualTo("Dimensions('%7DCubes')");
	}

	@Test
	void aMissingDimensionIsNotFound() {
		tm1().error(404, "278", "'Nope' can not be found in collection of type 'Dimension'.");
		Throwable error = conformance.fail("dimensions", "get", "Nope", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);

		tm1().error(404, "278", "'Nope' can not be found in collection of type 'Dimension'.");
		assertThat(conformance.fail("dimensions", "delete", "Nope")).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void aServerErrorWithoutABodyIsTm1s() {
		tm1().status(500);
		Throwable error = conformance.fail("dimensions", "get", "Region", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1ServerException.class);
	}

	@Test
	void createsADimensionWithItsStructure() {
		tm1().json(201, "{\"Name\":\"Region\"}");
		Dimension created = conformance.call("dimensions", "create", DimensionPatch.builder()
			.name("Region")
			.hierarchies(List.of(HierarchyPatch.builder()
				.name("Region")
				.elements(List.of(ElementPatch.builder().name("Total").type(ElementType.CONSOLIDATED).build(),
						ElementPatch.builder().name("North").type(ElementType.NUMERIC).build()))
				.edges(List.of(EdgePatch.builder().parentName("Total").componentName("North").weight(1.0).build()))
				.build()))
			.build());
		assertThat(created.name()).isEqualTo("Region");
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key()).isEqualTo("POST Dimensions");
		assertThat(post.header("Content-Type")).isEqualTo("application/json");
		assertThat(post.text()).isEqualTo("{\"Name\":\"Region\",\"Hierarchies\":[{\"Name\":\"Region\",\"Elements\":["
				+ "{\"Name\":\"Total\",\"Type\":\"Consolidated\"},{\"Name\":\"North\",\"Type\":\"Numeric\"}],"
				+ "\"Edges\":[{\"ParentName\":\"Total\",\"ComponentName\":\"North\",\"Weight\":1.0}]}]}");
	}

	@Test
	void creatingADimensionThatExistsConflicts() {
		tm1().error(409, "278", "Dimension 'Region' already exists.");
		Throwable error = conformance.fail("dimensions", "create", DimensionPatch.builder().name("Region").build());
		assertThat(error).isInstanceOf(Tm1ConflictException.class);
	}

	@Test
	void aDimensionWithoutANameIsABadRequest() {
		tm1().error(400, "278", "Name is required.");
		Throwable error = conformance.fail("dimensions", "create", DimensionPatch.builder().build());
		assertThat(error).isInstanceOf(Tm1BadRequestException.class);
		assertThat(tm1().last().text()).isEqualTo("{}");
	}

	@Test
	void aNameTm1RefusesIsAnInvalidUniqueName() {
		tm1().error(400, "248", "Invalid name.");
		Throwable error = conformance.fail("dimensions", "create", DimensionPatch.builder().name("a]b").build());
		assertThat(error).isInstanceOfSatisfying(Tm1BadRequestException.class,
				e -> assertThat(e.kind()).isEqualTo(Tm1ErrorKind.INVALID_UNIQUE_NAME));
	}

	@Test
	void updatesWithAndWithoutAnEtag() {
		tm1().status(204);
		Object none = conformance.call("dimensions", "update", "Region",
				DimensionPatch.builder().uniqueName("[Region]").build());
		assertThat(none).isNull();
		ConformanceTm1.Recorded patch = tm1().last();
		assertThat(patch.key()).isEqualTo("PATCH Dimensions('Region')");
		assertThat(patch.text()).isEqualTo("{\"UniqueName\":\"[Region]\"}");
		assertThat(patch.header("If-Match")).isNull();

		tm1().status(204);
		conformance.call("dimensions", "update", "O'Brien",
				DimensionPatch.builder().allLeavesHierarchyName(null).build(), "W/\"1\"");
		assertThat(tm1().last().target()).isEqualTo("Dimensions('O''Brien')");
		assertThat(tm1().last().header("If-Match")).isEqualTo("W/\"1\"");
		assertThat(tm1().last().text()).isEqualTo("{\"AllLeavesHierarchyName\":null}");
	}

	@Test
	void aStaleEtagIsRefused() {
		tm1().error(412, "278", "stale");
		Throwable error = conformance.fail("dimensions", "update", "Region",
				DimensionPatch.builder().name("Region").build(), "W/\"0\"");
		assertThat(error).isInstanceOf(Tm1PreconditionFailedException.class);
	}

	@Test
	void deletesADimension() {
		tm1().status(204);
		Object none = conformance.call("dimensions", "delete", "Region");
		assertThat(none).isNull();
		assertThat(tm1().last().key()).isEqualTo("DELETE Dimensions('Region')");
	}

	@Test
	void savesACopy() {
		tm1().json(201, "{\"Name\":\"Copy\"}");
		Dimension copy = conformance.call("dimensions", "saveAs", "Region", "Copy");
		assertThat(copy.name()).isEqualTo("Copy");
		assertThat(tm1().last().key()).isEqualTo("POST Dimensions('Region')/tm1.SaveAs");
		assertThat(tm1().last().text()).isEqualTo("{\"Name\":\"Copy\"}");

		tm1().json(201, "{\"Name\":\"Copy\"}");
		conformance.call("dimensions", "saveAs", "Region", "Copy", true, false);
		assertThat(tm1().last().text())
			.isEqualTo("{\"Name\":\"Copy\",\"Overwrite\":true,\"KeepExistingAttributes\":false}");

		tm1().json(201, "{\"Name\":\"Copy\"}");
		conformance.call("dimensions", "saveAs", "Region", "Copy", false, true);
		assertThat(tm1().last().text())
			.isEqualTo("{\"Name\":\"Copy\",\"Overwrite\":false,\"KeepExistingAttributes\":true}");
	}

	@Test
	void savingOverAnExistingDimensionConflicts() {
		tm1().error(409, "278", "Dimension 'Copy' already exists.");
		Throwable error = conformance.fail("dimensions", "saveAs", "Region", "Copy");
		assertThat(error).isInstanceOf(Tm1ConflictException.class);
	}

	@Test
	void addsAnAllLeavesHierarchy() {
		tm1().json(201, "{\"Name\":\"Leaves\",\"Visible\":false}");
		Hierarchy leaves = conformance.call("dimensions", "addAllLeavesHierarchy", "Region");
		assertThat(leaves.name()).isEqualTo("Leaves");
		assertThat(tm1().last().key()).isEqualTo("POST Dimensions('Region')/tm1.AddAllLeavesHierarchy");
		assertThat(tm1().last().text()).isEqualTo("{}");

		tm1().json(201, "{\"Name\":\"All Leaves\",\"Visible\":true}");
		conformance.call("dimensions", "addAllLeavesHierarchy", "Region", "All Leaves", true);
		assertThat(tm1().last().text()).isEqualTo("{\"AllLeavesHierarchyName\":\"All Leaves\",\"Visible\":true}");
	}

	@Test
	void locksUnlocksReservesAndReleases() {
		for (String operation : List.of("lock", "unlock", "reserve", "release")) {
			tm1().status(204);
			Object none = conformance.call("dimensions", operation, "Region");
			assertThat(none).isNull();
			String action = Character.toUpperCase(operation.charAt(0)) + operation.substring(1);
			assertThat(tm1().last().key()).isEqualTo("POST Dimensions('Region')/tm1." + action);
			assertThat(tm1().last().text()).isEqualTo("{}");
		}
	}

	@Test
	void refusesAMissingName() {
		assertThat(conformance.fail("dimensions", "get", null, ODataQuery.none()))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("dimensions", "delete", (Object) null)).isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("dimensions", "lock", (Object) null)).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}

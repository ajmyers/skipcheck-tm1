// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.cube;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1ConflictException;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.error.Tm1PreconditionFailedException;
import io.skipcheck.tm1.client.model.Cube;
import io.skipcheck.tm1.client.model.MDXViewCreate;
import io.skipcheck.tm1.client.model.MDXViewPatch;
import io.skipcheck.tm1.client.model.NativeView;
import io.skipcheck.tm1.client.model.NativeViewCreate;
import io.skipcheck.tm1.client.model.NativeViewPatch;
import io.skipcheck.tm1.client.model.View;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * ViewService against a fake TM1: what each method sends, and how it reads TM1's answer.
 * Every call is recorded to {@code spec/conformance/calls/ViewServiceTest.json}, which
 * the Python, .NET and TypeScript clients replay.
 */
class ViewServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void listsAndGetsInEitherScope() {
		tm1().json("{\"value\":[{\"@odata.type\":\"#ibm.tm1.api.v1.MDXView\",\"Name\":\"Mdx\",\"MDX\":\"SELECT\"},"
				+ "{\"@odata.type\":\"#ibm.tm1.api.v1.NativeView\",\"Name\":\"Native\"}]}");
		List<View> views = conformance.call("views", "list", "Sales", ViewScope.PUBLIC, ODataQuery.none());
		assertThat(views).extracting(View::name).containsExactly("Mdx", "Native");
		assertThat(tm1().last().key()).isEqualTo("GET Cubes('Sales')/Views");

		tm1().json("{\"value\":[{\"Name\":\"Mine\"}]}");
		conformance.call("views", "list", "Joe's", ViewScope.PRIVATE, ODataQuery.none().select("Name").top(5));
		assertThat(tm1().last().target()).isEqualTo("Cubes('Joe''s')/PrivateViews?$select=Name&$top=5");

		tm1().json("{\"@odata.etag\":\"W/\\\"3\\\"\",\"Name\":\"By Region\"}");
		View view = conformance.call("views", "get", "Sales", ViewScope.PUBLIC, "By Region",
				ODataQuery.none().select("Name"));
		assertThat(view.name()).isEqualTo("By Region");
		assertThat(tm1().last().target()).isEqualTo("Cubes('Sales')/Views('By%20Region')?$select=Name");

		tm1().json("{\"Name\":\"Mine\"}");
		conformance.call("views", "get", "Sales", ViewScope.PRIVATE, "Mine", ODataQuery.none());
		assertThat(tm1().last().target()).isEqualTo("Cubes('Sales')/PrivateViews('Mine')");
	}

	@Test
	void aMissingViewIsNotFound() {
		tm1().error(404, "278", "'Gone' can not be found in collection of type 'View'.");
		Throwable error = conformance.fail("views", "get", "Sales", ViewScope.PUBLIC, "Gone", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void getsANativeViewByItsCast() {
		tm1().json("{\"@odata.type\":\"#ibm.tm1.api.v1.NativeView\",\"Name\":\"Native\",\"SuppressEmptyRows\":true,"
				+ "\"Columns\":[{\"Subset\":{\"Name\":\"Months\"}}]}");
		NativeView view = conformance.call("views", "getNative", "Sales", ViewScope.PUBLIC, "Native",
				ODataQuery.none().expand("Columns", c -> c.expand("Subset", s -> s.select("Name"))));
		assertThat(view.suppressEmptyRows()).isTrue();
		assertThat(tm1().last().target()).isEqualTo(
				"Cubes('Sales')/Views('Native')/ibm.tm1.api.v1.NativeView?$expand=Columns($expand%3DSubset($select%3DName))");

		tm1().json("{\"@odata.type\":\"#ibm.tm1.api.v1.NativeView\",\"Name\":\"Mine\"}");
		conformance.call("views", "getNative", "Sales", ViewScope.PRIVATE, "Joe's", ODataQuery.none());
		assertThat(tm1().last().target()).isEqualTo("Cubes('Sales')/PrivateViews('Joe''s')/ibm.tm1.api.v1.NativeView");
	}

	@Test
	void createsUpdatesAndDeletes() {
		tm1().json(201, "{\"Name\":\"Mdx\",\"MDX\":\"SELECT {} ON 0 FROM [Sales]\"}");
		View created = conformance.call("views", "create", "Sales", ViewScope.PUBLIC,
				MDXViewCreate.builder().name("Mdx").mdx("SELECT {} ON 0 FROM [Sales]").build());
		assertThat(created.name()).isEqualTo("Mdx");
		assertThat(tm1().last().key()).isEqualTo("POST Cubes('Sales')/Views");
		assertThat(tm1().last().text()).isEqualTo(
				"{\"@odata.type\":\"#ibm.tm1.api.v1.MDXView\",\"Name\":\"Mdx\",\"MDX\":\"SELECT {} ON 0 FROM [Sales]\"}");

		tm1().json(201, "{\"Name\":\"Native\"}");
		conformance.call("views", "create", "Sales", ViewScope.PRIVATE,
				NativeViewCreate.builder()
					.name("Native")
					.columns(List.of())
					.rows(List.of())
					.suppressEmptyRows(true)
					.build());
		assertThat(tm1().last().key()).isEqualTo("POST Cubes('Sales')/PrivateViews");

		tm1().status(204);
		conformance.call("views", "update", "Sales", ViewScope.PUBLIC, "Mdx",
				MDXViewPatch.builder().mdx("SELECT {[Month].[Jan]} ON 0 FROM [Sales]").build());
		assertThat(tm1().last().key()).isEqualTo("PATCH Cubes('Sales')/Views('Mdx')");
		assertThat(tm1().last().header("If-Match")).isNull();

		tm1().status(204);
		conformance.call("views", "update", "Sales", ViewScope.PRIVATE, "Native",
				NativeViewPatch.builder().suppressEmptyRows(false).build(), "W/\"3\"");
		assertThat(tm1().last().key()).isEqualTo("PATCH Cubes('Sales')/PrivateViews('Native')");
		assertThat(tm1().last().header("If-Match")).isEqualTo("W/\"3\"");
		assertThat(tm1().last().text())
			.isEqualTo("{\"@odata.type\":\"#ibm.tm1.api.v1.NativeView\",\"SuppressEmptyRows\":false}");

		tm1().status(204);
		conformance.call("views", "delete", "Sales", ViewScope.PUBLIC, "Joe's view");
		assertThat(tm1().last().key()).isEqualTo("DELETE Cubes('Sales')/Views('Joe''s%20view')");

		tm1().status(204);
		conformance.call("views", "delete", "Sales", ViewScope.PRIVATE, "Renamed");
		assertThat(tm1().last().key()).isEqualTo("DELETE Cubes('Sales')/PrivateViews('Renamed')");
	}

	@Test
	void aViewThatExistsIsAConflictAndAStaleEtagIsRefused() {
		tm1().error(409, "278", "'Mdx' already exists");
		Throwable conflict = conformance.fail("views", "create", "Sales", ViewScope.PUBLIC,
				MDXViewCreate.builder().name("Mdx").mdx("SELECT").build());
		assertThat(conflict).isInstanceOf(Tm1ConflictException.class);

		tm1().error(412, "278", "stale");
		Throwable stale = conformance.fail("views", "update", "Sales", ViewScope.PUBLIC, "Mdx",
				MDXViewPatch.builder().mdx("SELECT").build(), "W/\"0\"");
		assertThat(stale).isInstanceOf(Tm1PreconditionFailedException.class);
	}

	@Test
	void readsTheViewsCube() {
		tm1().json("{\"Name\":\"Sales\"}");
		Cube cube = conformance.call("views", "cube", "Sales", ViewScope.PUBLIC, "Mdx",
				ODataQuery.none().select("Name"));
		assertThat(cube.name()).isEqualTo("Sales");
		assertThat(tm1().last().target()).isEqualTo("Cubes('Sales')/Views('Mdx')/Cube?$select=Name");

		tm1().json("{\"Name\":\"Sales\"}");
		conformance.call("views", "cube", "Sales", ViewScope.PRIVATE, "Mine", ODataQuery.none());
		assertThat(tm1().last().target()).isEqualTo("Cubes('Sales')/PrivateViews('Mine')/Cube");
	}

	@Test
	void refusesAMissingViewName() {
		Throwable error = conformance.fail("views", "delete", "Sales", ViewScope.PUBLIC, null);
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}

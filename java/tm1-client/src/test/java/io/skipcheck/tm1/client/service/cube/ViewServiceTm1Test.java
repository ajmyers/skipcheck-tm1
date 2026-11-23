package io.skipcheck.tm1.client.service.cube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.error.Tm1PreconditionFailedException;
import io.skipcheck.tm1.client.model.CubePatch;
import io.skipcheck.tm1.client.model.DimensionPatch;
import io.skipcheck.tm1.client.model.ElementPatch;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.model.HierarchyPatch;
import io.skipcheck.tm1.client.model.MDXView;
import io.skipcheck.tm1.client.model.MDXViewPatch;
import io.skipcheck.tm1.client.model.NativeView;
import io.skipcheck.tm1.client.model.NativeViewPatch;
import io.skipcheck.tm1.client.model.SubsetPatch;
import io.skipcheck.tm1.client.model.View;
import io.skipcheck.tm1.client.model.ViewAxisSelectionPatch;
import io.skipcheck.tm1.client.model.ViewTitlePatch;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.dimension.SubsetScope;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

/**
 * ViewService, on a cube of Region, Measure and Year, each with a public subset "All".
 */
@RequiresTm1
class ViewServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	private static ViewService views;

	private String cube;

	private String region;

	private String measure;

	private String year;

	@BeforeAll
	static void connect(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
		views = tm1.views();
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	@BeforeEach
	void createCube() {
		cube = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		region = dimension(cube + "-region", "North", "South");
		measure = dimension(cube + "-measure", "Amount");
		year = dimension(cube + "-year", "2025", "2026");
		tm1.cubes()
			.create(CubePatch.builder().name(cube).bindDimensions(dim(region), dim(measure), dim(year)).build())
			.block(WAIT);
	}

	@AfterEach
	void deleteCube() {
		tm1.cubes().delete(cube).block(WAIT);
		for (String d : List.of(region, measure, year)) {
			tm1.dimensions().delete(d).block(WAIT);
		}
	}

	@Test
	void createsReadsUpdatesAndDeletesBothKindsOfView() {
		views.create(cube, ViewScope.PUBLIC, MDXViewPatch.builder().name("Mdx").mdx(mdx()).build()).block(WAIT);
		views.create(cube, ViewScope.PUBLIC, nativeView("Native")).block(WAIT);
		List<View> all = views.list(cube, ViewScope.PUBLIC, ODataQuery.none()).collectList().block(WAIT);
		assertThat(all).extracting(View::name).containsExactlyInAnyOrder("Mdx", "Native");
		assertThat(all).filteredOn(v -> v.name().equals("Mdx")).singleElement().isInstanceOf(MDXView.class);
		assertThat(all).filteredOn(v -> v.name().equals("Native")).singleElement().isInstanceOf(NativeView.class);

		String etag = views.get(cube, ViewScope.PUBLIC, "Mdx", ODataQuery.none()).block(WAIT).etag();
		String changed = "SELECT {[" + measure + "].[" + measure + "].[Amount]} ON 0 FROM [" + cube + "]";
		views.update(cube, ViewScope.PUBLIC, "Mdx", MDXViewPatch.builder().mdx(changed).build(), etag).block(WAIT);
		assertThat(views.get(cube, ViewScope.PUBLIC, "Mdx", ODataQuery.none()).block(WAIT))
			.isInstanceOfSatisfying(MDXView.class, v -> assertThat(v.mdx()).isEqualTo(changed));
		assertThatThrownBy(
				() -> views.update(cube, ViewScope.PUBLIC, "Mdx", MDXViewPatch.builder().mdx(mdx()).build(), etag)
					.block(WAIT))
			.isInstanceOf(Tm1PreconditionFailedException.class);
		assertThat(views.cube(cube, ViewScope.PUBLIC, "Mdx", ODataQuery.none()).block(WAIT).name()).isEqualTo(cube);

		views.delete(cube, ViewScope.PUBLIC, "Mdx").block(WAIT);
		assertThat(views.list(cube, ViewScope.PUBLIC, ODataQuery.none()).map(View::name).collectList().block(WAIT))
			.containsExactly("Native");
	}

	@Test
	void keepsPrivateViewsApart() {
		views.create(cube, ViewScope.PRIVATE, MDXViewPatch.builder().name("Mine").mdx(mdx()).build()).block(WAIT);
		assertThat(views.list(cube, ViewScope.PRIVATE, ODataQuery.none()).map(View::name).collectList().block(WAIT))
			.containsExactly("Mine");
		assertThat(views.list(cube, ViewScope.PUBLIC, ODataQuery.none()).collectList().block(WAIT)).isEmpty();
	}

	@Test
	void expandsANativeViewsAxesThroughItsType() {
		views.create(cube, ViewScope.PUBLIC, nativeView("Native")).block(WAIT);
		NativeView read = views
			.getNative(cube, ViewScope.PUBLIC, "Native",
					ODataQuery.none()
						.expand("Columns/Subset")
						.expand("Rows/Subset")
						.expand("Titles/Subset")
						.expand("Titles/Selected"))
			.block(WAIT);
		assertThat(read.columns()).singleElement().satisfies(c -> assertThat(c.subset().name()).isEqualTo("All"));
		assertThat(read.rows()).singleElement().satisfies(r -> assertThat(r.subset().uniqueName()).contains(region));
		assertThat(read.titles()).singleElement().satisfies(t -> assertThat(t.selected().name()).isEqualTo("2026"));
	}

	@Test
	void expandsNativeViewsAxesInAListWithMdxViews() {
		views.create(cube, ViewScope.PUBLIC, nativeView("Native")).block(WAIT);
		views.create(cube, ViewScope.PUBLIC, MDXViewPatch.builder().name("Mdx").mdx(mdx()).build()).block(WAIT);
		List<View> all = views.list(cube, ViewScope.PUBLIC, ODataQuery.none().expand("tm1.NativeView/Columns/Subset"))
			.collectList()
			.block(WAIT);
		assertThat(all).filteredOn(v -> v instanceof NativeView)
			.singleElement()
			.satisfies(v -> assertThat(((NativeView) v).columns()).singleElement()
				.satisfies(c -> assertThat(c.subset().name()).isEqualTo("All")));
		assertThat(all).filteredOn(v -> v instanceof MDXView).hasSize(1);
	}

	private NativeViewPatch nativeView(String name) {
		return NativeViewPatch.builder()
			.name(name)
			.columns(List.of(ViewAxisSelectionPatch.builder().bindSubset(subset(measure)).build()))
			.rows(List.of(ViewAxisSelectionPatch.builder().bindSubset(subset(region)).build()))
			.titles(List.of(ViewTitlePatch.builder()
				.bindSubset(subset(year))
				.bindSelected(dim(year).nav("Hierarchies").key(year).nav("Elements").key("2026"))
				.build()))
			.build();
	}

	private String mdx() {
		return "SELECT {[" + measure + "].[" + measure + "].Members} ON 0, {[" + region + "].[" + region
				+ "].Members} ON 1 FROM [" + cube + "]";
	}

	private static ODataPath subset(String dimension) {
		return dim(dimension).nav("Hierarchies").key(dimension).nav("Subsets").key("All");
	}

	private static ODataPath dim(String name) {
		return ODataPath.of("Dimensions").key(name);
	}

	private static String dimension(String name, String... elements) {
		tm1.dimensions()
			.create(DimensionPatch.builder()
				.name(name)
				.hierarchies(List.of(HierarchyPatch.builder()
					.name(name)
					.elements(Arrays.stream(elements)
						.map(e -> ElementPatch.builder().name(e).type(ElementType.NUMERIC).build())
						.toList())
					.build()))
				.build())
			.block(WAIT);
		tm1.subsets()
			.create(name, name, SubsetScope.PUBLIC,
					SubsetPatch.builder()
						.name("All")
						.expression("{TM1SUBSETALL([" + name + "].[" + name + "])}")
						.build())
			.block(WAIT);
		return name;
	}

}

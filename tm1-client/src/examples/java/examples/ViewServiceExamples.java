package examples;

import java.util.List;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.model.MDXView;
import net.ajmyers.tm1.client.model.MDXViewPatch;
import net.ajmyers.tm1.client.model.NativeView;
import net.ajmyers.tm1.client.model.NativeViewPatch;
import net.ajmyers.tm1.client.model.View;
import net.ajmyers.tm1.client.model.ViewAxisSelectionPatch;
import net.ajmyers.tm1.client.model.ViewTitlePatch;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.service.cube.ViewScope;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link net.ajmyers.tm1.client.service.cube.ViewService}, quoted in its
 * Javadoc.
 */
final class ViewServiceExamples {

	Mono<View> createNative(Tm1Client tm1) {
		// @start region = create-native
		// Months across, regions down, and the cube's other dimensions as titles: every
		// dimension of the cube must be on an axis. Each axis binds an existing subset.
		ODataPath dimensions = ODataPath.of("Dimensions");
		ODataPath months = dimensions.key("Month").nav("Hierarchies").key("Month").nav("Subsets").key("Quarters");
		ODataPath regions = dimensions.key("Region").nav("Hierarchies").key("Region").nav("Subsets").key("Leaves");
		ODataPath versions = dimensions.key("Version").nav("Hierarchies").key("Version");
		ODataPath measures = dimensions.key("Measure").nav("Hierarchies").key("Measure");
		return tm1.views()
			.create("Sales", ViewScope.PUBLIC,
					NativeViewPatch.builder()
						.name("Revenue by Region")
						.columns(List.of(ViewAxisSelectionPatch.builder().bindSubset(months).build()))
						.rows(List.of(ViewAxisSelectionPatch.builder().bindSubset(regions).build()))
						.titles(List.of(
								ViewTitlePatch.builder()
									.bindSubset(versions.nav("Subsets").key("All"))
									.bindSelected(versions.nav("Elements").key("Actual"))
									.build(),
								ViewTitlePatch.builder()
									.bindSubset(measures.nav("Subsets").key("All"))
									.bindSelected(measures.nav("Elements").key("Revenue"))
									.build()))
						.suppressEmptyRows(true)
						.build());
		// @end
	}

	Mono<View> createMdx(Tm1Client tm1) {
		// @start region = create-mdx
		// An MDX view is its query
		String mdx = "SELECT {[Month].[Month].[Jan], [Month].[Month].[Feb]} ON 0,"
				+ " {TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)} ON 1 FROM [Sales]"
				+ " WHERE ([Version].[Version].[Actual], [Measure].[Measure].[Revenue])";
		return tm1.views()
			.create("Sales", ViewScope.PRIVATE, MDXViewPatch.builder().name("My Revenue").mdx(mdx).build());
		// @end
	}

	Flux<String> list(Tm1Client tm1) {
		// @start region = list
		// Each view comes back as its own type
		return tm1.views().list("Sales", ViewScope.PUBLIC, ODataQuery.none()).map(view -> {
			if (view instanceof MDXView mdxView) {
				return view.name() + ": " + mdxView.mdx();
			}
			if (view instanceof NativeView) {
				return view.name() + ": native";
			}
			return view.name() + ": unknown kind";
		});
		// @end
	}

	Flux<String> readNative(Tm1Client tm1) {
		// @start region = read-native
		// A native view's axes are complex properties: expand them by path, through the
		// view's type
		return tm1.views()
			.getNative("Sales", ViewScope.PUBLIC, "Revenue by Region",
					ODataQuery.none().expand("Columns/Subset").expand("Rows/Subset"))
			.flatMapMany(view -> Flux.concat(Flux.fromIterable(view.columns()), Flux.fromIterable(view.rows())))
			.map(axis -> axis.subset().uniqueName());
		// @end
	}

	private ViewServiceExamples() {
	}

}

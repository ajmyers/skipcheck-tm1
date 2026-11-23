// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Examples;

/// <summary>Examples for <c>ViewService</c>, shown in the guide.</summary>
public static class ViewServiceExamples
{
    public static async Task<View> CreateNative(Tm1Client tm1)
    {
        // @start region = create-native
        // Months across, regions down, and the cube's other dimensions as titles: every
        // dimension of the cube must be on an axis. Each axis binds an existing subset.
        var dimensions = ODataPath.Of("Dimensions");
        var months = dimensions.Key("Month").Nav("Hierarchies").Key("Month").Nav("Subsets").Key("Quarters");
        var regions = dimensions.Key("Region").Nav("Hierarchies").Key("Region").Nav("Subsets").Key("Leaves");
        var versions = dimensions.Key("Version").Nav("Hierarchies").Key("Version");
        var measures = dimensions.Key("Measure").Nav("Hierarchies").Key("Measure");
        return await tm1.Views.CreateAsync(
            "Sales",
            ViewScope.Public,
            NativeViewPatch
                .Builder()
                .Name("Revenue by Region")
                .Columns([ViewAxisSelectionPatch.Builder().BindSubset(months).Build()])
                .Rows([ViewAxisSelectionPatch.Builder().BindSubset(regions).Build()])
                .Titles([
                    ViewTitlePatch.Builder().BindSubset(versions.Nav("Subsets").Key("All")).BindSelected(versions.Nav("Elements").Key("Actual")).Build(),
                    ViewTitlePatch.Builder().BindSubset(measures.Nav("Subsets").Key("All")).BindSelected(measures.Nav("Elements").Key("Revenue")).Build(),
                ])
                .SuppressEmptyRows(true)
                .Build()
        );
        // @end
    }

    public static async Task<View> CreateMdx(Tm1Client tm1)
    {
        // @start region = create-mdx
        // An MDX view is its query
        var mdx =
            "SELECT {[Month].[Month].[Jan], [Month].[Month].[Feb]} ON 0,"
            + " {TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)} ON 1 FROM [Sales]"
            + " WHERE ([Version].[Version].[Actual], [Measure].[Measure].[Revenue])";
        return await tm1.Views.CreateAsync("Sales", ViewScope.Private, MDXViewPatch.Builder().Name("My Revenue").Mdx(mdx).Build());
        // @end
    }

    public static IAsyncEnumerable<string> List(Tm1Client tm1)
    {
        // @start region = list
        // Each view comes back as its own type
        return tm1
            .Views.ListAsync("Sales", ViewScope.Public)
            .Select(view =>
                view switch
                {
                    MDXView mdxView => $"{view.Name}: {mdxView.Mdx}",
                    NativeView => $"{view.Name}: native",
                    _ => $"{view.Name}: unknown kind",
                }
            );
        // @end
    }

    public static async Task<List<string>> ReadNative(Tm1Client tm1)
    {
        // @start region = read-native
        // A native view's axes are complex properties: expand them by path, through the
        // view's type
        var view = await tm1.Views.GetNativeAsync(
            "Sales",
            ViewScope.Public,
            "Revenue by Region",
            ODataQuery.None().Expand("Columns/Subset").Expand("Rows/Subset")
        );
        return view.Columns!.Concat(view.Rows!).Select(axis => axis.Subset!.UniqueName!).ToList();
        // @end
    }
}

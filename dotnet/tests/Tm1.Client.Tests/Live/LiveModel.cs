// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>What the live tests build their cubes from: dimensions, subsets and cells, made through the
/// raw session (as the Java tests make what a later service owns), so these tests need only the cube and
/// data services.</summary>
internal static class LiveModel
{
    public static ODataPath Dim(string name) => ODataPath.Of("Dimensions").Key(name);

    public static ODataPath Subset(string dimension) => Dim(dimension).Nav("Hierarchies").Key(dimension).Nav("Subsets").Key("All");

    public static ODataPath Element(string dimension, string element) => Dim(dimension).Nav("Hierarchies").Key(dimension).Nav("Elements").Key(element);

    public static ElementPatch Element(string name, ElementType type) => ElementPatch.Builder().Name(name).Type(type).Build();

    public static EdgePatch Edge(string parent, string component) => EdgePatch.Builder().ParentName(parent).ComponentName(component).Weight(1.0).Build();

    /// <summary>A dimension of numeric elements.</summary>
    public static Task DimensionAsync(Tm1Client tm1, string name, params string[] elements) =>
        DimensionAsync(tm1, name, elements.Select(e => Element(e, ElementType.Numeric)).ToList(), []);

    public static Task DimensionAsync(Tm1Client tm1, string name, IReadOnlyList<ElementPatch> elements, IReadOnlyList<EdgePatch> edges)
    {
        var hierarchy = HierarchyPatch.Builder().Name(name).Elements(elements);
        if (edges.Count > 0)
        {
            hierarchy.Edges(edges);
        }
        return tm1.Session.ExecuteAsync(
            Tm1Request.Post(ODataPath.Of("Dimensions"), DimensionPatch.Builder().Name(name).Hierarchies([hierarchy.Build()]).Build())
        );
    }

    /// <summary>The public subset <c>All</c> of a dimension's own hierarchy.</summary>
    public static Task SubsetAllAsync(Tm1Client tm1, string dimension) =>
        tm1.Session.ExecuteAsync(
            Tm1Request.Post(
                Dim(dimension).Nav("Hierarchies").Key(dimension).Nav("Subsets"),
                SubsetPatch.Builder().Name("All").Expression($"{{TM1SUBSETALL([{dimension}].[{dimension}])}}").Build()
            )
        );

    public static async Task DeleteDimensionsAsync(Tm1Client tm1, params string[] names)
    {
        foreach (var name in names)
        {
            await Quietly(() => tm1.Session.ExecuteAsync(Tm1Request.Delete(Dim(name))));
        }
    }

    /// <summary>Writes a cell through the raw session's <c>tm1.Update</c>.</summary>
    public static Task WriteAsync(Tm1Client tm1, string cube, IEnumerable<ODataPath> tuple, object value) =>
        tm1.Session.ExecuteAsync(
            Tm1Request.Post(
                ODataPath.Of("Cubes").Key(cube).Action("tm1.Update"),
                new Dictionary<string, object?>
                {
                    ["Cells"] = new[] { new Dictionary<string, object?> { ["Tuple@odata.bind"] = tuple.Select(p => p.ToString()).ToList() } },
                    ["Value"] = value,
                }
            )
        );

    /// <summary>The value of the cell an MDX query of one cell selects; 0 for an empty one.</summary>
    public static async Task<double> NumberAsync(Tm1Client tm1, string mdx)
    {
        var value = await ValueAsync(tm1, mdx);
        return value is { ValueKind: System.Text.Json.JsonValueKind.Number } v ? v.GetDouble() : 0;
    }

    public static async Task<System.Text.Json.JsonElement?> ValueAsync(Tm1Client tm1, string mdx) =>
        await tm1.Cellsets.StreamAsync(mdx, ODataQuery.None().Select("Ordinal", "Value"), 10, (_, cells) => cells.Select(c => c.Value)).FirstOrDefaultAsync();

    public static async Task Quietly(Func<Task> work)
    {
        try
        {
            await work();
        }
        catch (Tm1Exception)
        {
            // Gone already
        }
    }
}

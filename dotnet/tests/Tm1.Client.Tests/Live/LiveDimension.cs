// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>What the dimension services' live tests build: element and edge patches, a dimension made for
/// one test, and the names of what a test reads.</summary>
internal static class LiveDimension
{
    public static ElementPatch Numeric(string name) => ElementPatch.Builder().Name(name).Type(ElementType.Numeric).Build();

    public static ElementPatch Consolidated(string name) => ElementPatch.Builder().Name(name).Type(ElementType.Consolidated).Build();

    public static ElementPatch Text(string name) => ElementPatch.Builder().Name(name).Type(ElementType.String).Build();

    public static EdgePatch Edge(string parent, string component) => EdgePatch.Builder().ParentName(parent).ComponentName(component).Weight(1.0).Build();

    public static DimensionPatch DimensionOf(string name, IReadOnlyList<ElementPatch> elements, IReadOnlyList<EdgePatch>? edges = null)
    {
        var hierarchy = HierarchyPatch.Builder().Name(name).Elements(elements);
        if (edges is { Count: > 0 })
        {
            hierarchy = hierarchy.Edges(edges);
        }
        return DimensionPatch.Builder().Name(name).Hierarchies([hierarchy.Build()]).Build();
    }

    public static List<string> Names(IEnumerable<Element> items) => items.Select(i => i.Name!).ToList();

    public static List<string> Names(IEnumerable<Member> items) => items.Select(i => i.Name!).ToList();

    public static List<string> Names(IEnumerable<Hierarchy> items) => items.Select(i => i.Name!).ToList();

    public static List<string> Names(IEnumerable<Subset> items) => items.Select(i => i.Name!).ToList();

    /// <summary>A client and a dimension made for one test, deleted afterwards.</summary>
    public sealed class Made : IAsyncDisposable
    {
        private Made(Tm1Client tm1, string name)
        {
            Tm1 = tm1;
            Name = name;
        }

        public Tm1Client Tm1 { get; }

        public string Name { get; }

        public static async Task<Made> Of(Func<string, DimensionPatch> patch)
        {
            var tm1 = Live.Client();
            var name = Live.Name();
            await tm1.Dimensions.CreateAsync(patch(name));
            return new Made(tm1, name);
        }

        public async ValueTask DisposeAsync()
        {
            try
            {
                await Tm1.Dimensions.DeleteAsync(Name);
            }
            catch (Tm1NotFoundException)
            {
                // The test deleted it
            }
            await Tm1.DisposeAsync();
        }
    }
}

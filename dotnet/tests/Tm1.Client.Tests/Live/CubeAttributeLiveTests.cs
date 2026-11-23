// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>Attributes of cubes and views against a real TM1: a port of the Java client's
/// CubeAttributeTm1Test.</summary>
public class CubeAttributeLiveTests
{
    private static string Label() => "Label " + Guid.NewGuid().ToString("N")[..4];

    private static AttributeDefinitionPatch StringAttribute(string name) => AttributeDefinitionPatch.Builder().Name(name).Type(AttributeType.String).Build();

    private static LocalizedAttributesPatch French(string attribute, string value) =>
        LocalizedAttributesPatch.Builder().LocaleID("fr").Attributes(AttributesPatch.Builder().Property(attribute, value).Build()).Build();

    private static async Task<string?> Value(Tm1Client tm1, AttributeOwner owner, string attribute)
    {
        var fr = await tm1.LocalizedAttributes.GetAsync(owner, "fr");
        return fr.Attributes?.DynamicProperties is { } p && p.TryGetValue(attribute, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;
    }

    private static async Task<List<string>> Names(Tm1Client tm1, AttributeSet set) =>
        (await tm1.Attributes.ListAsync(set).ToListAsync()).Select(a => a.Name!).ToList();

    [Tm1Fact]
    public async Task DefinesAndTranslatesAttributes()
    {
        await using var tm1 = Live.Client();
        var name = Live.Name();
        var dims = new[] { name + "-a", name + "-b" };
        var dimensions = ODataPath.Of("Dimensions");
        try
        {
            foreach (var dim in dims)
            {
                await tm1.Dimensions.CreateAsync(LiveDimension.DimensionOf(dim, [LiveDimension.Numeric("X")]));
                await tm1.Subsets.CreateAsync(
                    dim,
                    dim,
                    SubsetScope.Public,
                    SubsetPatch.Builder().Name("All").Expression($"{{TM1SUBSETALL([{dim}].[{dim}])}}").Build()
                );
            }
            await tm1.Cubes.CreateAsync(CubePatch.Builder().Name(name).BindDimensions(dimensions.Key(dims[0]), dimensions.Key(dims[1])).Build());
            await tm1.Views.CreateAsync(
                name,
                ViewScope.Public,
                MDXViewPatch.Builder().Name("V").Mdx($"SELECT {{[{dims[0]}].[{dims[0]}].Members}} ON 0 FROM [{name}]").Build()
            );
            ViewAxisSelectionPatch Axis(string dim) =>
                ViewAxisSelectionPatch.Builder().BindSubset(dimensions.Key(dim).Nav("Hierarchies").Key(dim).Nav("Subsets").Key("All")).Build();
            // A view attribute needs a public native view
            await tm1.Views.CreateAsync(name, ViewScope.Public, NativeViewPatch.Builder().Name("N").Columns([Axis(dims[0])]).Rows([Axis(dims[1])]).Build());

            foreach (
                var (set, owner) in new[]
                {
                    (AttributeSet.Cubes(), AttributeOwner.Cube(name)),
                    (AttributeSet.Views(name), AttributeOwner.View(name, ViewScope.Public, "V")),
                }
            )
            {
                var attribute = Label();
                await tm1.Attributes.CreateAsync(set, StringAttribute(attribute));
                try
                {
                    Assert.Contains(attribute, await Names(tm1, set));
                    await tm1.LocalizedAttributes.CreateAsync(owner, French(attribute, "Ventes"));
                    Assert.Equal("Ventes", await Value(tm1, owner, attribute));
                }
                finally
                {
                    await tm1.Attributes.DeleteAsync(set, attribute);
                }
            }
        }
        finally
        {
            try
            {
                await tm1.Cubes.DeleteAsync(name);
            }
            catch (Tm1NotFoundException)
            {
                // Never made
            }
            foreach (var dim in dims)
            {
                try
                {
                    await tm1.Dimensions.DeleteAsync(dim);
                }
                catch (Tm1NotFoundException)
                {
                    // Never made
                }
            }
        }
    }
}

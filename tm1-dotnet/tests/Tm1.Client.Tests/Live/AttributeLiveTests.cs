using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Errors;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Services;

namespace AjMyers.Tm1.Client.Tests.Live;

/// <summary>The attribute services against a real TM1: ports of the Java client's
/// AttributeServiceTm1Test, CubeAttributeTm1Test and ProcessAttributeTm1Test. Views, processes and
/// chores are set up with plain requests, so these need nothing but the attribute, dimension and
/// cube services.</summary>
public class AttributeLiveTests
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

    /// <summary>A dimension with North and South, and a public subset S.</summary>
    private static Task<DimensionLiveTests.Made> Dimension() => MadeWithSubset();

    private static async Task<DimensionLiveTests.Made> MadeWithSubset()
    {
        var made = await DimensionLiveTests.Made.Of(n => DimensionLiveTests.DimensionOf(n, [DimensionLiveTests.Numeric("North"), DimensionLiveTests.Numeric("South")]));
        var d = made.Name;
        await made.Tm1.Subsets.CreateAsync(d, d, SubsetScope.Public, SubsetPatch.Builder().Name("S").Expression($"{{[{d}].[{d}].[North]}}").Build());
        return made;
    }

    // AttributeServiceTm1Test

    [Tm1Fact]
    public async Task CreatesListsReadsAndDeletesAttributeDefinitions()
    {
        await using var made = await Dimension();
        var (tm1, d) = (made.Tm1, made.Name);
        foreach (var set in new[] { AttributeSet.Dimensions(), AttributeSet.Hierarchies(d), AttributeSet.Elements(d, d), AttributeSet.Subsets(d, d) })
        {
            var attribute = "Code " + Guid.NewGuid().ToString("N")[..4];
            Assert.Equal(AttributeType.String, (await tm1.Attributes.CreateAsync(set, StringAttribute(attribute))).Type);
            try
            {
                Assert.Contains(attribute, await Names(tm1, set));
                Assert.Equal(attribute, (await tm1.Attributes.GetAsync(set, attribute)).Name);
            }
            finally
            {
                await tm1.Attributes.DeleteAsync(set, attribute);
            }
            Assert.DoesNotContain(attribute, await Names(tm1, set));
        }
    }

    [Tm1Fact]
    public async Task TranslatesAttributeValues()
    {
        await using var made = await Dimension();
        var (tm1, d) = (made.Tm1, made.Name);
        var cases = new (AttributeSet Set, AttributeOwner Owner, AttributeOwner Reader)[]
        {
            (AttributeSet.Dimensions(), AttributeOwner.Dimension(d), AttributeOwner.Dimension(d)),
            (AttributeSet.Hierarchies(d), AttributeOwner.Hierarchy(d, d), AttributeOwner.Hierarchy(d, d)),
            (AttributeSet.Elements(d, d), AttributeOwner.Element(d, d, "North"), AttributeOwner.Element(d, d, "North")),
            // A member's translations are its element's, and read only
            (AttributeSet.Elements(d, d), AttributeOwner.Element(d, d, "North"), AttributeOwner.Member(d, d, "North")),
            (AttributeSet.Subsets(d, d), AttributeOwner.Subset(d, d, SubsetScope.Public, "S"), AttributeOwner.Subset(d, d, SubsetScope.Public, "S")),
        };
        foreach (var (set, owner, reader) in cases)
        {
            var attribute = Label();
            await tm1.Attributes.CreateAsync(set, StringAttribute(attribute));
            var localized = tm1.LocalizedAttributes;
            try
            {
                await localized.CreateAsync(owner, French(attribute, "Nord"));
                Assert.Contains("fr", (await localized.ListAsync(reader).ToListAsync()).Select(t => t.LocaleID));
                Assert.Equal("Nord", await Value(tm1, reader, attribute));
                var changed = French(attribute, "Le Nord");
                var fr = owner.Path.Nav("LocalizedAttributes").Key("fr");
                await Assert.ThrowsAsync<Tm1BadRequestException>(() => localized.CreateAsync(owner, changed)); // a locale can't be created twice
                await Assert.ThrowsAsync<Tm1BadRequestException>(() => tm1.Session.ExecuteAsync(Tm1Request.Patch(fr, changed))); // nor patched
                await Assert.ThrowsAsync<Tm1BadRequestException>(() => tm1.Session.ExecuteAsync(Tm1Request.Delete(fr))); // nor deleted
                Assert.Equal("Nord", await Value(tm1, reader, attribute));
            }
            finally
            {
                await tm1.Attributes.DeleteAsync(set, attribute);
            }
        }
    }

    [Tm1Fact]
    public async Task NeedsTheAttributeDefinedAndRefusesWritesThroughAMember()
    {
        await using var made = await Dimension();
        var (tm1, d) = (made.Tm1, made.Name);
        var caption = LocalizedAttributesPatch.Builder().LocaleID("fr").Attributes(AttributesPatch.Builder().Caption("Nord").Build()).Build();
        // Caption isn't an attribute until it's defined
        await Assert.ThrowsAsync<Tm1BadRequestException>(() => tm1.LocalizedAttributes.CreateAsync(AttributeOwner.Element(d, d, "North"), caption));
        // POST isn't supported on a member's
        await Assert.ThrowsAsync<Tm1BadRequestException>(() => tm1.LocalizedAttributes.CreateAsync(AttributeOwner.Member(d, d, "North"), LocalizedAttributesPatch.Builder().LocaleID("fr").Build()));
    }

    // CubeAttributeTm1Test

    [Tm1Fact]
    public async Task DefinesAndTranslatesCubeAndViewAttributes()
    {
        await using var tm1 = Live.Client();
        var name = Live.Name();
        var dims = new[] { name + "-a", name + "-b" };
        var dimensions = ODataPath.Of("Dimensions");
        try
        {
            foreach (var dim in dims)
            {
                await tm1.Dimensions.CreateAsync(DimensionLiveTests.DimensionOf(dim, [DimensionLiveTests.Numeric("X")]));
                await tm1.Subsets.CreateAsync(dim, dim, SubsetScope.Public, SubsetPatch.Builder().Name("All").Expression($"{{TM1SUBSETALL([{dim}].[{dim}])}}").Build());
            }
            await tm1.Cubes.CreateAsync(CubePatch.Builder().Name(name).BindDimensions(dimensions.Key(dims[0]), dimensions.Key(dims[1])).Build());
            var views = ODataPath.Of("Cubes").Key(name).Nav("Views");
            await tm1.Session.ExecuteAsync(Tm1Request.Post(views, new Dictionary<string, object?>
            {
                ["@odata.type"] = "#ibm.tm1.api.v1.MDXView",
                ["Name"] = "V",
                ["MDX"] = $"SELECT {{[{dims[0]}].[{dims[0]}].Members}} ON 0 FROM [{name}]",
            }));
            Dictionary<string, object?> Axis(string dim) => new() { ["Subset@odata.bind"] = dimensions.Key(dim).Nav("Hierarchies").Key(dim).Nav("Subsets").Key("All").ToString() };
            // A view attribute needs a public native view
            await tm1.Session.ExecuteAsync(Tm1Request.Post(views, new Dictionary<string, object?>
            {
                ["@odata.type"] = "#ibm.tm1.api.v1.NativeView",
                ["Name"] = "N",
                ["Columns"] = new[] { Axis(dims[0]) },
                ["Rows"] = new[] { Axis(dims[1]) },
            }));

            foreach (var (set, owner) in new[] { (AttributeSet.Cubes(), AttributeOwner.Cube(name)), (AttributeSet.Views(name), AttributeOwner.View(name, ViewScope.Public, "V")) })
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

    // ProcessAttributeTm1Test

    [Tm1Fact]
    public async Task DefinesAndTranslatesProcessAndChoreAttributes()
    {
        await using var tm1 = Live.Client();
        var process = Live.Name();
        var chore = process + "-chore";
        await tm1.Session.ExecuteAsync(Tm1Request.Post(ODataPath.Of("Processes"), new Dictionary<string, object?> { ["Name"] = process, ["PrologProcedure"] = "nX = 1;\n" }));
        try
        {
            await tm1.Session.ExecuteAsync(Tm1Request.Post(ODataPath.Of("Chores"), new Dictionary<string, object?>
            {
                ["Name"] = chore,
                ["StartTime"] = "2026-01-01T02:00:00Z",
                ["DSTSensitive"] = false,
                ["Active"] = false,
                ["ExecutionMode"] = "SingleCommit",
                ["Frequency"] = "P1D",
                ["Tasks"] = new[] { new Dictionary<string, object?> { ["Process@odata.bind"] = ODataPath.Of("Processes").Key(process).ToString() } },
            }));
            try
            {
                foreach (var (set, owner) in new[] { (AttributeSet.Processes(), AttributeOwner.Process(process)), (AttributeSet.Chores(), AttributeOwner.Chore(chore)) })
                {
                    var attribute = Label();
                    await tm1.Attributes.CreateAsync(set, StringAttribute(attribute));
                    try
                    {
                        Assert.Contains(attribute, await Names(tm1, set));
                        await tm1.LocalizedAttributes.CreateAsync(owner, French(attribute, "Charger"));
                        Assert.Equal("Charger", await Value(tm1, owner, attribute));
                    }
                    finally
                    {
                        await tm1.Attributes.DeleteAsync(set, attribute);
                    }
                }
            }
            finally
            {
                await tm1.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Chores").Key(chore)));
            }
        }
        finally
        {
            await tm1.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Processes").Key(process)));
        }
    }
}

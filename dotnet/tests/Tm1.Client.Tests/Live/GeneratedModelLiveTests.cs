// SPDX-License-Identifier: Apache-2.0

using System;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Testcontainers;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>The generated model, read straight from a real TM1 through the session: a port of the Java
/// client's GeneratedModelTm1Test.</summary>
public class GeneratedModelLiveTests
{
    [Tm1Fact]
    public async Task ReadsTheServerAndStreamsDimensionsIntoGeneratedTypes()
    {
        await using var tm1 = Live.Client();
        var session = tm1.Session;
        var server = await session.ExecuteAsync(Tm1Request.Get<Server>(ODataPath.Of("Server")));
        // The version the container read as it started; of a server already running, the session's
        var version = string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("TM1_URL"))
            ? (await Tm1ServerContainer.SharedAsync()).Tm1Version.ToString()
            : (await session.VersionAsync()).ToString();
        Assert.Equal(version, server.ProductVersion);
        Assert.Null(server.ETag); // TM1 sends no ETag for the Server singleton

        var dimensions = await session
            .StreamAsync(Tm1Request.List<Dimension>(ODataPath.Of("Dimensions"), ODataQuery.None().Expand("Hierarchies")))
            .ToListAsync();
        Assert.NotEmpty(dimensions);
        Assert.All(dimensions, d =>
        {
            Assert.False(string.IsNullOrWhiteSpace(d.Name));
            Assert.False(string.IsNullOrWhiteSpace(d.ETag));
            Assert.NotEmpty(d.Hierarchies!);
        });
    }

    [Tm1Fact]
    public async Task CreatesAViewAsItsSubtypeAndReadsItBackThroughTheHierarchy()
    {
        await using var tm1 = Live.Client();
        var session = tm1.Session;
        var name = Live.Name();
        var views = ODataPath.Of("Cubes").Key("}ClientGroups").Nav("Views");
        try
        {
            // Java posts its MDXView record; here a model is read only, and its patch is what's written
            var created = await session.ExecuteAsync(
                Tm1Request.Post<View>(views, MDXViewPatch.Builder().Name(name).Mdx("SELECT {[}Groups].[}Groups].MEMBERS} ON 0 FROM [}ClientGroups]").Build())
            );
            Assert.Contains("}Groups", Assert.IsType<MDXView>(created).Mdx);

            var all = await session.StreamAsync(Tm1Request.List<View>(views)).ToListAsync();
            Assert.IsType<MDXView>(Assert.Single(all, v => v.Name == name));

            var members = await session
                .StreamAsync(Tm1Request.List<Member>(ODataPath.Of("Dimensions").Key("}Groups").Nav("Hierarchies").Key("}Groups").Nav("Members")))
                .ToListAsync();
            Assert.NotEmpty(members);
            Assert.All(members, m => Assert.IsType<Member>(m)); // Java's Member.Plain: no subtype
        }
        finally
        {
            await LiveModel.Quietly(() => session.ExecuteAsync(Tm1Request.Delete(views.Key(name))));
        }
    }

    [Tm1Fact]
    public async Task QueriesWithFieldConstantsAndEnumLiterals()
    {
        await using var tm1 = Live.Client();
        var session = tm1.Session;
        var elements = ODataPath.Of("Dimensions").Key("}Groups").Nav("Hierarchies").Key("}Groups").Nav("Elements");
        var all = await session
            .StreamAsync(Tm1Request.List<Element>(elements, ODataQuery.None().Select(Element.Fields.Name, Element.Fields.Type).OrderBy(Element.Fields.Name)))
            .ToListAsync();
        Assert.NotEmpty(all);
        // TM1 orders names ignoring case
        Assert.Equal(all.Select(e => e.Name).Order(StringComparer.OrdinalIgnoreCase), all.Select(e => e.Name));
        var type = all[0].Type;
        Assert.NotNull(type);
        Assert.NotEqual(ElementType.Unknown, type);

        var filtered = await session.StreamAsync(Tm1Request.List<Element>(elements, ODataQuery.None().Filter(Element.Fields.Type.Eq(type)))).ToListAsync();
        Assert.Equal(all.Count(e => e.Type == type), filtered.Count);
        Assert.All(filtered, e => Assert.Equal(type, e.Type));
    }

    [Tm1Fact]
    public async Task CreatesADimensionAndACubeFromPatchesThenPatchesAndClearsItsRules()
    {
        await using var tm1 = Live.Client();
        var session = tm1.Session;
        var name = Live.Name();
        var dimension = ODataPath.Of("Dimensions").Key(name);
        var cube = ODataPath.Of("Cubes").Key(name);
        try
        {
            await session.ExecuteAsync(
                Tm1Request.Post<Dimension>(
                    ODataPath.Of("Dimensions"),
                    DimensionPatch
                        .Builder()
                        .Name(name)
                        .Hierarchies([
                            HierarchyPatch
                                .Builder()
                                .Name(name)
                                .Elements([
                                    ElementPatch.Builder().Name("North").Type(ElementType.Numeric).Build(),
                                    ElementPatch.Builder().Name("South").Type(ElementType.Numeric).Build(),
                                ])
                                .Build(),
                        ])
                        .Build()
                )
            );
            await session.ExecuteAsync(
                Tm1Request.Post<Cube>(
                    ODataPath.Of("Cubes"),
                    CubePatch.Builder().Name(name).BindDimensions(dimension, ODataPath.Of("Dimensions").Key("}Groups")).Build()
                )
            );

            var created = await session.ExecuteAsync(
                Tm1Request.Get<Cube>(cube, ODataQuery.None().Expand(Cube.Fields.Dimensions, d => d.Select(Dimension.Fields.Name)))
            );
            // TM1 lists its virtual Sandboxes dimension first
            Assert.Equal(["Sandboxes", name, "}Groups"], created.Dimensions!.Select(d => d.Name));
            var elements = await session.StreamAsync(Tm1Request.List<Element>(dimension.Nav("Hierarchies").Key(name).Nav("Elements"))).ToListAsync();
            Assert.Equal([("North", ElementType.Numeric), ("South", ElementType.Numeric)], elements.Select(e => (e.Name!, e.Type!.Value)).Order());

            await session.ExecuteAsync(Tm1Request.Patch(cube, CubePatch.Builder().Rules("SKIPCHECK;").Build()));
            Assert.Contains("SKIPCHECK", await Rules(session, cube));
            await session.ExecuteAsync(Tm1Request.Patch(cube, CubePatch.Builder().Rules(null).Build()));
            Assert.True(string.IsNullOrEmpty(await Rules(session, cube)), "an explicit null clears them");
        }
        finally
        {
            await LiveModel.Quietly(() => session.ExecuteAsync(Tm1Request.Delete(cube)));
            await LiveModel.Quietly(() => session.ExecuteAsync(Tm1Request.Delete(dimension)));
        }
    }

    private static async Task<string?> Rules(Session.Tm1Session session, ODataPath cube) =>
        (await session.ExecuteAsync(Tm1Request.Get<Cube>(cube, ODataQuery.None().Select(Cube.Fields.Rules)))).Rules;

    [Tm1Fact]
    public async Task TheServerHasEveryOperationInTheCatalog()
    {
        await using var tm1 = Live.Client();
        var capabilities = await tm1.Session.CapabilitiesAsync();
        Assert.All(Tm1Operations.All, op => Assert.True(capabilities.Supports(op.Capability()), op.Endpoint()));
    }
}

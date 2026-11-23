// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>AnnotationService against a fake TM1: each method sends what the Java client's does.</summary>
public class AnnotationServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    private string Sent => Uri.UnescapeDataString($"{_tm1.Last.Method} {_tm1.Last.Target}");

    [Fact]
    public async Task AnnotatesACellAsWorkspaceDoes()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => new Dictionary<string, object?>
        {
            ["ID"] = "C}ClientGroups~B.B~7uA",
            ["Text"] = "first note",
            ["commentLocation"] = "Admin,ADMIN",
        };
        var cell = new Dictionary<string, string> { ["}Clients"] = "Admin", ["}Groups"] = "ADMIN" };
        var made = await tm1.Annotations.CreateAsync("}ClientGroups", cell, "first note");
        Assert.Equal("C}ClientGroups~B.B~7uA", made.Id);
        Assert.Equal("Admin,ADMIN", made.DynamicProperties!["commentLocation"].GetString());
        Assert.Equal("POST Annotations", Sent);
        var body = _tm1.Last.Json;
        Assert.Equal("first note", body.GetProperty("Text").GetString());
        var context = Assert.Single(body.GetProperty("ApplicationContext").EnumerateArray());
        Assert.Equal("ApplicationContextFacets('}Cubes')", Uri.UnescapeDataString(context.GetProperty("Facet@odata.bind").GetString()!));
        Assert.Equal("}ClientGroups", context.GetProperty("Value").GetString());
        Assert.Equal(
            ["Dimensions('}Clients')/Hierarchies('}Clients')/Members('Admin')", "Dimensions('}Groups')/Hierarchies('}Groups')/Members('ADMIN')"],
            body.GetProperty("DimensionalContext@odata.bind").EnumerateArray().Select(e => Uri.UnescapeDataString(e.GetString()!))
        );
        Assert.Equal("}ClientGroups", body.GetProperty("objectName").GetString());
        Assert.Equal("first note", body.GetProperty("commentValue").GetString());
        Assert.Equal("ANNOTATION", body.GetProperty("commentType").GetString());
        Assert.Equal("Admin,ADMIN", body.GetProperty("commentLocation").GetString());

        await tm1.Annotations.CreateAsync(AnnotationPatch.Builder().Text("by hand").Build());
        Assert.Equal(("POST Annotations", """{"Text":"by hand"}"""), (Sent, _tm1.Last.Text));
    }

    [Fact]
    public async Task ReadsChangesAndDeletes()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => FakeTm1.Collection();
        await tm1.Annotations.ListAsync(ODataQuery.None().Top(1)).ToListAsync();
        Assert.Equal("GET Annotations?$top=1", Sent);
        await tm1.Annotations.ApplicationContextAsync("a1", ODataQuery.None().Expand("Facet")).ToListAsync();
        Assert.Equal("GET Annotations('a1')/ApplicationContext?$expand=Facet", Sent);
        await tm1.Annotations.DimensionalContextAsync("a1").ToListAsync();
        Assert.Equal("GET Annotations('a1')/DimensionalContext", Sent);
        await tm1.Annotations.AttachmentsAsync("a1").ToListAsync();
        Assert.Equal("GET Annotations('a1')/Attachments", Sent);
        await tm1.Annotations.CubeAnnotationsAsync("Sales").ToListAsync();
        Assert.Equal("GET Cubes('Sales')/Annotations", Sent);
        await tm1.Annotations.CellAnnotationsAsync("cs1", 3).ToListAsync();
        Assert.Equal("GET Cellsets('cs1')/Cells(3)/Annotations", Sent);
        await tm1.Annotations.FacetsAsync(ODataQuery.None().Expand("Values")).ToListAsync();
        Assert.Equal("GET ApplicationContextFacets?$expand=Values", Sent);
        await tm1.Annotations.FacetValuesAsync("}Cubes").ToListAsync();
        Assert.Equal("GET ApplicationContextFacets('}Cubes')/Values", Sent);

        _tm1.Respond = _ => new
        {
            Name = "}Cubes",
            ID = "a1",
            Text = "note",
        };
        Assert.Equal("}Cubes", (await tm1.Annotations.FacetAsync("}Cubes", "Sales")).Name);
        Assert.Equal("GET ApplicationContextFacets('}Cubes')/Values('Sales')/Facet", Sent);
        Assert.Equal("note", (await tm1.Annotations.GetAsync("a1")).Text);
        Assert.Equal("GET Annotations('a1')", Sent);

        _tm1.Respond = _ => null;
        await tm1.Annotations.UpdateAsync("a1", AnnotationPatch.Builder().Text("second").Property("commentValue", "second").Build());
        Assert.Equal(("PATCH Annotations('a1')", """{"Text":"second","commentValue":"second"}"""), (Sent, _tm1.Last.Text));
        await tm1.Annotations.DeleteAsync("a1");
        Assert.Equal("DELETE Annotations('a1')", Sent);
        await tm1.Annotations.DeleteAnnotationArtifactsAsync();
        Assert.Equal("POST DeleteAnnotationArtifacts", Sent);
    }
}

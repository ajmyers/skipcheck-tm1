using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>AnnotationService against a real TM1: a port of the Java client's
/// AnnotationServiceTm1Test. Comments on cells, found by cube, cell and ID, and their facets.</summary>
public class AnnotationLiveTests
{
    private const string Cube = "}ClientGroups";

    private static readonly Dictionary<string, string> Cell = new() { ["}Clients"] = "Admin", ["}Groups"] = "ADMIN" };

    [Tm1Fact]
    public async Task AnnotatesACellAndFindsItByCubeCellAndId()
    {
        await using var tm1 = Live.Client();
        var made = await tm1.Annotations.CreateAsync(Cube, Cell, "first note");
        var id = made.Id!;
        try
        {
            Assert.StartsWith("C" + Cube + "~", id);
            Assert.True(made.DynamicProperties!.ContainsKey("objectName"));
            Assert.True(made.DynamicProperties.ContainsKey("commentValue"));
            Assert.Equal("Admin,ADMIN", made.DynamicProperties["commentLocation"].GetString());
            Assert.Empty(await tm1.Annotations.ListAsync().ToListAsync()); // TM1 lists none here
            Assert.Contains(id, await tm1.Annotations.CubeAnnotationsAsync(Cube).Select(a => a.Id).ToListAsync());

            var mdx = $"SELECT {{[}}Clients].[Admin]}} ON 0, {{[}}Groups].[ADMIN]}} ON 1 FROM [{Cube}]";
            var cellset = await tm1.Cellsets.ExecuteAsync(mdx);
            try
            {
                Assert.Equal([id], await tm1.Annotations.CellAnnotationsAsync(cellset.Id!, 0).Select(a => a.Id).ToListAsync());
            }
            finally
            {
                await tm1.Cellsets.DeleteAsync(cellset.Id!);
            }

            Assert.Equal(
                ["[}Clients].[}Clients].[Admin]", "[}Groups].[}Groups].[ADMIN]"],
                await tm1.Annotations.DimensionalContextAsync(id, ODataQuery.None().Select("UniqueName")).Select(m => m.UniqueName).ToListAsync()
            );
            // Reads back empty, though the facet value exists
            Assert.Empty(await tm1.Annotations.ApplicationContextAsync(id).ToListAsync());
            Assert.Contains(Cube, await tm1.Annotations.FacetValuesAsync("}Cubes").Select(v => v.Value).ToListAsync());
            Assert.Equal("}Cubes", (await tm1.Annotations.FacetAsync("}Cubes", Cube)).Name);
            Assert.Contains("}Cubes", await tm1.Annotations.FacetsAsync().Select(f => f.Name).ToListAsync());
            Assert.Empty(await tm1.Annotations.AttachmentsAsync(id).ToListAsync());

            await tm1.Annotations.UpdateAsync(id, AnnotationPatch.Builder().Text("second note").Build());
            var changed = await tm1.Annotations.GetAsync(id);
            Assert.Equal("second note", changed.Text);
            Assert.NotNull(changed.LastUpdatedBy);
            Assert.Equal("first note", changed.DynamicProperties!["commentValue"].GetString()); // kept apart from Text
        }
        finally
        {
            await tm1.Annotations.DeleteAsync(id);
        }
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => tm1.Annotations.GetAsync(id));
    }

    [Tm1Fact]
    public async Task DeletesEveryAnnotationArtifact()
    {
        await using var tm1 = Live.Client();
        await tm1.Annotations.CreateAsync(Cube, Cell, "to be swept");
        await tm1.Annotations.DeleteAnnotationArtifactsAsync();
        Assert.Empty(await tm1.Annotations.CubeAnnotationsAsync(Cube).ToListAsync());
    }
}

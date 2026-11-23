using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Examples;

/// <summary>Examples for <c>ContentService</c>, shown in the guide.</summary>
public static class ContentServiceExamples
{
    public static ContentPath Path()
    {
        // @start region = path
        // Children by ID, which for most entries is the name plus a suffix for the kind
        var notes = ContentPath.Applications().Child("Planning").Child("notes.txt.blob");
        // Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')
        // @end
        return notes;
    }

    public static async Task<Entry> Application(Tm1Client tm1)
    {
        // @start region = application
        // An application: a folder with a link and a reference to a cube
        var planning = ContentPath.Applications().Child("Planning");
        await tm1.Contents.CreateAsync(ContentPath.Applications(), ContentScope.Public, FolderPatch.Builder().Name("Planning").Build());
        await tm1.Contents.CreateAsync(planning, ContentScope.Public, LinkPatch.Builder().Name("Guide").Url("https://example.com/planning-guide").Build());
        return await tm1.Contents.CreateAsync(
            planning,
            ContentScope.Public,
            CubeReferencePatch.Builder().Name("Sales").BindCube(ODataPath.Of("Cubes").Key("Sales")).Build()
        );
        // @end
    }

    public static async Task Upload(Tm1Client tm1, string file)
    {
        // @start region = upload
        // A document made in an application is a reference to one TM1 keeps in Blobs; the
        // reference, as CreateAsync returns it, is where its content goes
        var planning = ContentPath.Applications().Child("Planning");
        var reference = await tm1.Contents.CreateAsync(planning, ContentScope.Public, DocumentPatch.Builder().Name("notes.txt").Build());
        await tm1.Contents.UploadAsync(planning.Child(reference), await File.ReadAllBytesAsync(file));
        // @end
    }

    public static async Task Download(Tm1Client tm1, string file)
    {
        // @start region = download
        // The document's bytes, read whole and written to the file
        var notes = ContentPath.Applications().Child("Planning").Child("notes.txt.blob");
        await File.WriteAllBytesAsync(file, await tm1.Contents.ContentAsync(notes));
        // @end
    }

    public static IAsyncEnumerable<string> Cubes(Tm1Client tm1)
    {
        // @start region = cubes
        // The cubes an application refers to, by a cast expand
        return tm1
            .Contents.ContentsAsync(
                ContentPath.Applications().Child("Planning"),
                ContentScope.Public,
                ODataQuery.None().Expand("tm1.CubeReference/Cube($select=Name)")
            )
            .OfType<CubeReference>()
            .Where(reference => reference.Cube?.Name is not null)
            .Select(reference => reference.Cube!.Name!);
        // @end
    }
}

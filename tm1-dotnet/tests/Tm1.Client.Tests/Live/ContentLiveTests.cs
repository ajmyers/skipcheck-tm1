using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Errors;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Services;

namespace AjMyers.Tm1.Client.Tests.Live;

/// <summary>ContentService against a real TM1: a port of the Java client's ContentServiceTm1Test.</summary>
public class ContentLiveTests
{
    private static string Text(byte[] content) => Encoding.UTF8.GetString(content);

    [Tm1Fact]
    public async Task ListsTheRoots()
    {
        await using var tm1 = Live.Client();
        var roots = await tm1.Contents.RootsAsync().Select(e => e.Name).ToListAsync();
        Assert.Contains("Applications", roots);
        Assert.Contains("Blobs", roots);
    }

    [Tm1Fact]
    public async Task KeepsADocumentInBlobsBehindAReference()
    {
        await using var tm1 = Live.Client();
        var name = Live.Name();
        var folder = ContentPath.Applications().Child(name);
        await tm1.Contents.CreateAsync(ContentPath.Applications(), ContentScope.Public, FolderPatch.Builder().Name(name).Build());
        try
        {
            var made = await tm1.Contents.CreateAsync(folder, ContentScope.Public, DocumentPatch.Builder().Name("notes.txt").Build());
            Assert.IsType<DocumentReference>(made); // a document made in an application is a reference
            Assert.Equal("notes.txt.blob", made.Id); // found by ID, the name plus a suffix
            var notes = folder.Child(made);
            await Assert.ThrowsAsync<Tm1NotFoundException>(() => tm1.Contents.GetAsync(folder.Child("notes.txt"))); // not by name
            await tm1.Contents.UploadAsync(notes, Encoding.UTF8.GetBytes("hello there"));
            Assert.Equal("hello there", Text(await tm1.Contents.ContentAsync(notes)));
            var document = await tm1.Contents.DocumentAsync(notes);
            Assert.Equal(11, document.Size);
            Assert.StartsWith("notes_", document.Name); // named by TM1, with a timestamp
            Assert.EndsWith(".txt", document.Name);
            var blob = ContentPath.Blobs().Child(document.Id!);
            Assert.Equal("hello there", Text(await tm1.Contents.ContentAsync(blob)));
            var copy = await tm1.Contents.CopyAsync(notes, folder, ContentScope.Public, "copy.txt");
            Assert.Equal("copy.txt.blob", copy.Id);
            // A copy refers to the same document
            Assert.Equal(document.Id, (await tm1.Contents.DocumentAsync(folder.Child(copy.Id!))).Id);
            await tm1.Contents.DeleteAsync(notes);
            // Kept while the copy refers to it
            Assert.Equal("hello there", Text(await tm1.Contents.ContentAsync(blob)));
        }
        finally
        {
            await tm1.Contents.DeleteAsync(folder);
        }
    }

    [Tm1Fact]
    public async Task MakesLinksAndReferencesAndMovesThem()
    {
        await using var tm1 = Live.Client();
        var name = Live.Name();
        var folder = ContentPath.Applications().Child(name);
        var other = ContentPath.Applications().Child(name + "-2");
        await tm1.Contents.CreateAsync(ContentPath.Applications(), ContentScope.Public, FolderPatch.Builder().Name(name).Build());
        await tm1.Contents.CreateAsync(ContentPath.Applications(), ContentScope.Public, FolderPatch.Builder().Name(name + "-2").Build());
        try
        {
            var link = await tm1.Contents.CreateAsync(folder, ContentScope.Public, LinkPatch.Builder().Name("ibm").Url("https://www.ibm.com").Build());
            Assert.Equal("ibm.extr", link.Id);
            var reference = await tm1.Contents.CreateAsync(
                folder,
                ContentScope.Public,
                CubeReferencePatch.Builder().Name("}ClientGroups").BindCube(ODataPath.Of("Cubes").Key("}ClientGroups")).Build()
            );
            Assert.Equal("}ClientGroups.cube", reference.Id);
            var listed = await tm1
                .Contents.ContentsAsync(folder, ContentScope.Public, ODataQuery.None().Expand("tm1.CubeReference/Cube($select=Name)"))
                .ToListAsync();
            Assert.Equal("}ClientGroups", Assert.Single(listed.OfType<CubeReference>()).Cube!.Name);
            Assert.Equal("https://www.ibm.com", Assert.IsType<Link>(await tm1.Contents.GetAsync(folder.Child(link.Id!))).Url);
            Assert.Equal(name, (await tm1.Contents.ContainedByAsync(folder.Child(link.Id!))).Id);
            // Entries can't be changed
            await Assert.ThrowsAsync<Tm1BadRequestException>(() =>
                tm1.Session.ExecuteAsync(Tm1Request.Patch(folder.Child(link.Id!).Path(), new Dictionary<string, object?> { ["Name"] = "renamed" }))
            );
            var moved = await tm1.Contents.MoveAsync(folder.Child(link.Id!), other, ContentScope.Public, null);
            Assert.Equal("ibm.extr", moved.Id);
            Assert.Equal(["ibm.extr"], await tm1.Contents.ContentsAsync(other, ContentScope.Public).Select(e => e.Id).ToListAsync());
            await tm1.Contents.CreateAsync(folder, ContentScope.Private, FolderPatch.Builder().Name("mine").Build());
            Assert.Equal(["mine"], await tm1.Contents.ContentsAsync(folder, ContentScope.Private).Select(e => e.Id).ToListAsync());
            Assert.IsType<Folder>(await tm1.Contents.GetAsync(folder.Child("mine", ContentScope.Private)));
            Assert.Empty(await tm1.Contents.LocalizedAttributesAsync(folder).ToListAsync());
        }
        finally
        {
            await Task.WhenAll(tm1.Contents.DeleteAsync(folder), tm1.Contents.DeleteAsync(other));
        }
    }

    [Tm1Fact]
    public async Task RefusesAChunkedUpload()
    {
        await using var tm1 = Live.Client();
        var name = Live.Name();
        var folder = ContentPath.Applications().Child(name);
        await tm1.Contents.CreateAsync(ContentPath.Applications(), ContentScope.Public, FolderPatch.Builder().Name(name).Build());
        try
        {
            var made = await tm1.Contents.CreateAsync(folder, ContentScope.Public, DocumentPatch.Builder().Name("notes.txt").Build());
            var (url, user, password) = Live.Target();
            using var http = new HttpClient();
            using var chunked = new HttpRequestMessage(HttpMethod.Put, url + folder.Child(made.Id!).Path() + "/tm1.DocumentReference/Document/Content")
            {
                Content = new StreamContent(new System.IO.MemoryStream(Encoding.UTF8.GetBytes("hello"))),
            };
            chunked.Headers.Authorization = new AuthenticationHeaderValue("Basic", Convert.ToBase64String(Encoding.UTF8.GetBytes(user + ":" + password)));
            chunked.Headers.TransferEncodingChunked = true; // sent without a length
            chunked.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            Assert.Equal(HttpStatusCode.BadRequest, (await http.SendAsync(chunked)).StatusCode);
        }
        finally
        {
            await tm1.Contents.DeleteAsync(folder);
        }
    }
}

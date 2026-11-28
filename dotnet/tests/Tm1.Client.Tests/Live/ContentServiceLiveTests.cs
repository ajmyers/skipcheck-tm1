// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>ContentService against a real TM1: a port of the Java client's ContentServiceTm1Test.</summary>
public class ContentServiceLiveTests
{
    private static string Text(byte[] content) => Encoding.UTF8.GetString(content);

    [Tm1Fact]
    public async Task ListsTheRoots()
    {
        await using var tm1 = Live.Client();
        var roots = await tm1.Contents.RootsAsync().Select(e => e.Name).ToListAsync();
        Assert.Contains("Applications", roots);
        if (await ServerAssumptions.IsV12Async(tm1))
        {
            Assert.Contains("Files", roots);
            Assert.Contains("Home", roots);
            Assert.Contains("Session", roots);
        }
        else
        {
            Assert.Contains("Blobs", roots);
        }
    }

    [Tm1Fact]
    public async Task KeepsADocumentInBlobsBehindAReference()
    {
        await using var tm1 = Live.Client();
        var v12 = await ServerAssumptions.IsV12Async(tm1);
        var name = Live.Name();
        var folder = ContentPath.Applications().Child(name);
        await tm1.Contents.CreateAsync(ContentPath.Applications(), ContentScope.Public, FolderCreate.Builder().Name(name).Build());
        // TM1 keeps a document as }externals/<name>_<second>.<ext>, server-wide: two made with one
        // name in one second collide (400, code 226), so each test names its own
        var doc = name + "-notes";
        try
        {
            var made = await tm1.Contents.CreateAsync(folder, ContentScope.Public, DocumentCreate.Builder().Name(doc + ".txt").Build());
            Assert.IsType<DocumentReference>(made); // a document made in an application is a reference
            if (v12)
            {
                Assert.Null(made.Id); // TM1 v12 omits deprecated Entry.ID
                Assert.Equal(doc + ".txt", made.Name);
            }
            else
            {
                Assert.Equal(doc + ".txt.blob", made.Id); // found by ID, the name plus a suffix
            }
            var notes = folder.Child(made);
            if (!v12)
            {
                await Assert.ThrowsAsync<Tm1NotFoundException>(() => tm1.Contents.GetAsync(folder.Child(doc + ".txt"))); // not by name
            }
            await tm1.Contents.UploadAsync(notes, Encoding.UTF8.GetBytes("hello there"));
            Assert.Equal("hello there", Text(await tm1.Contents.ContentAsync(notes)));
            var document = await tm1.Contents.DocumentAsync(notes);
            Assert.Equal(11, document.Size);
            Assert.StartsWith(doc + "_", document.Name); // named by TM1, with a timestamp
            Assert.EndsWith(".txt", document.Name);
            var copy = await tm1.Contents.CopyAsync(notes, folder, ContentScope.Public, doc + "-copy.txt");
            if (v12)
            {
                Assert.Equal(doc + "-copy.txt", copy.Name);
                var copiedDoc = await tm1.Contents.DocumentAsync(folder.Child(copy));
                Assert.StartsWith(doc + "-copy_", copiedDoc.Name); // TM1 v12 gives a copy its own backing document
                Assert.EndsWith(".txt", copiedDoc.Name);
                await tm1.Contents.DeleteAsync(notes);
                Assert.Equal("hello there", Text(await tm1.Contents.ContentAsync(folder.Child(copy))));
            }
            else
            {
                var blob = ContentPath.Blobs().Child(document.Id!);
                Assert.Equal("hello there", Text(await tm1.Contents.ContentAsync(blob)));
                Assert.Equal(doc + "-copy.txt.blob", copy.Id);
                // A copy refers to the same document
                Assert.Equal(document.Id, (await tm1.Contents.DocumentAsync(folder.Child(copy.Id!))).Id);
                await tm1.Contents.DeleteAsync(notes);
                // Kept while the copy refers to it
                Assert.Equal("hello there", Text(await tm1.Contents.ContentAsync(blob)));
            }
        }
        finally
        {
            await tm1.Contents.DeleteAsync(folder);
        }
    }

    [Tm1Fact]
    public async Task ReadsAndWritesDocumentsInFilesHomeAndSession()
    {
        await using var tm1 = Live.Client();
        if (!await ServerAssumptions.IsV12Async(tm1))
        {
            return; // direct-document roots (Files, Home, Session) are TM1 v12+ (xUnit 2 can't skip once started)
        }
        var filename = Live.Name() + ".txt";
        foreach (var root in new[] { ContentPath.Files(), ContentPath.Home(), ContentPath.Session() })
        {
            var created = await tm1.Contents.CreateAsync(root, ContentScope.Public, DocumentCreate.Builder().Name(filename).Build());
            Assert.IsType<Document>(created);
            var doc = root.Child(created);
            try
            {
                await tm1.Contents.UploadAsync(doc, Encoding.UTF8.GetBytes("hello v12"));
                Assert.Equal("hello v12", Text(await tm1.Contents.ContentAsync(doc)));
                var read = await tm1.Contents.DocumentAsync(doc);
                Assert.Equal(9, read.Size);
                Assert.Equal(filename, read.Name);
            }
            finally
            {
                await LiveModel.Quietly(() => tm1.Contents.DeleteAsync(doc));
            }
        }
    }

    [Tm1Fact]
    public async Task MakesLinksAndReferencesAndMovesThem()
    {
        await using var tm1 = Live.Client();
        var v12 = await ServerAssumptions.IsV12Async(tm1);
        var name = Live.Name();
        var folder = ContentPath.Applications().Child(name);
        var other = ContentPath.Applications().Child(name + "-2");
        await tm1.Contents.CreateAsync(ContentPath.Applications(), ContentScope.Public, FolderCreate.Builder().Name(name).Build());
        await tm1.Contents.CreateAsync(ContentPath.Applications(), ContentScope.Public, FolderCreate.Builder().Name(name + "-2").Build());
        try
        {
            var link = await tm1.Contents.CreateAsync(folder, ContentScope.Public, LinkCreate.Builder().Name("ibm").Url("https://www.ibm.com").Build());
            var linkPath = folder.Child(link);
            Assert.Equal(v12 ? "ibm" : "ibm.extr", v12 ? link.Name : link.Id);
            var reference = await tm1.Contents.CreateAsync(
                folder,
                ContentScope.Public,
                CubeReferenceCreate.Builder().Name("}ClientGroups").BindCube(ODataPath.Of("Cubes").Key("}ClientGroups")).Build()
            );
            Assert.Equal(v12 ? "}ClientGroups" : "}ClientGroups.cube", v12 ? reference.Name : reference.Id);
            var listed = await tm1
                .Contents.ContentsAsync(folder, ContentScope.Public, ODataQuery.None().Expand("tm1.CubeReference/Cube($select=Name)"))
                .ToListAsync();
            Assert.Equal("}ClientGroups", Assert.Single(listed.OfType<CubeReference>()).Cube!.Name);
            Assert.Equal("https://www.ibm.com", Assert.IsType<Link>(await tm1.Contents.GetAsync(linkPath)).Url);
            var parent = await tm1.Contents.ContainedByAsync(linkPath);
            Assert.Equal(name, v12 ? parent.Name : parent.Id);
            // Entries can't be changed
            await Assert.ThrowsAsync<Tm1BadRequestException>(() =>
                tm1.Session.ExecuteAsync(Tm1Request.Patch(linkPath.Path(), new Dictionary<string, object?> { ["Name"] = "renamed" }))
            );
            var moved = await tm1.Contents.MoveAsync(linkPath, other, ContentScope.Public, null);
            var expectedMoved = v12 ? "ibm" : "ibm.extr";
            Assert.Equal(expectedMoved, v12 ? moved.Name : moved.Id);
            Assert.Equal([expectedMoved], await tm1.Contents.ContentsAsync(other, ContentScope.Public).Select(e => v12 ? e.Name : e.Id).ToListAsync());
            await tm1.Contents.CreateAsync(folder, ContentScope.Private, FolderCreate.Builder().Name("mine").Build());
            Assert.Equal(["mine"], await tm1.Contents.ContentsAsync(folder, ContentScope.Private).Select(e => v12 ? e.Name : e.Id).ToListAsync());
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
        await tm1.Contents.CreateAsync(ContentPath.Applications(), ContentScope.Public, FolderCreate.Builder().Name(name).Build());
        using var raw = Live.IsEngine ? new RawTm1() : null;
        string? cookie = null;
        try
        {
            cookie = raw?.Login();
            // A name of its own: see KeepsADocumentInBlobsBehindAReference
            var made = await tm1.Contents.CreateAsync(folder, ContentScope.Public, DocumentCreate.Builder().Name(name + "-notes.txt").Build());
            var (url, user, password) = Live.Target();
            // TM1_VERIFY and TM1_URL as the other requests take them: RawTm1's handler, and a / after the URL
            using var http = new HttpClient(RawTm1.Handler());
            using var chunked = new HttpRequestMessage(
                HttpMethod.Put,
                url.TrimEnd('/') + "/" + folder.Child(made).Path() + "/tm1.DocumentReference/Document/Content"
            )
            {
                Content = new StreamContent(new System.IO.MemoryStream(Encoding.UTF8.GetBytes("hello"))),
            };
            if (cookie is not null)
            {
                chunked.Headers.TryAddWithoutValidation("Cookie", cookie);
            }
            else
            {
                chunked.Headers.Authorization = new AuthenticationHeaderValue("Basic", Convert.ToBase64String(Encoding.UTF8.GetBytes(user + ":" + password)));
            }
            chunked.Headers.TransferEncodingChunked = true; // sent without a length
            chunked.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            // TM1 11 refuses chunked uploads (400); TM1 v12's gateway accepts them (204)
            var expected = await ServerAssumptions.IsV12Async(tm1) ? HttpStatusCode.NoContent : HttpStatusCode.BadRequest;
            Assert.Equal(expected, (await http.SendAsync(chunked)).StatusCode);
        }
        finally
        {
            if (raw is not null && cookie is not null)
            {
                raw.Close(cookie);
            }
            await tm1.Contents.DeleteAsync(folder);
        }
    }
}

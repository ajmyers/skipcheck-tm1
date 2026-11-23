// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>ContentService and ContentPath against a fake TM1: each method sends what the Java client's does.</summary>
public class ContentServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    private string Sent => $"{_tm1.Last.Method} {_tm1.Last.Target}";

    [Fact]
    public void PathsFindChildrenById()
    {
        var notes = ContentPath.Applications().Child("Planning").Child("notes.txt.blob");
        Assert.Equal("Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')", notes.Path().ToString());
        Assert.Equal("Applications/Planning/notes.txt.blob", notes.ToString());
        Assert.False(notes.InBlobs());
        Assert.True(ContentPath.Blobs().Child("a.txt").InBlobs());

        var mine = ContentPath.Applications().Child("mine", ContentScope.Private);
        Assert.Equal("Contents('Applications')/PrivateContents('mine')", mine.Path().ToString());
        Assert.Equal("Applications/(private) mine", mine.ToString());
        Assert.Equal(ContentPath.Root("Applications").Child("Planning"), ContentPath.Applications().Child(new Folder { Id = "Planning" }));
        Assert.Equal(ContentPath.Applications().Child("Planning").GetHashCode(), ContentPath.Applications().Child("Planning").GetHashCode());
        Assert.NotEqual(mine, ContentPath.Applications().Child("mine"));
        Assert.Throws<ArgumentException>(() => ContentPath.Applications().Child(new Folder { Name = "Planning" }));
        Assert.Equal("PrivateContents", ContentScope.Private.Navigation());
    }

    [Fact]
    public async Task ListsGetsAndDeletes()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ =>
            FakeTm1.Collection(
                new Dictionary<string, object?>
                {
                    ["@odata.type"] = "#ibm.tm1.api.v1.Folder",
                    ["ID"] = "Applications",
                    ["Name"] = "Applications",
                }
            );
        Assert.IsType<Folder>(Assert.Single(await tm1.Contents.RootsAsync(ODataQuery.None().Select("Name")).ToListAsync()));
        Assert.Equal("GET Contents?$select=Name", Sent);

        var planning = ContentPath.Applications().Child("Planning");
        await tm1.Contents.ContentsAsync(planning, ContentScope.Public, ODataQuery.None().Expand("tm1.CubeReference/Cube($select=Name)")).ToListAsync();
        Assert.Equal("GET Contents('Applications')/Contents('Planning')/Contents?$expand=tm1.CubeReference/Cube($select=Name)", Uri.UnescapeDataString(Sent));
        await tm1.Contents.ContentsAsync(planning, ContentScope.Private).ToListAsync();
        Assert.Equal("GET Contents('Applications')/Contents('Planning')/PrivateContents", Sent);

        _tm1.Respond = _ => new Dictionary<string, object?>
        {
            ["@odata.type"] = "#ibm.tm1.api.v1.Link",
            ["ID"] = "ibm.extr",
            ["URL"] = "https://www.ibm.com",
        };
        var link = Assert.IsType<Link>(await tm1.Contents.GetAsync(planning.Child("ibm.extr")));
        Assert.Equal("https://www.ibm.com", link.Url);
        Assert.Equal("GET Contents('Applications')/Contents('Planning')/Contents('ibm.extr')", Sent);

        _tm1.Respond = _ => new { ID = "Planning" };
        Assert.Equal("Planning", (await tm1.Contents.ContainedByAsync(planning.Child("ibm.extr"))).Id);
        Assert.Equal("GET Contents('Applications')/Contents('Planning')/Contents('ibm.extr')/ContainedBy", Sent);
        _tm1.Respond = _ => FakeTm1.Collection();
        await tm1.Contents.LocalizedAttributesAsync(planning).ToListAsync();
        Assert.Equal("GET Contents('Applications')/Contents('Planning')/LocalizedAttributes", Sent);

        _tm1.Respond = _ => null;
        await tm1.Contents.DeleteAsync(planning);
        Assert.Equal("DELETE Contents('Applications')/Contents('Planning')", Sent);
    }

    [Fact]
    public async Task CreatesCopiesAndMoves()
    {
        var tm1 = _tm1.Client();
        var planning = ContentPath.Applications().Child("Planning");
        _tm1.Respond = _ => new Dictionary<string, object?> { ["@odata.type"] = "#ibm.tm1.api.v1.DocumentReference", ["ID"] = "notes.txt.blob" };
        var made = await tm1.Contents.CreateAsync(planning, ContentScope.Public, DocumentPatch.Builder().Name("notes.txt").Build());
        Assert.IsType<DocumentReference>(made);
        Assert.Equal(("POST Contents('Applications')/Contents('Planning')/Contents", "notes.txt"), (Sent, _tm1.Last.Json.GetProperty("Name").GetString()));
        await tm1.Contents.CreateAsync(planning, ContentScope.Private, FolderPatch.Builder().Name("mine").Build());
        Assert.Equal("POST Contents('Applications')/Contents('Planning')/PrivateContents", Sent);

        var other = ContentPath.Applications().Child("Other");
        await tm1.Contents.CopyAsync(planning.Child(made), other, ContentScope.Public, "copy.txt");
        Assert.Equal("POST Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')/tm1.Copy", Sent);
        Assert.Equal("""{"Target@odata.bind":"Contents('Applications')/Contents('Other')","Private":false,"Name":"copy.txt"}""", _tm1.Last.Text);
        await tm1.Contents.MoveAsync(planning.Child(made), other, ContentScope.Private, null);
        Assert.Equal("POST Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')/tm1.Move", Sent);
        Assert.Equal("""{"Target@odata.bind":"Contents('Applications')/Contents('Other')","Private":true}""", _tm1.Last.Text);
    }

    [Fact]
    public async Task ReadsAndWritesDocuments()
    {
        var tm1 = _tm1.Client();
        var notes = ContentPath.Applications().Child("Planning").Child("notes.txt.blob");
        _tm1.Respond = _ => new { ID = "notes_20261122125939.txt", Size = 11 };
        Assert.Equal(11, (await tm1.Contents.DocumentAsync(notes)).Size);
        Assert.Equal("GET Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')/tm1.DocumentReference/Document", Sent);

        _tm1.Respond = _ => new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(Encoding.UTF8.GetBytes("hello there")) };
        Assert.Equal("hello there", Encoding.UTF8.GetString(await tm1.Contents.ContentAsync(notes)));
        Assert.Equal("GET Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')/tm1.DocumentReference/Document/Content", Sent);
        await tm1.Contents.ContentAsync(ContentPath.Blobs().Child("notes_1.txt"));
        Assert.Equal("GET Contents('Blobs')/Contents('notes_1.txt')/tm1.Document/Content", Sent);

        _tm1.Respond = _ => null;
        await tm1.Contents.UploadAsync(notes, Encoding.UTF8.GetBytes("hello"));
        Assert.Equal("PUT Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')/tm1.DocumentReference/Document/Content", Sent);
        Assert.Equal("hello", _tm1.Last.Text);
        Assert.Equal("5", _tm1.Last.Headers["content-length"]);
    }
}

// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>The contents tree, where Planning Analytics keeps applications: folders, documents,
/// links, and references to cubes, dimensions, subsets, views, processes and chores. Entries are
/// addressed by <see cref="ContentPath"/>.
/// <para>What TM1 (11.8) does here that you might not expect:</para>
/// <list type="bullet">
/// <item>Entries are found by <c>ID</c>, not <c>Name</c> (see <see cref="ContentPath"/>).</item>
/// <item>A document made in an application becomes a <c>DocumentReference</c> there, to a
/// <c>Document</c> TM1 makes in <c>Blobs</c> with a timestamp in its name, such as
/// <c>notes_20261122125939.txt</c>. Its content goes through the reference
/// (<see cref="ContentAsync"/>, <see cref="UploadAsync"/>). A copy of the reference refers to the same
/// document; deleting a reference leaves the document while another refers to it.</item>
/// <item>Entries can't be changed: TM1 refuses a PATCH ("PATCH not supported on this resource"), so
/// there's no update. To rename one, copy it with a new name and delete the original.</item>
/// </list>
/// <para>Build an application: a folder, a link and a cube reference:</para>
/// <code>
/// // An application: a folder with a link and a reference to a cube
/// var planning = ContentPath.Applications().Child("Planning");
/// await tm1.Contents.CreateAsync(ContentPath.Applications(), ContentScope.Public, FolderPatch.Builder().Name("Planning").Build());
/// await tm1.Contents.CreateAsync(planning, ContentScope.Public, LinkPatch.Builder().Name("Guide").Url("https://example.com/planning-guide").Build());
/// await tm1.Contents.CreateAsync(planning, ContentScope.Public, CubeReferencePatch.Builder().Name("Sales").BindCube(ODataPath.Of("Cubes").Key("Sales")).Build());
/// </code>
/// <para>Upload a document from a file:</para>
/// <code>
/// // A document made in an application is a reference to one TM1 keeps in Blobs; the
/// // reference, as create returns it, is where its content goes
/// var planning = ContentPath.Applications().Child("Planning");
/// var reference = await tm1.Contents.CreateAsync(planning, ContentScope.Public, DocumentPatch.Builder().Name("notes.txt").Build());
/// await tm1.Contents.UploadAsync(planning.Child(reference), await File.ReadAllBytesAsync(file));
/// </code>
/// <para>Download a document's content to a file:</para>
/// <code>
/// var notes = ContentPath.Applications().Child("Planning").Child("notes.txt.blob");
/// await File.WriteAllBytesAsync(file, await tm1.Contents.ContentAsync(notes));
/// </code>
/// <para>List the cubes an application refers to:</para>
/// <code>
/// // The cubes an application refers to, by a cast expand
/// var cubes = await tm1.Contents.ContentsAsync(ContentPath.Applications().Child("Planning"), ContentScope.Public,
///         ODataQuery.None().Expand("tm1.CubeReference/Cube($select=Name)"))
///     .OfType&lt;CubeReference&gt;()
///     .Where(reference =&gt; reference.Cube is not null)
///     .Select(reference =&gt; reference.Cube!.Name)
///     .ToListAsync();
/// </code>
/// </summary>
public sealed class ContentService : Tm1Service
{
    /// <summary>A service that sends its requests through <paramref name="session"/>.</summary>
    public ContentService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET Contents</c>, streamed: the root folders, <c>Applications</c> and <c>Blobs</c>.</summary>
    [Tm1Endpoint("EntitySet Contents")]
    public IAsyncEnumerable<Entry> RootsAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Entry>(ODataPath.Of("Contents"), query), cancellationToken);

    /// <summary><c>GET</c> of one entry. A reference's object comes by a cast expand, such as
    /// <c>Expand("tm1.CubeReference/Cube")</c>. Returns the entry, of its own kind (<c>Folder</c>,
    /// <c>Link</c>, <c>CubeReference</c> …), or a <c>Tm1NotFoundException</c> if there's none.</summary>
    [Tm1Endpoint("EntitySet Contents")]
    public Task<Entry> GetAsync(ContentPath entry, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Entry>(Require(entry, nameof(entry)).Path(), query), cancellationToken);

    /// <summary>A folder's entries, streamed, each of its own kind. References' objects come by a cast
    /// expand, such as <c>Expand("tm1.CubeReference/Cube($select=Name)")</c>.</summary>
    [Tm1Endpoint("Navigation Folder/Contents")]
    [Tm1Endpoint("Navigation Folder/PrivateContents")]
    [Tm1Endpoint("Navigation ChoreReference/Chore")]
    [Tm1Endpoint("Navigation CubeReference/Cube")]
    [Tm1Endpoint("Navigation DimensionReference/Dimension")]
    [Tm1Endpoint("Navigation ProcessReference/Process")]
    [Tm1Endpoint("Navigation SubsetReference/Subset")]
    [Tm1Endpoint("Navigation ViewReference/View")]
    public IAsyncEnumerable<Entry> ContentsAsync(
        ContentPath folder,
        ContentScope scope,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.StreamAsync(Tm1Request.List<Entry>(Require(folder, nameof(folder)).Path().Nav(scope.Navigation()), query), cancellationToken);

    /// <summary>Makes an entry in a folder: a <c>FolderPatch</c>, <c>DocumentPatch</c>, <c>LinkPatch</c>
    /// or a reference, such as a <c>CubeReferencePatch</c> with
    /// <c>BindCube(ODataPath.Of("Cubes").Key("Sales"))</c>. A document made outside <c>Blobs</c> comes
    /// back as a <c>DocumentReference</c>, with an empty document behind it; <see cref="UploadAsync"/>
    /// gives it content. Returns the entry as made, with the <c>ID</c> to find it by.</summary>
    [Tm1Endpoint("Navigation Folder/Contents")]
    [Tm1Endpoint("Navigation Folder/PrivateContents")]
    public Task<Entry> CreateAsync(ContentPath folder, ContentScope scope, EntryPatch entry, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(
            Tm1Request.Post<Entry>(Require(folder, nameof(folder)).Path().Nav(scope.Navigation()), Require(entry, nameof(entry))),
            cancellationToken
        );

    /// <summary><c>DELETE</c> of an entry, and everything in it if it's a folder.</summary>
    [Tm1Endpoint("EntitySet Contents")]
    public Task DeleteAsync(ContentPath entry, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(Require(entry, nameof(entry)).Path()), cancellationToken);

    /// <summary><c>ContainedBy</c>: the folder an entry is in.</summary>
    [Tm1Endpoint("Navigation Entry/ContainedBy")]
    public Task<Folder> ContainedByAsync(ContentPath entry, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Folder>(Require(entry, nameof(entry)).Path().Nav("ContainedBy"), query), cancellationToken);

    /// <summary><c>LocalizedAttributes</c>, streamed: an entry's attributes in each locale.</summary>
    [Tm1Endpoint("Navigation Entry/LocalizedAttributes")]
    public IAsyncEnumerable<LocalizedAttributes> LocalizedAttributesAsync(
        ContentPath entry,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.StreamAsync(Tm1Request.List<LocalizedAttributes>(Require(entry, nameof(entry)).Path().Nav("LocalizedAttributes"), query), cancellationToken);

    /// <summary><c>tm1.Copy</c>: copies an entry into the folder <paramref name="target"/>, public or
    /// private, named <paramref name="name"/> or, if null, as the entry is. A copied document reference
    /// refers to the same document. Returns the copy.</summary>
    [Tm1Endpoint("Action Entry/Copy")]
    public Task<Entry> CopyAsync(
        ContentPath entry,
        ContentPath target,
        ContentScope scope,
        string? name = null,
        CancellationToken cancellationToken = default
    ) => Relocate(Tm1Operations.EntryCopy, entry, target, scope, name, cancellationToken);

    /// <summary><c>tm1.Move</c>: moves an entry into the folder <paramref name="target"/>, public or
    /// private there, renamed <paramref name="name"/> unless it's null. Returns the entry where it is now.</summary>
    [Tm1Endpoint("Action Entry/Move")]
    public Task<Entry> MoveAsync(
        ContentPath entry,
        ContentPath target,
        ContentScope scope,
        string? name = null,
        CancellationToken cancellationToken = default
    ) => Relocate(Tm1Operations.EntryMove, entry, target, scope, name, cancellationToken);

    /// <summary><c>Document</c>: the document a reference refers to, in <c>Blobs</c>, with its
    /// <c>Size</c> and <c>LastUpdated</c>.</summary>
    [Tm1Endpoint("Navigation DocumentReference/Document")]
    public Task<Document> DocumentAsync(ContentPath reference, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Document>(DocumentPath(Require(reference, nameof(reference))), query), cancellationToken);

    /// <summary>A document's <c>Content</c>, read whole. The path is a document reference, or a
    /// document in <c>Blobs</c>.</summary>
    [Tm1Endpoint("Navigation DocumentReference/Document")]
    public Task<byte[]> ContentAsync(ContentPath document, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Bytes(DocumentPath(Require(document, nameof(document))).Nav("Content")), cancellationToken);

    /// <summary>Replaces a document's <c>Content</c>. The path is a document reference, or a document in
    /// <c>Blobs</c>. TM1 (11.8) refuses a chunked upload, so the content is sent whole, with its length.</summary>
    [Tm1Endpoint("Navigation DocumentReference/Document")]
    public Task UploadAsync(ContentPath document, byte[] content, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(
            Tm1Request.Put(DocumentPath(Require(document, nameof(document))).Nav("Content"), Require(content, nameof(content))),
            cancellationToken
        );

    private static ODataPath DocumentPath(ContentPath entry) =>
        entry.InBlobs() ? entry.Path().Cast("tm1.Document") : entry.Path().Cast("tm1.DocumentReference").Nav("Document");

    private Task<Entry> Relocate(
        Tm1Operation operation,
        ContentPath entry,
        ContentPath target,
        ContentScope scope,
        string? name,
        CancellationToken cancellationToken
    )
    {
        var body = new Dictionary<string, object?>
        {
            ["Target@odata.bind"] = Require(target, nameof(target)).Path().ToString(),
            ["Private"] = scope == ContentScope.Private,
        };
        if (name is not null)
        {
            body["Name"] = name;
        }
        var request = Tm1Request.Post<Entry>(Require(entry, nameof(entry)).Path().Action(operation.Name), body).Requires(operation.Capability());
        return Session.ExecuteAsync(request, cancellationToken);
    }
}

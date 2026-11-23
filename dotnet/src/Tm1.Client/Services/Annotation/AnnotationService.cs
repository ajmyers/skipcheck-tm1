// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Annotations: comments on cube cells, as Planning Analytics Workspace makes them, and the
/// application context facets that tie them to cubes.
/// <para>What TM1 (11.8) does here that you might not expect:</para>
/// <list type="bullet">
/// <item><c>GET Annotations</c> lists none, even with annotations made. They're found by cube
/// (<see cref="CubeAnnotationsAsync"/>), by cell (<see cref="CellAnnotationsAsync"/>), or by ID.</item>
/// <item>An annotation's <c>ApplicationContext</c> reads back empty, though the facet value given when
/// it was made exists (<see cref="FacetValuesAsync"/>).</item>
/// <item>Besides <c>Text</c>, an annotation carries properties <c>$metadata</c> doesn't declare
/// (<c>Annotation</c> is an open type): <c>objectName</c> (the cube), <c>commentValue</c>,
/// <c>commentType</c> and <c>commentLocation</c> (the cell's elements, comma-separated). They're in
/// its extra properties.</item>
/// <item>IDs look like <c>C}ClientGroups~B.B~7uA_4TxLwG</c>.</item>
/// </list>
/// <para>Comment on a cell:</para>
/// <code>
/// // A comment on one cell, as Planning Analytics Workspace makes it: the cell's
/// // elements by dimension, in the cube's dimension order
/// var cell = new Dictionary&lt;string, string&gt; { ["Version"] = "Forecast", ["Region"] = "North", ["Measure"] = "Amount" };
/// var annotation = await tm1.Annotations.CreateAsync("Sales", cell, "Includes the March promotion");
/// </code>
/// <para>Read a cube's comments:</para>
/// <code>
/// // GET Annotations lists none on TM1 11.8, so read them by cube
/// await foreach (var annotation in tm1.Annotations.CubeAnnotationsAsync("Sales"))
/// {
///     Console.WriteLine(annotation.Creator + ": " + annotation.Text);
/// }
/// </code>
/// <para>Change what a comment says:</para>
/// <code>
/// // TM1 keeps Text and the commentValue PAW shows apart, so change both
/// var text = "Includes the March and April promotions";
/// await tm1.Annotations.UpdateAsync(id, AnnotationPatch.Builder().Text(text).Property("commentValue", text).Build());
/// </code>
/// </summary>
public sealed class AnnotationService : Tm1Service
{
    private static readonly ODataPath Annotations = ODataPath.Of("Annotations");
    private static readonly ODataPath Facets = ODataPath.Of("ApplicationContextFacets");

    /// <summary>A service that sends its requests through <paramref name="session"/>.</summary>
    public AnnotationService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET Annotations</c>, streamed. TM1 (11.8) lists none here: use
    /// <see cref="CubeAnnotationsAsync"/> or <see cref="CellAnnotationsAsync"/>.</summary>
    [Tm1Endpoint("EntitySet Annotations")]
    public IAsyncEnumerable<Annotation> ListAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Annotation>(Annotations, query), cancellationToken);

    /// <summary><c>GET Annotations('id')</c>: the annotation, or a <c>Tm1NotFoundException</c> if there's none.</summary>
    [Tm1Endpoint("EntitySet Annotations")]
    public Task<Annotation> GetAsync(string id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Annotation>(AnnotationPath(id), query), cancellationToken);

    /// <summary>Annotates one cell, the way Planning Analytics Workspace does: <c>Text</c> and
    /// <c>commentValue</c> are the text, the <c>}Cubes</c> facet names the cube, and the dimensional
    /// context binds the cell's members. <paramref name="elements"/> is the cell's element in each
    /// dimension's same-named hierarchy, by dimension, in the cube's dimension order. Returns the
    /// annotation, with its ID.</summary>
    [Tm1Endpoint("EntitySet Annotations")]
    public Task<Annotation> CreateAsync(string cube, IReadOnlyDictionary<string, string> elements, string text, CancellationToken cancellationToken = default)
    {
        Require(cube, nameof(cube));
        Require(text, nameof(text));
        var members = Require(elements, nameof(elements))
            .Select(e => ODataPath.Of("Dimensions").Key(e.Key).Nav("Hierarchies").Key(e.Key).Nav("Members").Key(e.Value))
            .ToArray();
        return CreateAsync(
            AnnotationPatch
                .Builder()
                .Text(text)
                .ApplicationContext([ApplicationContextFacetValuePatch.Builder().BindFacet(Facets.Key("}Cubes")).Value(cube).Build()])
                .BindDimensionalContext(members)
                .Property("objectName", cube)
                .Property("commentValue", text)
                .Property("commentType", "ANNOTATION")
                .Property("commentLocation", string.Join(",", elements.Values))
                .Build(),
            cancellationToken
        );
    }

    /// <summary><c>POST Annotations</c>, for an annotation built by hand. Returns the annotation as
    /// made, with its ID.</summary>
    [Tm1Endpoint("EntitySet Annotations")]
    public Task<Annotation> CreateAsync(AnnotationPatch annotation, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Post<Annotation>(Annotations, Require(annotation, nameof(annotation))), cancellationToken);

    /// <summary><c>PATCH Annotations('id')</c>. To change what it says, set both <c>Text</c> and the
    /// <c>commentValue</c> property: TM1 keeps them apart. TM1 records <c>LastUpdatedBy</c> and
    /// <c>LastUpdated</c>.</summary>
    [Tm1Endpoint("EntitySet Annotations")]
    public Task UpdateAsync(string id, AnnotationPatch patch, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Patch(AnnotationPath(id), Require(patch, nameof(patch))), cancellationToken);

    /// <summary><c>DELETE Annotations('id')</c>.</summary>
    [Tm1Endpoint("EntitySet Annotations")]
    public Task DeleteAsync(string id, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(AnnotationPath(id)), cancellationToken);

    /// <summary><c>ApplicationContext</c>, streamed, with <c>Facet</c> by expand. TM1 (11.8) returns none.</summary>
    [Tm1Endpoint("Navigation Annotation/ApplicationContext")]
    public IAsyncEnumerable<ApplicationContextFacetValue> ApplicationContextAsync(
        string id,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.StreamAsync(Tm1Request.List<ApplicationContextFacetValue>(AnnotationPath(id).Nav("ApplicationContext"), query), cancellationToken);

    /// <summary><c>DimensionalContext</c>, streamed: the members of the annotated cell.</summary>
    [Tm1Endpoint("Navigation Annotation/DimensionalContext")]
    public IAsyncEnumerable<Member> DimensionalContextAsync(string id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Member>(AnnotationPath(id).Nav("DimensionalContext"), query), cancellationToken);

    /// <summary><c>Attachments</c>, streamed: documents attached to the annotation.</summary>
    [Tm1Endpoint("Navigation Annotation/Attachments")]
    public IAsyncEnumerable<Attachment> AttachmentsAsync(string id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Attachment>(AnnotationPath(id).Nav("Attachments"), query), cancellationToken);

    /// <summary><c>Cubes('c')/Annotations</c>, streamed: a cube's annotations.</summary>
    [Tm1Endpoint("Navigation Cube/Annotations")]
    public IAsyncEnumerable<Annotation> CubeAnnotationsAsync(string cube, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Annotation>(ODataPath.Of("Cubes").Key(Require(cube, nameof(cube))).Nav("Annotations"), query), cancellationToken);

    /// <summary><c>Cellsets('id')/Cells(n)/Annotations</c>, streamed: one cellset cell's annotations.
    /// They also come with the cells by <c>Expand("Annotations")</c>.</summary>
    [Tm1Endpoint("Navigation CellsetCell/Annotations")]
    public IAsyncEnumerable<Annotation> CellAnnotationsAsync(
        string cellset,
        long ordinal,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    )
    {
        var path = ODataPath.Of("Cellsets").Key(Require(cellset, nameof(cellset))).Nav("Cells").Key(ordinal).Nav("Annotations");
        return Session.StreamAsync(Tm1Request.List<Annotation>(path, query), cancellationToken);
    }

    /// <summary><c>GET ApplicationContextFacets</c>, streamed, such as <c>}Cubes</c>; their
    /// <c>Values</c> by expand.</summary>
    [Tm1Endpoint("EntitySet ApplicationContextFacets")]
    public IAsyncEnumerable<ApplicationContextFacet> FacetsAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<ApplicationContextFacet>(Facets, query), cancellationToken);

    /// <summary>A facet's <c>Values</c>, streamed: for <c>}Cubes</c>, the cubes with annotations.</summary>
    [Tm1Endpoint("Navigation ApplicationContextFacet/Values")]
    public IAsyncEnumerable<ApplicationContextFacetValue> FacetValuesAsync(
        string facet,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.StreamAsync(Tm1Request.List<ApplicationContextFacetValue>(Facets.Key(Require(facet, nameof(facet))).Nav("Values"), query), cancellationToken);

    /// <summary>A facet value's <c>Facet</c>.</summary>
    [Tm1Endpoint("Navigation ApplicationContextFacetValue/Facet")]
    public Task<ApplicationContextFacet> FacetAsync(string facet, string value, ODataQuery? query = null, CancellationToken cancellationToken = default)
    {
        var path = Facets.Key(Require(facet, nameof(facet))).Nav("Values").Key(Require(value, nameof(value))).Nav("Facet");
        return Session.ExecuteAsync(Tm1Request.Get<ApplicationContextFacet>(path, query), cancellationToken);
    }

    /// <summary><c>DeleteAnnotationArtifacts</c>: removes every annotation in the model, with their
    /// facet values, attachments and control cubes. It can't be undone.</summary>
    [Tm1Endpoint("ActionImport DeleteAnnotationArtifacts")]
    public Task DeleteAnnotationArtifactsAsync(CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.DeleteAnnotationArtifacts;
        return Session.ExecuteAsync(Tm1Request.Post(ODataPath.Of(operation.Import), null).Requires(operation.Capability()), cancellationToken);
    }

    private static ODataPath AnnotationPath(string id) => Annotations.Key(Require(id, nameof(id)));
}

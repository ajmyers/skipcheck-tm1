using System.Collections.Generic;
using System.Linq;

namespace Skipcheck.Tm1.Client.OData;

/// <summary>OData's JSON control information, as the Java client's <c>ODataJson</c>.</summary>
public static class ODataJson
{
    /// <summary>The context URL.</summary>
    public const string Context = "@odata.context";

    /// <summary>An entity's ETag, for optimistic concurrency (<c>If-Match</c>).</summary>
    public const string ETag = "@odata.etag";

    /// <summary>The (derived) type of an entity, such as <c>#ibm.tm1.api.v1.MDXView</c>.</summary>
    public const string Type = "@odata.type";

    /// <summary>An entity's ID.</summary>
    public const string Id = "@odata.id";

    /// <summary>The next page of a partial collection.</summary>
    public const string NextLink = "@odata.nextLink";

    /// <summary>The link for changes since this response, with change tracking.</summary>
    public const string DeltaLink = "@odata.deltaLink";

    /// <summary>The total number of items, with <c>$count=true</c>.</summary>
    public const string Count = "@odata.count";

    /// <summary>The suffix binding a navigation property to existing entities in a
    /// request.</summary>
    public const string BindSuffix = "@odata.bind";

    /// <summary><c>{"Cube@odata.bind": "Cubes('c')"}</c>, for a POST or PATCH body.</summary>
    public static IReadOnlyDictionary<string, object?> Bind(string navigation, ODataPath target) =>
        new Dictionary<string, object?> { [navigation + BindSuffix] = target.ToString() };

    /// <summary><c>{"Dimensions@odata.bind": ["Dimensions('a')", …]}</c>.</summary>
    public static IReadOnlyDictionary<string, object?> Bind(string navigation, IEnumerable<ODataPath> targets) =>
        new Dictionary<string, object?> { [navigation + BindSuffix] = targets.Select(t => t.ToString()).ToList() };
}

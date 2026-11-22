using System.Collections.Generic;
using System.Linq;

namespace AjMyers.Tm1.Client.OData;

/// <summary>OData's JSON control information, as the Java client's <c>ODataJson</c>.</summary>
public static class ODataJson
{
    public const string Context = "@odata.context";
    public const string ETag = "@odata.etag";
    public const string Type = "@odata.type";
    public const string Id = "@odata.id";
    public const string NextLink = "@odata.nextLink";
    public const string DeltaLink = "@odata.deltaLink";
    public const string Count = "@odata.count";
    public const string BindSuffix = "@odata.bind";

    /// <summary><c>{"Cube@odata.bind": "Cubes('c')"}</c>, for a POST or PATCH body.</summary>
    public static IReadOnlyDictionary<string, object?> Bind(string navigation, ODataPath target) =>
        new Dictionary<string, object?> { [navigation + BindSuffix] = target.ToString() };

    /// <summary><c>{"Dimensions@odata.bind": ["Dimensions('a')", …]}</c>.</summary>
    public static IReadOnlyDictionary<string, object?> Bind(string navigation, IEnumerable<ODataPath> targets) =>
        new Dictionary<string, object?> { [navigation + BindSuffix] = targets.Select(t => t.ToString()).ToList() };
}

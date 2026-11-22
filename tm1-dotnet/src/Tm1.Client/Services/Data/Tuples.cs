using System;
using System.Collections.Generic;
using System.Linq;
using AjMyers.Tm1.Client.OData;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Cell coordinates, as TM1 binds them: one element path per dimension of the cube, in the
/// cube's order, as the Java client's <c>Tuples</c>.</summary>
public static class Tuples
{
    /// <summary>The element paths of a cell, dimension to element, each element in its dimension's
    /// own hierarchy, in the order given (which must be the cube's).</summary>
    public static ODataPath[] Of(IReadOnlyDictionary<string, string> tuple)
    {
        ArgumentNullException.ThrowIfNull(tuple);
        return tuple.Select(e => Element(e.Key, e.Key, e.Value)).ToArray();
    }

    /// <summary><c>Dimensions('d')/Hierarchies('h')/Elements('e')</c>.</summary>
    public static ODataPath Element(string dimension, string hierarchy, string element) =>
        ODataPath.Of("Dimensions")
            .Key(dimension ?? throw new ArgumentNullException(nameof(dimension)))
            .Nav("Hierarchies")
            .Key(hierarchy ?? throw new ArgumentNullException(nameof(hierarchy)))
            .Nav("Elements")
            .Key(element ?? throw new ArgumentNullException(nameof(element)));

    internal static List<string> Bindings(IEnumerable<ODataPath> paths) => paths.Select(p => p.ToString()).ToList();
}

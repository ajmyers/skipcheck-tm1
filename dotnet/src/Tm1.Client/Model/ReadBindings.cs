// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Text;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Model;

/// <summary>The paths a write's generated <c>Builder(read)</c> binds a read's navigations to: null where
/// one can't be made. As the Java model's generated <c>ReadBindings</c>.</summary>
internal static class ReadBindings
{
    /// <summary>An entity in a set, by its key: <c>Dimensions('Region')</c>.</summary>
    internal static ODataPath? ByKey(string set, object? key) => key is null ? null : ODataPath.Of(set).Key(key);

    /// <summary>Entities in a set, by their keys: null if any key is missing.</summary>
    internal static ODataPath[]? ByKeys(string set, IReadOnlyList<object?> keys)
    {
        var result = new ODataPath[keys.Count];
        for (var i = 0; i < result.Length; i++)
        {
            if (ByKey(set, keys[i]) is not { } path)
            {
                return null;
            }
            result[i] = path;
        }
        return result;
    }

    /// <summary>An element or member, by its <c>UniqueName</c>: <c>[d].[h].[e]</c>, or <c>[d].[e]</c> in the
    /// dimension's own hierarchy, with <c>]]</c> for <c>]</c>. Null for any other shape.</summary>
    internal static ODataPath? InHierarchy(string collection, string? uniqueName)
    {
        if (uniqueName is null)
        {
            return null;
        }
        var parts = new List<string>();
        var i = 0;
        while (i < uniqueName.Length)
        {
            if (uniqueName[i] != '[')
            {
                return null;
            }
            var part = new StringBuilder();
            var j = i + 1;
            while (true)
            {
                if (j >= uniqueName.Length)
                {
                    return null;
                }
                var c = uniqueName[j];
                if (c == ']')
                {
                    if (j + 1 < uniqueName.Length && uniqueName[j + 1] == ']')
                    {
                        part.Append(']');
                        j += 2;
                        continue;
                    }
                    break;
                }
                part.Append(c);
                j++;
            }
            parts.Add(part.ToString());
            i = j + 1;
            if (i < uniqueName.Length)
            {
                if (uniqueName[i] != '.')
                {
                    return null;
                }
                i++;
                if (i == uniqueName.Length)
                {
                    return null;
                }
            }
        }
        if (parts.Count == 2)
        {
            parts.Insert(1, parts[0]);
        }
        if (parts.Count != 3)
        {
            return null;
        }
        return ODataPath.Of("Dimensions").Key(parts[0]).Nav("Hierarchies").Key(parts[1]).Nav(collection).Key(parts[2]);
    }

    /// <summary>Elements or members, by their <c>UniqueName</c>s: null if any can't be made.</summary>
    internal static ODataPath[]? AllInHierarchy(string collection, IReadOnlyList<object?> uniqueNames)
    {
        var result = new ODataPath[uniqueNames.Count];
        for (var i = 0; i < result.Length; i++)
        {
            if ((uniqueNames[i] is string name ? InHierarchy(collection, name) : null) is not { } path)
            {
                return null;
            }
            result[i] = path;
        }
        return result;
    }
}

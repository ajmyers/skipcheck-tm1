// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;

namespace Skipcheck.Tm1.Client.OData;

/// <summary>A resource path relative to the service root, as the Java client's <c>ODataPath</c>:
/// <c>Cubes('Sales')/Views('Default')</c>. Immutable; names are checked and key values encoded.</summary>
public sealed partial class ODataPath : IEquatable<ODataPath>
{
    private readonly IReadOnlyList<string> _segments;

    private ODataPath(IReadOnlyList<string> segments) => _segments = segments;

    /// <summary>A path starting at an entity set, singleton or operation import, such as
    /// <c>Cubes</c>.</summary>
    /// <exception cref="ArgumentException"><paramref name="name"/> isn't an OData
    /// identifier.</exception>
    public static ODataPath Of(string name) => new([Identifier(name)]);

    /// <summary>The service's metadata document, <c>$metadata</c>: its CSDL XML. A path of its own,
    /// since <c>$metadata</c> isn't an identifier <see cref="Of"/> accepts.</summary>
    public static ODataPath Metadata() => new(["$metadata"]);

    /// <summary>The service's batch endpoint, <c>$batch</c>: a path of its own, as
    /// <see cref="Metadata"/>.</summary>
    public static ODataPath Batch() => new(["$batch"]);

    /// <summary>A path TM1 sent (an <c>@odata.id</c>, a <c>Location</c>), re-encoded segment by segment.</summary>
    public static ODataPath Link(string link)
    {
        ArgumentNullException.ThrowIfNull(link);
        if (link.Length == 0 || link.StartsWith('/') || link.Contains("://", StringComparison.Ordinal) || link.Contains('?'))
        {
            throw new ArgumentException("Not a relative path without a query: " + link);
        }
        return new(link.Split('/').Select(ODataEncoding.LinkSegment).ToList());
    }

    /// <summary>A function import called from the service root, such as <c>ModelCubes()</c> or
    /// <c>TailMessageLog(Top=10)</c>.</summary>
    /// <param name="name">The import's name, unqualified.</param>
    /// <param name="parameters">The parameter names and values, in order, as for
    /// <see cref="Function"/>.</param>
    public static ODataPath FunctionImport(string name, IReadOnlyDictionary<string, object?>? parameters = null) =>
        new([Call(Identifier(name), parameters ?? new Dictionary<string, object?>())]);

    /// <summary><c>Cubes</c> → <c>Cubes('Sales')</c>.</summary>
    public ODataPath Key(object value) => AppendToLast("(" + KeyValue(value) + ")");

    /// <summary>A compound key: <c>Edges(ParentName='a',ComponentName='b')</c>.</summary>
    public ODataPath Key(IReadOnlyDictionary<string, object?> parts)
    {
        if (parts.Count == 0)
        {
            throw new ArgumentException("A compound key needs at least one part");
        }
        return AppendToLast("(" + string.Join(",", parts.Select(p => Identifier(p.Key) + "=" + KeyValue(p.Value))) + ")");
    }

    /// <summary>Adds a navigation property or structural property segment, such as
    /// <c>Views</c>.</summary>
    public ODataPath Nav(string property) => Append(Identifier(property));

    /// <summary>Adds a type-cast segment, such as <c>tm1.NativeView</c>.</summary>
    public ODataPath Cast(string qualifiedType) => Append(Qualified(qualifiedType));

    /// <summary>Adds a bound action segment, such as <c>tm1.Reserve</c>. Actions are invoked with
    /// POST.</summary>
    public ODataPath Action(string qualifiedName) => Append(Qualified(qualifiedName));

    /// <summary>Adds a bound function call with literal parameters, such as
    /// <c>tm1.GetPartition(Begin=0,End=99)</c>. Use parameter aliases
    /// (<see cref="ODataQuery.Alias"/>) for complex or collection parameters.</summary>
    /// <param name="qualifiedName">The namespace-qualified function name.</param>
    /// <param name="parameters">The parameter names and values, in order; each value is written as
    /// an OData literal, or as the alias's name if it's an <see cref="Alias"/>.</param>
    public ODataPath Function(string qualifiedName, IReadOnlyDictionary<string, object?>? parameters = null) =>
        Append(Call(Qualified(qualifiedName), parameters ?? new Dictionary<string, object?>()));

    /// <summary>Adds <c>$value</c>: a property's or media entity's raw value.</summary>
    public ODataPath Value() => Append("$value");

    /// <summary>Adds <c>$count</c>: the number of items in a collection, as plain text.</summary>
    public ODataPath Count() => Append("$count");

    /// <summary>Adds <c>$ref</c>: references to entities rather than the entities
    /// themselves.</summary>
    public ODataPath Ref() => Append("$ref");

    /// <summary>The path as it reads, before percent-encoding.</summary>
    public override string ToString() => string.Join("/", _segments);

    /// <inheritdoc/>
    public bool Equals(ODataPath? other) => other is not null && other._segments.SequenceEqual(_segments);

    /// <inheritdoc/>
    public override bool Equals(object? obj) => obj is ODataPath p && Equals(p);

    /// <inheritdoc/>
    public override int GetHashCode() => ToString().GetHashCode(StringComparison.Ordinal);

    /// <summary>A parameter alias, <c>@p</c>: a function's structured argument, given as JSON in
    /// the query (<c>Fn(p=@p)?@p={…}</c>).</summary>
    public sealed partial record Alias
    {
        /// <summary>An alias.</summary>
        /// <param name="name"><c>@</c> followed by an identifier, such as <c>@p</c>.</param>
        /// <exception cref="ArgumentException"><paramref name="name"/> isn't one.</exception>
        public Alias(string name)
        {
            if (name is null || !name.StartsWith('@') || !IdentifierPattern().IsMatch(name[1..]))
            {
                throw new ArgumentException("A parameter alias is @ followed by an identifier: " + name);
            }
            Name = name;
        }

        /// <summary>The alias, with its <c>@</c>.</summary>
        public string Name { get; }
    }

    private ODataPath Append(string segment) => new(_segments.Append(segment).ToList());

    private ODataPath AppendToLast(string suffix)
    {
        if (_segments.Count == 0)
        {
            throw new InvalidOperationException("A key follows a collection");
        }
        var last = _segments[^1];
        if (last.EndsWith(')') || last.StartsWith('$'))
        {
            throw new InvalidOperationException($"Can't add a key to '{last}': a key follows a collection, once");
        }
        return new(_segments.Take(_segments.Count - 1).Append(last + suffix).ToList());
    }

    private static string KeyValue(object? value) =>
        value is string s ? "'" + ODataEncoding.PathValue(s.Replace("'", "''")) + "'" : ODataEncoding.PathValue(ODataLiterals.Of(value));

    private static string Call(string function, IReadOnlyDictionary<string, object?> parameters) =>
        function
        + "("
        + string.Join(",", parameters.Select(p => Identifier(p.Key) + "=" + (p.Value is Alias a ? a.Name : ODataEncoding.PathValue(ODataLiterals.Of(p.Value)))))
        + ")";

    private static string Identifier(string name)
    {
        if (name is null || !IdentifierPattern().IsMatch(name))
        {
            throw new ArgumentException($"Not an OData identifier: '{name}'. Key values go in Key(), not in a segment name.");
        }
        return name;
    }

    private static string Qualified(string name)
    {
        if (name is null || !QualifiedPattern().IsMatch(name))
        {
            throw new ArgumentException($"Not a qualified OData name: '{name}'");
        }
        return name;
    }

    [GeneratedRegex("^[A-Za-z_][A-Za-z0-9_]*$")]
    private static partial Regex IdentifierPattern();

    [GeneratedRegex(@"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$")]
    private static partial Regex QualifiedPattern();
}

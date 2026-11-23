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

    public static ODataPath Of(string name) => new([Identifier(name)]);

    public static ODataPath Metadata() => new(["$metadata"]);

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

    public ODataPath Nav(string property) => Append(Identifier(property));

    public ODataPath Cast(string qualifiedType) => Append(Qualified(qualifiedType));

    public ODataPath Action(string qualifiedName) => Append(Qualified(qualifiedName));

    public ODataPath Function(string qualifiedName, IReadOnlyDictionary<string, object?>? parameters = null) =>
        Append(Call(Qualified(qualifiedName), parameters ?? new Dictionary<string, object?>()));

    public ODataPath Value() => Append("$value");

    public ODataPath Count() => Append("$count");

    public ODataPath Ref() => Append("$ref");

    public override string ToString() => string.Join("/", _segments);

    public bool Equals(ODataPath? other) => other is not null && other._segments.SequenceEqual(_segments);

    public override bool Equals(object? obj) => obj is ODataPath p && Equals(p);

    public override int GetHashCode() => ToString().GetHashCode(StringComparison.Ordinal);

    /// <summary>A parameter alias, <c>@p</c>: a function's structured argument, given as JSON in
    /// the query (<c>Fn(p=@p)?@p={…}</c>).</summary>
    public sealed partial record Alias
    {
        public Alias(string name)
        {
            if (name is null || !name.StartsWith('@') || !IdentifierPattern().IsMatch(name[1..]))
            {
                throw new ArgumentException("A parameter alias is @ followed by an identifier: " + name);
            }
            Name = name;
        }

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

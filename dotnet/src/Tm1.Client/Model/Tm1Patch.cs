using System;
using System.Collections.Generic;
using System.Linq;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Model;

/// <summary>A write, as the Java model's patches (<c>CubePatch</c>): the properties set, explicit
/// nulls included, and <c>@odata.bind</c> references, in the order they were set. A subtype's patch
/// sends its <c>@odata.type</c> first. Never send a model read from TM1 back: it carries
/// server-computed properties.</summary>
public abstract class Tm1Patch : IEquatable<Tm1Patch>
{
    protected Tm1Patch(IReadOnlyDictionary<string, object?> values)
    {
        Values = new List<KeyValuePair<string, object?>>(values).AsReadOnly();
    }

    /// <summary>The properties set, by their names in TM1's JSON, in the order they were set.</summary>
    public IReadOnlyList<KeyValuePair<string, object?>> Values { get; }

    public bool Equals(Tm1Patch? other) =>
        other is not null
        && other.GetType() == GetType()
        && other.Values.Select(v => (v.Key, Text(v.Value))).SequenceEqual(Values.Select(v => (v.Key, Text(v.Value))));

    public override bool Equals(object? obj) => obj is Tm1Patch p && Equals(p);

    public override int GetHashCode() => string.Join(",", Values.Select(v => v.Key)).GetHashCode(StringComparison.Ordinal);

    public override string ToString() => GetType().Name + "{" + string.Join(", ", Values.Select(v => v.Key + "=" + Text(v.Value))) + "}";

    private static string Text(object? v) =>
        v is System.Collections.IEnumerable e and not string ? "[" + string.Join(",", e.Cast<object?>().Select(Text)) + "]" : v?.ToString() ?? "null";

    /// <summary>What every generated builder is made of.</summary>
    public abstract class BuilderBase<TPatch, TSelf>
        where TPatch : Tm1Patch
        where TSelf : BuilderBase<TPatch, TSelf>
    {
        private readonly Func<IReadOnlyDictionary<string, object?>, TPatch> _build;
        private readonly OrderedDictionary<string, object?> _values = new();

        protected BuilderBase(Func<IReadOnlyDictionary<string, object?>, TPatch> build, string? odataType)
        {
            _build = build;
            if (odataType is not null)
            {
                _values[ODataJson.Type] = odataType;
            }
        }

        protected TSelf Set(string name, object? value)
        {
            _values[name] = value is System.Collections.IEnumerable e and not string ? e.Cast<object?>().ToList() : value;
            return (TSelf)this;
        }

        protected TSelf Bind(string name, ODataPath target)
        {
            _values[name + ODataJson.BindSuffix] = target.ToString();
            return (TSelf)this;
        }

        protected TSelf BindAll(string name, IEnumerable<ODataPath> targets)
        {
            _values[name + ODataJson.BindSuffix] = targets.Select(t => t.ToString()).ToList();
            return (TSelf)this;
        }

        /// <summary>The patch, with what's been set so far.</summary>
        public TPatch Build() => _build(_values);
    }
}

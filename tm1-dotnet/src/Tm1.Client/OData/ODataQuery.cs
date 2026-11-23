using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;

namespace Skipcheck.Tm1.Client.OData;

/// <summary>OData query options: <c>$select</c>, <c>$expand</c> (with nested options),
/// <c>$filter</c>, <c>$orderby</c>, <c>$top</c>, <c>$skip</c>, <c>$count</c>, custom options such as
/// TM1's <c>!sandbox</c>, and parameter aliases. Immutable: each method returns a new query, as the
/// Java client's <c>ODataQuery</c>:
/// <code>ODataQuery.None().Select("Name", "Type").Expand("Hierarchies", h => h.Select("Name").Top(5))</code>
/// Options are written in a fixed order, and each value is percent-encoded once by
/// <see cref="ToQueryString"/>. Nested options inside <c>$expand</c> are separated by <c>;</c>.</summary>
public sealed partial class ODataQuery : IEquatable<ODataQuery>
{
    private static readonly ODataQuery Empty = new();

    private IReadOnlyList<string> _select = [];
    private IReadOnlyList<KeyValuePair<string, ODataQuery>> _expand = [];
    private string? _filter;
    private IReadOnlyList<string> _orderBy = [];
    private int? _top;
    private int? _skip;
    private bool? _count;
    private IReadOnlyList<KeyValuePair<string, string>> _custom = [];
    private IReadOnlyList<KeyValuePair<string, string>> _aliases = [];

    private ODataQuery() { }

    /// <summary>No options: the server's default response.</summary>
    public static ODataQuery None() => Empty;

    public ODataQuery Select(params string[] properties) =>
        With(q => q._select = [.. _select, .. properties.Select(p => p ?? throw new ArgumentNullException(nameof(properties)))]);

    public ODataQuery Select(params ODataProperty[] properties) => Select(properties.Select(p => p.Path).ToArray());

    public ODataQuery Expand(string navigation) => Expand(navigation, q => q);

    /// <summary>Expands a navigation property, with nested options applied to what's there for it already.</summary>
    public ODataQuery Expand(string navigation, Func<ODataQuery, ODataQuery> options)
    {
        ArgumentNullException.ThrowIfNull(navigation);
        var current = _expand.FirstOrDefault(e => e.Key == navigation).Value ?? Empty;
        var next = _expand.Where(e => e.Key != navigation).ToList();
        var index = _expand.ToList().FindIndex(e => e.Key == navigation);
        var item = new KeyValuePair<string, ODataQuery>(navigation, options(current));
        if (index < 0)
        {
            next.Add(item);
        }
        else
        {
            next.Insert(index, item);
        }
        return With(q => q._expand = next);
    }

    public ODataQuery Expand(ODataProperty navigation) => Expand(navigation.Path);

    public ODataQuery Expand(ODataProperty navigation, Func<ODataQuery, ODataQuery> options) => Expand(navigation.Path, options);

    public ODataQuery Filter(Filter filter) => With(q => q._filter = (filter ?? throw new ArgumentNullException(nameof(filter))).ToString());

    public ODataQuery OrderBy(string property) => With(q => q._orderBy = [.. _orderBy, property]);

    public ODataQuery OrderByDescending(string property) => With(q => q._orderBy = [.. _orderBy, property + " desc"]);

    public ODataQuery OrderBy(ODataProperty property) => OrderBy(property.Path);

    public ODataQuery OrderByDescending(ODataProperty property) => OrderByDescending(property.Path);

    public ODataQuery Top(int n) => n < 0 ? throw new ArgumentException("$top can't be negative: " + n) : With(q => q._top = n);

    public ODataQuery Skip(int n) => n < 0 ? throw new ArgumentException("$skip can't be negative: " + n) : With(q => q._skip = n);

    public ODataQuery WithCount() => With(q => q._count = true);

    /// <summary>A custom option, such as TM1's <c>!sandbox</c>.</summary>
    public ODataQuery Option(string name, string value)
    {
        if (name is null || !CustomPattern().IsMatch(name))
        {
            throw new ArgumentException("Not a valid custom query option name: " + name);
        }
        ArgumentNullException.ThrowIfNull(value);
        return With(q => q._custom = [.. _custom.Where(c => c.Key != name), new(name, value)]);
    }

    /// <summary>The value of a custom option, or null.</summary>
    public string? Option(string name) => _custom.FirstOrDefault(c => c.Key == name).Value;

    /// <summary>A parameter alias's value: <c>Fn(p=@p)</c> with <c>?@p=…</c>.</summary>
    public ODataQuery Alias(ODataPath.Alias alias, string literalOrJson) =>
        With(q =>
            q._aliases = [.. _aliases.Where(a => a.Key != alias.Name), new(alias.Name, literalOrJson ?? throw new ArgumentNullException(nameof(literalOrJson)))]
        );

    public bool IsEmpty() => SystemOptions().Count == 0 && _custom.Count == 0 && _aliases.Count == 0;

    internal IReadOnlyList<KeyValuePair<string, string>> CustomOptions => _custom;

    /// <summary>Every option, unencoded, in order.</summary>
    public IReadOnlyList<KeyValuePair<string, string>> Parameters() => [.. SystemOptions(), .. _custom, .. _aliases];

    /// <summary>The encoded query string, without <c>?</c>.</summary>
    public string ToQueryString() => string.Join("&", Parameters().Select(p => p.Key + "=" + ODataEncoding.QueryValue(p.Value)));

    public override string ToString() => string.Join("&", Parameters().Select(p => p.Key + "=" + p.Value));

    public bool Equals(ODataQuery? other) => other is not null && other.ToQueryString() == ToQueryString();

    public override bool Equals(object? obj) => obj is ODataQuery q && Equals(q);

    public override int GetHashCode() => ToQueryString().GetHashCode(StringComparison.Ordinal);

    private ODataQuery With(Action<ODataQuery> change)
    {
        var copy = (ODataQuery)MemberwiseClone();
        change(copy);
        return copy;
    }

    private List<KeyValuePair<string, string>> SystemOptions()
    {
        var options = new List<KeyValuePair<string, string>>();
        if (_select.Count > 0)
        {
            options.Add(new("$select", string.Join(",", _select)));
        }
        if (_expand.Count > 0)
        {
            options.Add(
                new(
                    "$expand",
                    string.Join(
                        ",",
                        _expand.Select(e =>
                        {
                            var inner = e.Value.SystemOptions();
                            return inner.Count == 0 ? e.Key : e.Key + "(" + string.Join(";", inner.Select(o => o.Key + "=" + o.Value)) + ")";
                        })
                    )
                )
            );
        }
        if (_filter is not null)
        {
            options.Add(new("$filter", _filter));
        }
        if (_orderBy.Count > 0)
        {
            options.Add(new("$orderby", string.Join(",", _orderBy)));
        }
        if (_top is { } top)
        {
            options.Add(new("$top", top.ToString(System.Globalization.CultureInfo.InvariantCulture)));
        }
        if (_skip is { } skip)
        {
            options.Add(new("$skip", skip.ToString(System.Globalization.CultureInfo.InvariantCulture)));
        }
        if (_count is { } count)
        {
            options.Add(new("$count", count ? "true" : "false"));
        }
        return options;
    }

    [GeneratedRegex(@"^[A-Za-z!][A-Za-z0-9_\-.!]*$")]
    private static partial Regex CustomPattern();
}

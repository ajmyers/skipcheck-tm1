using System;
using System.Linq;
using System.Text.RegularExpressions;

namespace Skipcheck.Tm1.Client.OData;

/// <summary>A <c>$filter</c> expression, built from <see cref="ODataProperty"/> comparisons and the
/// canonical functions TM1 supports, as the Java client's <c>Filter</c>. Every binary expression
/// is parenthesized, so precedence never depends on how a filter was composed. TM1 compares names
/// in <c>$filter</c> case- and space-sensitively, while keyed access ignores both: to find one
/// object by name, use its key.</summary>
public sealed partial class Filter : IEquatable<Filter>
{
    private readonly string _expression;

    private Filter(string expression) => _expression = expression;

    /// <summary>An expression written by hand, used as is.</summary>
    public static Filter Raw(string expression) => new(expression ?? throw new ArgumentNullException(nameof(expression)));

    /// <summary>All of the given filters, joined with <c>and</c>; the filter itself if there's only
    /// one.</summary>
    public static Filter And(params Filter[] filters) => Join("and", filters);

    /// <summary>Any of the given filters, joined with <c>or</c>; the filter itself if there's only
    /// one.</summary>
    public static Filter Or(params Filter[] filters) => Join("or", filters);

    /// <summary><c>not filter</c>.</summary>
    public static Filter Not(Filter filter) => new($"not({filter._expression})");

    /// <summary><c>startswith(property,'prefix')</c>.</summary>
    public static Filter StartsWith(ODataProperty property, string prefix) => Function("startswith", property, prefix);

    /// <summary><c>endswith(property,'suffix')</c>.</summary>
    public static Filter EndsWith(ODataProperty property, string suffix) => Function("endswith", property, suffix);

    /// <summary><c>contains(property,'text')</c>.</summary>
    public static Filter Contains(ODataProperty property, string text) => Function("contains", property, text);

    /// <summary><c>collection/any(v:condition)</c>: true if any member of a collection
    /// matches.</summary>
    /// <param name="collectionPath">The path to the collection, such as <c>Hierarchies</c>, used as
    /// is.</param>
    /// <param name="variable">The range variable's name, an OData identifier.</param>
    /// <param name="condition">Builds the condition from the range variable
    /// (<c>v.Nav("Name")</c>).</param>
    public static Filter Any(string collectionPath, string variable, Func<Variable, Filter> condition) => Lambda("any", collectionPath, variable, condition);

    /// <summary><c>collection/all(v:condition)</c>: true if every member of a collection
    /// matches.</summary>
    /// <param name="collectionPath">The path to the collection, such as <c>Hierarchies</c>, used as
    /// is.</param>
    /// <param name="variable">The range variable's name, an OData identifier.</param>
    /// <param name="condition">Builds the condition from the range variable.</param>
    public static Filter All(string collectionPath, string variable, Func<Variable, Filter> condition) => Lambda("all", collectionPath, variable, condition);

    /// <summary><c>property in (a,b,c)</c>, with each value written as an OData literal. Not every
    /// TM1 version supports <c>in</c>; <see cref="Or"/> of <c>eq</c>s always works.</summary>
    public static Filter In(ODataProperty property, params object?[] values)
    {
        if (values.Length == 0)
        {
            throw new ArgumentException("In() needs at least one value: an empty set matches nothing");
        }
        return new($"({property.Path} in ({string.Join(",", values.Select(ODataLiterals.Of))}))");
    }

    /// <summary>Both filters: <see cref="And"/>.</summary>
    public static Filter operator &(Filter a, Filter b) => And(a, b);

    /// <summary>Either filter: <see cref="Or"/>.</summary>
    public static Filter operator |(Filter a, Filter b) => Or(a, b);

    /// <summary>The negated filter: <see cref="Not"/>.</summary>
    public static Filter operator !(Filter a) => Not(a);

    internal static Filter Compare(ODataProperty property, string op, object? value) => new($"({property.Path} {op} {ODataLiterals.Of(value)})");

    /// <summary>The filter expression, before percent-encoding.</summary>
    public override string ToString() => _expression;

    /// <inheritdoc/>
    public bool Equals(Filter? other) => other is not null && other._expression == _expression;

    /// <inheritdoc/>
    public override bool Equals(object? obj) => obj is Filter f && Equals(f);

    /// <inheritdoc/>
    public override int GetHashCode() => _expression.GetHashCode(StringComparison.Ordinal);

    /// <summary>The range variable of <see cref="Any"/> and <see cref="All"/>.</summary>
    public sealed class Variable
    {
        private readonly string _name;

        internal Variable(string name) => _name = name;

        /// <summary>A path through the variable: <c>v/Name</c>.</summary>
        public ODataProperty Nav(string path) => new($"{_name}/{path}");

        /// <summary>The variable itself, for collections of primitive values: <c>v eq
        /// 'x'</c>.</summary>
        public ODataProperty Self() => new(_name);
    }

    private static Filter Join(string op, Filter[] filters)
    {
        if (filters.Length == 0)
        {
            throw new ArgumentException($"{op}() needs at least one filter");
        }
        return filters.Length == 1 ? filters[0] : new("(" + string.Join($" {op} ", filters.Select(f => f._expression)) + ")");
    }

    private static Filter Function(string name, ODataProperty property, string argument) => new($"{name}({property.Path},{ODataLiterals.String(argument)})");

    private static Filter Lambda(string kind, string collectionPath, string variable, Func<Variable, Filter> condition)
    {
        if (!IdentifierPattern().IsMatch(variable))
        {
            throw new ArgumentException("Not a valid range variable: " + variable);
        }
        return new($"{collectionPath}/{kind}({variable}:{condition(new Variable(variable))._expression})");
    }

    [GeneratedRegex("^[A-Za-z_][A-Za-z0-9_]*$")]
    private static partial Regex IdentifierPattern();
}

using System;
using System.Linq;
using System.Text.RegularExpressions;

namespace AjMyers.Tm1.Client.OData;

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

    public static Filter And(params Filter[] filters) => Join("and", filters);

    public static Filter Or(params Filter[] filters) => Join("or", filters);

    public static Filter Not(Filter filter) => new($"not({filter._expression})");

    public static Filter StartsWith(ODataProperty property, string prefix) => Function("startswith", property, prefix);

    public static Filter EndsWith(ODataProperty property, string suffix) => Function("endswith", property, suffix);

    public static Filter Contains(ODataProperty property, string text) => Function("contains", property, text);

    public static Filter Any(string collectionPath, string variable, Func<Variable, Filter> condition) => Lambda("any", collectionPath, variable, condition);

    public static Filter All(string collectionPath, string variable, Func<Variable, Filter> condition) => Lambda("all", collectionPath, variable, condition);

    public static Filter In(ODataProperty property, params object?[] values)
    {
        if (values.Length == 0)
        {
            throw new ArgumentException("In() needs at least one value: an empty set matches nothing");
        }
        return new($"({property.Path} in ({string.Join(",", values.Select(ODataLiterals.Of))}))");
    }

    public static Filter operator &(Filter a, Filter b) => And(a, b);

    public static Filter operator |(Filter a, Filter b) => Or(a, b);

    public static Filter operator !(Filter a) => Not(a);

    internal static Filter Compare(ODataProperty property, string op, object? value) => new($"({property.Path} {op} {ODataLiterals.Of(value)})");

    public override string ToString() => _expression;

    public bool Equals(Filter? other) => other is not null && other._expression == _expression;

    public override bool Equals(object? obj) => obj is Filter f && Equals(f);

    public override int GetHashCode() => _expression.GetHashCode(StringComparison.Ordinal);

    /// <summary>The range variable of <see cref="Any"/> and <see cref="All"/>.</summary>
    public sealed class Variable
    {
        private readonly string _name;

        internal Variable(string name) => _name = name;

        public ODataProperty Nav(string path) => new($"{_name}/{path}");

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

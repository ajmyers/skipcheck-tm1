namespace Skipcheck.Tm1.Client.OData;

/// <summary>A property path, for queries and filters: the generated models have one per property,
/// <c>Cube.Fields.Name</c> (the Java model's <c>Cube.NAME</c>). Comparisons make filters:
/// <c>Cube.Fields.Name.Eq("Sales")</c> is <c>(Name eq 'Sales')</c>.</summary>
public sealed record ODataProperty(string Path)
{
    /// <summary>A property path from text, with <c>/</c> between segments.</summary>
    public static ODataProperty Of(string path) => new(path);

    /// <summary><c>this eq value</c>, the value written as an OData literal; for null, use
    /// <see cref="IsNull"/>.</summary>
    public Filter Eq(object? value) => Filter.Compare(this, "eq", value);

    /// <summary><c>this ne value</c>, the value written as an OData literal.</summary>
    public Filter Ne(object? value) => Filter.Compare(this, "ne", value);

    /// <summary><c>this gt value</c>, the value written as an OData literal.</summary>
    public Filter Gt(object? value) => Filter.Compare(this, "gt", value);

    /// <summary><c>this ge value</c>, the value written as an OData literal.</summary>
    public Filter Ge(object? value) => Filter.Compare(this, "ge", value);

    /// <summary><c>this lt value</c>, the value written as an OData literal.</summary>
    public Filter Lt(object? value) => Filter.Compare(this, "lt", value);

    /// <summary><c>this le value</c>, the value written as an OData literal.</summary>
    public Filter Le(object? value) => Filter.Compare(this, "le", value);

    /// <summary><c>this eq null</c>.</summary>
    public Filter IsNull() => Filter.Compare(this, "eq", null);

    /// <summary>The path.</summary>
    public override string ToString() => Path;
}

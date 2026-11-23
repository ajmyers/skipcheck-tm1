namespace Skipcheck.Tm1.Client.OData;

/// <summary>A property path, for queries and filters: the generated models have one per property,
/// <c>Cube.Fields.Name</c> (the Java model's <c>Cube.NAME</c>). Comparisons make filters:
/// <c>Cube.Fields.Name.Eq("Sales")</c> is <c>(Name eq 'Sales')</c>.</summary>
public sealed record ODataProperty(string Path)
{
    public static ODataProperty Of(string path) => new(path);

    public Filter Eq(object? value) => Filter.Compare(this, "eq", value);

    public Filter Ne(object? value) => Filter.Compare(this, "ne", value);

    public Filter Gt(object? value) => Filter.Compare(this, "gt", value);

    public Filter Ge(object? value) => Filter.Compare(this, "ge", value);

    public Filter Lt(object? value) => Filter.Compare(this, "lt", value);

    public Filter Le(object? value) => Filter.Compare(this, "le", value);

    public Filter IsNull() => Filter.Compare(this, "eq", null);

    public override string ToString() => Path;
}

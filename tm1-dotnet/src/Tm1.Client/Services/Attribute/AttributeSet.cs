using System;
using AjMyers.Tm1.Client.OData;

namespace AjMyers.Tm1.Client.Services;

/// <summary>A set of attribute definitions: the attributes that one kind of object has, such as every
/// element of a hierarchy.</summary>
public sealed class AttributeSet
{
    private AttributeSet(ODataPath path)
    {
        Path = path;
    }

    internal ODataPath Path { get; }

    /// <summary>Dimensions' attributes: <c>/DimensionAttributes</c>.</summary>
    public static AttributeSet Dimensions() => new(ODataPath.Of("DimensionAttributes"));

    /// <summary>The attributes of a dimension's hierarchies: <c>Dimensions('d')/HierarchyAttributes</c>.</summary>
    public static AttributeSet Hierarchies(string dimension) => new(HierarchyService.DimensionPath(dimension).Nav("HierarchyAttributes"));

    /// <summary>The attributes of a hierarchy's elements: <c>…/Hierarchies('h')/ElementAttributes</c>.</summary>
    public static AttributeSet Elements(string dimension, string hierarchy) =>
        new(HierarchyService.HierarchyPath(dimension, hierarchy).Nav("ElementAttributes"));

    /// <summary>The attributes of a hierarchy's subsets: <c>…/Hierarchies('h')/SubsetAttributes</c>.</summary>
    public static AttributeSet Subsets(string dimension, string hierarchy) => new(HierarchyService.HierarchyPath(dimension, hierarchy).Nav("SubsetAttributes"));

    /// <summary>Cubes' attributes: <c>/CubeAttributes</c>.</summary>
    public static AttributeSet Cubes() => new(ODataPath.Of("CubeAttributes"));

    /// <summary>The attributes of a cube's views: <c>Cubes('c')/ViewAttributes</c>. TM1 (11.8) refuses to
    /// create one (400, "no public views") until the cube has a public <em>native</em> view; an MDX view
    /// doesn't count.</summary>
    public static AttributeSet Views(string cube) =>
        new(ODataPath.Of("Cubes").Key(cube ?? throw new ArgumentNullException(nameof(cube))).Nav("ViewAttributes"));

    /// <summary>Processes' attributes: <c>/ProcessAttributes</c>.</summary>
    public static AttributeSet Processes() => new(ODataPath.Of("ProcessAttributes"));

    /// <summary>Chores' attributes: <c>/ChoreAttributes</c>.</summary>
    public static AttributeSet Chores() => new(ODataPath.Of("ChoreAttributes"));

    public override string ToString() => Path.ToString();
}

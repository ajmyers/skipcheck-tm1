// SPDX-License-Identifier: Apache-2.0

using System;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>An object whose attribute values can be translated: it has <c>LocalizedAttributes</c>.</summary>
public sealed class AttributeOwner
{
    private AttributeOwner(ODataPath path)
    {
        Path = path;
    }

    internal ODataPath Path { get; }

    /// <summary>A dimension.</summary>
    public static AttributeOwner Dimension(string dimension) => new(HierarchyService.DimensionPath(dimension));

    /// <summary>A hierarchy.</summary>
    public static AttributeOwner Hierarchy(string dimension, string hierarchy) => new(HierarchyService.HierarchyPath(dimension, hierarchy));

    /// <summary>An element.</summary>
    public static AttributeOwner Element(string dimension, string hierarchy, string element) =>
        new(HierarchyService.ElementPath(dimension, hierarchy, element));

    /// <summary>A member.</summary>
    public static AttributeOwner Member(string dimension, string hierarchy, string member) =>
        new(HierarchyService.HierarchyPath(dimension, hierarchy).Nav("Members").Key(Required(member, nameof(member))));

    /// <summary>A subset.</summary>
    public static AttributeOwner Subset(string dimension, string hierarchy, SubsetScope scope, string subset) =>
        new(HierarchyService.HierarchyPath(dimension, hierarchy).Nav(scope.Navigation()).Key(Required(subset, nameof(subset))));

    /// <summary>A cube.</summary>
    public static AttributeOwner Cube(string cube) => new(ODataPath.Of("Cubes").Key(Required(cube, nameof(cube))));

    /// <summary>A view, in <paramref name="scope"/>: <see cref="ViewScope.Public"/> (<c>Views</c>) or
    /// <see cref="ViewScope.Private"/> (<c>PrivateViews</c>).</summary>
    public static AttributeOwner View(string cube, ViewScope scope, string view) =>
        new(ODataPath.Of("Cubes").Key(Required(cube, nameof(cube))).Nav(scope.Navigation()).Key(Required(view, nameof(view))));

    /// <summary>A process.</summary>
    public static AttributeOwner Process(string process) => new(ODataPath.Of("Processes").Key(Required(process, nameof(process))));

    /// <summary>A chore.</summary>
    public static AttributeOwner Chore(string chore) => new(ODataPath.Of("Chores").Key(Required(chore, nameof(chore))));

    /// <inheritdoc/>
    public override string ToString() => Path.ToString();

    private static string Required(string? value, string name) => value ?? throw new ArgumentNullException(name);
}

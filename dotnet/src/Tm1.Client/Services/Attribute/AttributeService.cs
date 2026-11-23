using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Attribute definitions: which attributes a kind of object has, and their types, for each
/// <see cref="AttributeSet"/>. Creating one adds it to every object of that kind; the values are cells
/// of the matching <c>}…Attributes</c> control cube.
/// <para>Add an attribute to every element of a hierarchy:</para>
/// <code>
/// await tm1.Attributes.CreateAsync(AttributeSet.Elements("Region", "Region"),
///     AttributeDefinitionPatch.Builder().Name("Manager").Type(AttributeType.String).Build());
/// </code>
/// <para>List the attributes cubes have:</para>
/// <code>
/// await foreach (var a in tm1.Attributes.ListAsync(AttributeSet.Cubes())) { … }
/// </code>
/// </summary>
public sealed class AttributeService : Tm1Service
{
    /// <summary>A service that sends its requests through <paramref name="session"/>.</summary>
    public AttributeService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET</c> the set's attributes, streamed.</summary>
    [Tm1Endpoint("EntitySet DimensionAttributes")]
    [Tm1Endpoint("Navigation Dimension/HierarchyAttributes")]
    [Tm1Endpoint("Navigation Hierarchy/ElementAttributes")]
    [Tm1Endpoint("Navigation Hierarchy/SubsetAttributes")]
    [Tm1Endpoint("EntitySet CubeAttributes")]
    [Tm1Endpoint("Navigation Cube/ViewAttributes")]
    [Tm1Endpoint("EntitySet ProcessAttributes")]
    [Tm1Endpoint("EntitySet ChoreAttributes")]
    public IAsyncEnumerable<AttributeDefinition> ListAsync(AttributeSet set, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<AttributeDefinition>(Require(set, nameof(set)).Path, query), cancellationToken);

    /// <summary><c>GET</c> one attribute of the set, or a <c>Tm1NotFoundException</c> if there's none.</summary>
    [Tm1Endpoint("EntitySet DimensionAttributes")]
    [Tm1Endpoint("Navigation Dimension/HierarchyAttributes")]
    [Tm1Endpoint("Navigation Hierarchy/ElementAttributes")]
    [Tm1Endpoint("Navigation Hierarchy/SubsetAttributes")]
    [Tm1Endpoint("EntitySet CubeAttributes")]
    [Tm1Endpoint("Navigation Cube/ViewAttributes")]
    [Tm1Endpoint("EntitySet ProcessAttributes")]
    [Tm1Endpoint("EntitySet ChoreAttributes")]
    public Task<AttributeDefinition> GetAsync(AttributeSet set, string name, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<AttributeDefinition>(Require(set, nameof(set)).Path.Key(Require(name, nameof(name))), query), cancellationToken);

    /// <summary><c>POST</c>: adds an attribute, its name and type, to every object of the set's kind;
    /// returns the attribute as created.</summary>
    [Tm1Endpoint("EntitySet DimensionAttributes")]
    [Tm1Endpoint("Navigation Dimension/HierarchyAttributes")]
    [Tm1Endpoint("Navigation Hierarchy/ElementAttributes")]
    [Tm1Endpoint("Navigation Hierarchy/SubsetAttributes")]
    [Tm1Endpoint("EntitySet CubeAttributes")]
    [Tm1Endpoint("Navigation Cube/ViewAttributes")]
    [Tm1Endpoint("EntitySet ProcessAttributes")]
    [Tm1Endpoint("EntitySet ChoreAttributes")]
    public Task<AttributeDefinition> CreateAsync(AttributeSet set, AttributeDefinitionPatch attribute, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Post<AttributeDefinition>(Require(set, nameof(set)).Path, Require(attribute, nameof(attribute))), cancellationToken);

    /// <summary><c>DELETE</c>: removes an attribute, with its values, from every object of the set's kind.</summary>
    [Tm1Endpoint("EntitySet DimensionAttributes")]
    [Tm1Endpoint("Navigation Dimension/HierarchyAttributes")]
    [Tm1Endpoint("Navigation Hierarchy/ElementAttributes")]
    [Tm1Endpoint("Navigation Hierarchy/SubsetAttributes")]
    [Tm1Endpoint("EntitySet CubeAttributes")]
    [Tm1Endpoint("Navigation Cube/ViewAttributes")]
    [Tm1Endpoint("EntitySet ProcessAttributes")]
    [Tm1Endpoint("EntitySet ChoreAttributes")]
    public Task DeleteAsync(AttributeSet set, string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(Require(set, nameof(set)).Path.Key(Require(name, nameof(name)))), cancellationToken);
}

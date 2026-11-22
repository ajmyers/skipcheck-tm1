using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Member sets: the results of MDX set expressions, as a single axis of tuples, kept on the
/// server in <c>/MemberSets</c> and keyed by an ordinal TM1 assigns (1, 2, … within the session).
/// <para>Like a cellset, a member set stays until it's deleted or the session ends. TM1 (11.8) lists
/// none, though each reads by its ordinal: keep the ordinals <see cref="ExecuteAsync"/> returns, and
/// delete what you're done with.</para>
/// <para>Evaluate a set expression and read its members' names:</para>
/// <code>
/// var set = await tm1.MemberSets.ExecuteAsync("{TM1SUBSETALL([Region].[Region])}", ODataQuery.None().Expand("Tuples", t => t.Expand("Members", m => m.Select("Name"))));
/// await tm1.MemberSets.DeleteAsync(set.Ordinal!.Value);
/// </code>
/// </summary>
public sealed class MemberSetService : Tm1Service
{
    private static readonly ODataPath MemberSets = ODataPath.Of("MemberSets");

    public MemberSetService(Tm1Session session)
        : base(session)
    {
    }

    /// <summary><c>ExecuteMDXSetExpression</c>: evaluates an MDX set expression, such as
    /// <c>{TM1SUBSETALL([Region].[Region])}</c>, keeping the result as a member set, with the
    /// <c>Ordinal</c> that keys it in <c>MemberSets</c>. Expand <c>Tuples($expand=Members($select=Name))</c>
    /// to get its members in the same round trip. Needs schema 11.1.0.</summary>
    [Tm1Endpoint("ActionImport ExecuteMDXSetExpression")]
    public Task<CellsetAxis> ExecuteAsync(string mdx, ODataQuery? query = null, CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.ExecuteMdxSetExpression;
        var request = Tm1Request.Post<CellsetAxis>(ODataPath.Of(operation.ImportName!), new Dictionary<string, object?> { ["MDX"] = Require(mdx, nameof(mdx)) })
            .WithQuery(query).Requires(operation.Capability());
        return Session.ExecuteAsync(request, cancellationToken);
    }

    /// <summary><c>GET MemberSets</c>, streamed. TM1 (11.8) lists none, though each reads by its ordinal
    /// with <see cref="GetAsync"/>.</summary>
    [Tm1Endpoint("EntitySet MemberSets")]
    public IAsyncEnumerable<CellsetAxis> ListAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<CellsetAxis>(MemberSets, query), cancellationToken);

    /// <summary><c>GET MemberSets(n)</c>: the set, or a <c>Tm1NotFoundException</c> if it's gone.</summary>
    [Tm1Endpoint("EntitySet MemberSets")]
    public Task<CellsetAxis> GetAsync(int ordinal, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<CellsetAxis>(MemberSets.Key(ordinal), query), cancellationToken);

    /// <summary><c>DELETE MemberSets(n)</c>: frees the set on the server.</summary>
    [Tm1Endpoint("EntitySet MemberSets")]
    public Task DeleteAsync(int ordinal, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(MemberSets.Key(ordinal)), cancellationToken);
}

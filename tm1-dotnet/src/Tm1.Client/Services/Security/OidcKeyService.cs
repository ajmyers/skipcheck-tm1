using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>The OpenID Connect signing keys TM1 has cached, for servers that check OIDC tokens
/// themselves. Removing a key makes TM1 fetch it from the provider again, as after the provider
/// rotates its keys. On a server that checks no tokens, the cache is empty.
/// <para>Refresh the cache after the provider rotates its keys:</para>
/// <code>await tm1.OidcKeys.RemoveAsync(await tm1.OidcKeys.KeysAsync().ToListAsync());</code>
/// </summary>
public sealed class OidcKeyService : Tm1Service
{
    public OidcKeyService(Tm1Session session)
        : base(session)
    {
    }

    /// <summary><c>GetOIDCKeys()</c>, streamed: the IDs of the cached keys.</summary>
    [Tm1Endpoint("FunctionImport GetOIDCKeys")]
    public IAsyncEnumerable<string> KeysAsync(CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.GetOidcKeys;
        return Session.StreamAsync(Tm1Request.List<string>(ODataPath.FunctionImport(operation.ImportName!), null)
            .Requires(operation.Capability()), cancellationToken);
    }

    /// <summary><c>RemoveOIDCKeyFromCache</c>: drops keys from the cache. A key TM1 hasn't cached fails
    /// the call with a <c>Tm1NotFoundException</c>: TM1 answers 400 with <c>ObjectNotFound</c> (code
    /// 55), which the client reads as not found. An empty list does nothing.</summary>
    [Tm1Endpoint("ActionImport RemoveOIDCKeyFromCache")]
    public Task RemoveAsync(IReadOnlyList<string> keyIds, CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.RemoveOidcKeyFromCache;
        var body = new Dictionary<string, object?> { ["KeyId"] = Require(keyIds, nameof(keyIds)).ToList() };
        return Session.ExecuteAsync(Tm1Request.Post(ODataPath.Of(operation.ImportName!), body)
            .Requires(operation.Capability()), cancellationToken);
    }
}

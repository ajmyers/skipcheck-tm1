using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>SQL data sources: <c>/SQLDataSources</c>, the ODBC sources the server knows, which a
/// process can read from. Read only.</summary>
public sealed class SqlDataSourceService : Tm1Service
{
    private static readonly ODataPath Sources = ODataPath.Of("SQLDataSources");

    public SqlDataSourceService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET SQLDataSources</c>, streamed.</summary>
    [Tm1Endpoint("EntitySet SQLDataSources")]
    public IAsyncEnumerable<SQLDataSource> ListAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<SQLDataSource>(Sources, query), cancellationToken);

    /// <summary><c>GET SQLDataSources('name')</c>.</summary>
    [Tm1Endpoint("EntitySet SQLDataSources")]
    public Task<SQLDataSource> GetAsync(string name, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<SQLDataSource>(Sources.Key(Require(name, nameof(name))), query), cancellationToken);
}

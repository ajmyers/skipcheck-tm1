using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>The ODBC data sources on TM1's host, which processes can read from (<c>DataSource.Type</c> <c>ODBC</c>). They're the host's ODBC configuration, not TM1's: read only here.</summary>
public sealed class SqlDataSourceService : Tm1Service
{
    public SqlDataSourceService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

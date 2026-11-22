using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Drillthrough: from a cell to the detail behind it, through a drillthrough process (<code>}Drill_…</code>). A cube drillthrough returns a cellset, of a view the process returns; a relational one returns rows from an ODBC source, directly or kept as a <c>Rowset</c>.</summary>
public sealed class DrillthroughService : Tm1Service
{
    public DrillthroughService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

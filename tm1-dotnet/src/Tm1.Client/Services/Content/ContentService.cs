using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>The contents tree, where Planning Analytics keeps applications: folders, documents, links, and references to cubes, dimensions, subsets, views, processes and chores. Entries are addressed by <c>ContentPath</c>.</summary>
public sealed class ContentService : Tm1Service
{
    public ContentService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

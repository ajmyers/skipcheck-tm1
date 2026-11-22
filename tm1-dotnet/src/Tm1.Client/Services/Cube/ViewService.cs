using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Views: a cube's public and private views (<c>ViewScope</c>), native (<c>NativeView</c>: subsets on columns, rows and titles) or MDX (<c>MDXView</c>). They're read as the sealed <c>View</c>, so each comes back as its own type.</summary>
public sealed class ViewService : Tm1Service
{
    public ViewService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

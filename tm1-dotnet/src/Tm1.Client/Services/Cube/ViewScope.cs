namespace Skipcheck.Tm1.Client.Services;

/// <summary>Who sees a view, and so which of a cube's collections holds it.</summary>
public enum ViewScope
{
    /// <summary>Everyone with access to the cube: <c>Views</c>.</summary>
    Public,

    /// <summary>Only the user who made it: <c>PrivateViews</c>.</summary>
    Private,
}

/// <summary>Java's <c>ViewScope.navigation()</c>.</summary>
public static class ViewScopeExtensions
{
    /// <summary>The cube's navigation property holding views of this scope.</summary>
    public static string Navigation(this ViewScope scope) => scope == ViewScope.Private ? "PrivateViews" : "Views";
}

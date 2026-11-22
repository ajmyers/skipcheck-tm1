namespace AjMyers.Tm1.Client.Services;

/// <summary>Who sees a subset, and so which of a hierarchy's collections holds it, as the Java
/// client's <c>SubsetScope</c>; <see cref="SubsetScopes.Navigation"/> names the collection.</summary>
public enum SubsetScope
{
    /// <summary>Everyone: <c>Hierarchies('h')/Subsets</c>.</summary>
    Public,

    /// <summary>Only the user who made it: <c>Hierarchies('h')/PrivateSubsets</c>.</summary>
    Private,

    /// <summary>Only this session, gone when it ends: <c>Hierarchies('h')/SessionSubsets</c>.</summary>
    Session,
}

/// <summary>What Java's <c>SubsetScope</c> methods are, for the C# enum.</summary>
public static class SubsetScopes
{
    /// <summary>The hierarchy's navigation property holding subsets of this scope, such as <c>PrivateSubsets</c>.</summary>
    public static string Navigation(this SubsetScope scope) => scope switch
    {
        SubsetScope.Public => "Subsets",
        SubsetScope.Private => "PrivateSubsets",
        SubsetScope.Session => "SessionSubsets",
        _ => throw new System.ArgumentOutOfRangeException(nameof(scope)),
    };
}

using System;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Who sees an entry, and so which of a folder's collections holds it.</summary>
public enum ContentScope
{
    /// <summary>Everyone: <c>…/Contents('id')</c>.</summary>
    Public,

    /// <summary>Only the user who made it: <c>…/PrivateContents('id')</c>.</summary>
    Private,
}

/// <summary>What a <see cref="ContentScope"/> means in a path.</summary>
public static class ContentScopeExtensions
{
    /// <summary>The folder's navigation property holding entries of this scope, such as <c>PrivateContents</c>.</summary>
    public static string Navigation(this ContentScope scope) =>
        scope switch
        {
            ContentScope.Public => "Contents",
            ContentScope.Private => "PrivateContents",
            _ => throw new ArgumentOutOfRangeException(nameof(scope), scope, null),
        };
}

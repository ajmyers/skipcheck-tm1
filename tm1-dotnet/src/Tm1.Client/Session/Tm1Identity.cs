using System;
using Skipcheck.Tm1.Client.Auth;

namespace Skipcheck.Tm1.Client.Session;

/// <summary>Who a pooled session acts as, as the Java client's <c>Tm1Identity</c>: the service
/// account itself, the service account impersonating a user (<c>TM1-Impersonate</c>), or a user
/// logging in with their own credentials. Two identities are equal when they're of the same kind
/// and name TM1's way: case and spaces don't count, so <c>Jane Doe</c> and <c>janedoe</c> share a
/// session.</summary>
public sealed class Tm1Identity : IEquatable<Tm1Identity>
{
    private static readonly Tm1Identity ServiceAccountIdentity = new(IdentityKind.ServiceAccount, "", null);

    private readonly string _comparableName;

    /// <summary>How the session logs in.</summary>
    public enum IdentityKind
    {
        /// <summary>As the pool's own credentials.</summary>
        ServiceAccount,

        /// <summary>As the pool's credentials, impersonating a user.</summary>
        Impersonating,

        /// <summary>With the user's own credentials.</summary>
        OwnCredentials,
    }

    private Tm1Identity(IdentityKind kind, string name, Tm1Authenticator? authenticator)
    {
        Kind = kind;
        Name = name;
        Authenticator = authenticator;
        _comparableName = UserNames.Comparable(name);
    }

    /// <summary>The pool's service account itself.</summary>
    public static Tm1Identity ServiceAccount() => ServiceAccountIdentity;

    /// <summary>The service account impersonating <paramref name="user"/>, which needs TM1 to allow
    /// it to impersonate (an admin, or the user's <c>Impersonation</c> right). TM1 matches the name
    /// only in printable ASCII.</summary>
    public static Tm1Identity Impersonating(string user) => new(IdentityKind.Impersonating, UserNames.CheckImpersonated(user), null);

    /// <summary>A user with their own credentials; <paramref name="name"/> tells identities apart.</summary>
    public static Tm1Identity WithCredentials(string name, Tm1Authenticator authenticator)
    {
        ArgumentNullException.ThrowIfNull(name);
        if (string.IsNullOrWhiteSpace(name))
        {
            throw new ArgumentException("An identity's name must not be blank");
        }
        return new(IdentityKind.OwnCredentials, name, authenticator ?? throw new ArgumentNullException(nameof(authenticator)));
    }

    public IdentityKind Kind { get; }

    /// <summary>The user's name, as given (empty for the service account).</summary>
    public string Name { get; }

    /// <summary>The user's own credentials, for <see cref="IdentityKind.OwnCredentials"/>.</summary>
    public Tm1Authenticator? Authenticator { get; }

    /// <summary>The pool's session settings, changed to log in as this identity.</summary>
    internal Tm1SessionBuilder ApplyTo(Tm1SessionBuilder settings) =>
        Kind switch
        {
            IdentityKind.ServiceAccount => settings,
            IdentityKind.Impersonating => settings.Impersonate(Name),
            _ => settings.WithoutImpersonation().Authenticator(Authenticator!),
        };

    public bool Equals(Tm1Identity? other) =>
        ReferenceEquals(this, other) || (other is not null && Kind == other.Kind && _comparableName == other._comparableName);

    public override bool Equals(object? obj) => Equals(obj as Tm1Identity);

    public override int GetHashCode() => HashCode.Combine(Kind, _comparableName);

    public override string ToString() =>
        Kind switch
        {
            IdentityKind.ServiceAccount => "Tm1Identity[service account]",
            IdentityKind.Impersonating => $"Tm1Identity[impersonating {Name}]",
            _ => $"Tm1Identity[{Name}]",
        };
}

/// <summary>TM1's user names: compared without case or spaces, and impersonated only in printable ASCII.</summary>
internal static class UserNames
{
    public static string CheckImpersonated(string user)
    {
        ArgumentNullException.ThrowIfNull(user);
        if (string.IsNullOrWhiteSpace(user))
        {
            throw new ArgumentException("The user to impersonate is blank, which TM1 would take as no impersonation at all");
        }
        foreach (var c in user)
        {
            if (c < 0x20 || c > 0x7e)
            {
                throw new ArgumentException(
                    $"TM1 can't impersonate '{user}': it doesn't match user names with characters other than printable ASCII through TM1-Impersonate"
                );
            }
        }
        return user;
    }

    public static string Comparable(string user) => user.Replace(" ", "", StringComparison.Ordinal).ToLowerInvariant();
}

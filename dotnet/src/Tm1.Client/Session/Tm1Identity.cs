// SPDX-License-Identifier: Apache-2.0

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
    /// it to impersonate (an admin, or the user's <c>Impersonation</c> right); see
    /// <see cref="Tm1SessionBuilder.Impersonate"/>.</summary>
    /// <exception cref="ArgumentException"><paramref name="user"/> is blank or has a control character.</exception>
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

    /// <summary>What kind of identity this is.</summary>
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
            _ => settings.WithoutImpersonation().Authenticator(Authenticator!), // own credentials: WithCredentials checks it has them
        };

    /// <inheritdoc/>
    public bool Equals(Tm1Identity? other) =>
        ReferenceEquals(this, other) || (other is not null && Kind == other.Kind && _comparableName == other._comparableName);

    /// <inheritdoc/>
    public override bool Equals(object? obj) => Equals(obj as Tm1Identity);

    /// <inheritdoc/>
    public override int GetHashCode() => HashCode.Combine(Kind, _comparableName);

    /// <inheritdoc/>
    public override string ToString() =>
        Kind switch
        {
            IdentityKind.ServiceAccount => "Tm1Identity[service account]",
            IdentityKind.Impersonating => $"Tm1Identity[impersonating {Name}]",
            _ => $"Tm1Identity[{Name}]",
        };
}

/// <summary>TM1's user names: compared without case or spaces.</summary>
internal static class UserNames
{
    /// <summary><paramref name="user"/>, if it can be impersonated: not blank, which TM1 would take as no
    /// impersonation and run as the logged-in user, and without control characters, which can't go
    /// in a header. Any other character is fine: TM1 (11.8) reads the header as UTF-8, and the
    /// client sends it so (<see cref="Tm1Session.Utf8Headers"/>).</summary>
    public static string CheckImpersonated(string user)
    {
        ArgumentNullException.ThrowIfNull(user);
        if (string.IsNullOrWhiteSpace(user))
        {
            throw new ArgumentException("The user to impersonate is blank, which TM1 would take as no impersonation at all");
        }
        foreach (var c in user)
        {
            if (char.IsControl(c))
            {
                throw new ArgumentException("The user to impersonate has a control character in its name, which can't be sent in a header");
            }
        }
        return user;
    }

    public static string Comparable(string user) => user.Replace(" ", "", StringComparison.Ordinal).ToLowerInvariant();
}

// SPDX-License-Identifier: Apache-2.0

using System;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Skipcheck.Tm1.Client.Auth;

/// <summary>How a session logs in: the <c>Authorization</c> header of its first request, as the
/// Java client's <c>Tm1Authenticator</c>. TM1 answers with a <c>TM1SessionId</c> cookie, which
/// later requests send instead.
///
/// <para>A <c>Tm1SessionPool</c> keeps one session for each set of a user's own credentials, and
/// tells them apart with <see cref="object.Equals(object)"/>. The authenticators here that hold
/// their credentials (<see cref="Basic"/>, <see cref="CamNamespace"/>,
/// <see cref="CamPassport(string)"/>, <see cref="CognosLogon"/>) are equal when the credentials are.
/// One that asks a supplier (<see cref="CamPassport(Func{CancellationToken, ValueTask{string}})"/>,
/// <see cref="CognosIdToken"/>) is only equal to itself, so keep it and pass the same one each
/// time; so is your own, unless it defines <c>Equals</c>.</para></summary>
public abstract class Tm1Authenticator
{
    /// <summary>The header to log in with; asked for at each login.</summary>
    public abstract ValueTask<string> AuthorizationAsync(CancellationToken cancellationToken = default);

    /// <summary>Who logs in, without secrets, for logs and errors.</summary>
    public abstract string Describe();

    /// <inheritdoc/>
    public override string ToString() => $"Tm1Authenticator[{Describe()}]";

    /// <summary>TM1's own security (modes 1 and 2).</summary>
    public static Tm1Authenticator Basic(string user, string? password) =>
        new Fixed("Basic " + Base64($"{user ?? throw new ArgumentNullException(nameof(user))}:{password ?? ""}"), "Basic " + user);

    /// <summary>CAM security (modes 4 and 5), with a user in a namespace.</summary>
    public static Tm1Authenticator CamNamespace(string user, string password, string @namespace)
    {
        ArgumentNullException.ThrowIfNull(user);
        ArgumentNullException.ThrowIfNull(password);
        ArgumentNullException.ThrowIfNull(@namespace);
        return new Fixed("CAMNamespace " + Base64($"{user}:{password}:{@namespace}"), $"CAMNamespace {@namespace}/{user}");
    }

    /// <summary>CAM security with one CAM passport the application already holds, such as the one a
    /// signed-in user's request carries: <c>CAMPassport &lt;passport&gt;</c>. The passport isn't
    /// renewed: once it expires, a login with it is refused. Two of these are equal when their
    /// passports are, so a <c>Tm1SessionPool</c> gives a user's renewed passport a session of its own.</summary>
    public static Tm1Authenticator CamPassport(string passport) =>
        new Fixed("CAMPassport " + (passport ?? throw new ArgumentNullException(nameof(passport))), "CAMPassport");

    /// <summary>CAM security with a passport, fetched at each login (a passport expires).</summary>
    public static Tm1Authenticator CamPassport(Func<CancellationToken, ValueTask<string>> passport) =>
        new Passport(passport ?? throw new ArgumentNullException(nameof(passport)));

    /// <summary>CAM security with a passport the client gets from Cognos itself, logging on with a
    /// user name and password (<see cref="CognosPassports.Password"/>): a fresh passport for each
    /// login. Unlike <see cref="CamNamespace"/>, TM1 never sees the password, only Cognos does.</summary>
    /// <param name="dispatcher">The Cognos dispatcher's address, such as <c>http://cognos:9300</c>.</param>
    /// <param name="namespace">The namespace's ID, such as <c>LDAP</c>.</param>
    /// <param name="user">The user name.</param>
    /// <param name="password">The password.</param>
    /// <param name="handler">The handler to reach Cognos through; null for the client's own.</param>
    public static Tm1Authenticator CognosLogon(
        string dispatcher,
        string @namespace,
        string user,
        string password,
        System.Net.Http.HttpMessageHandler? handler = null
    ) =>
        new Described(
            CamPassport(CognosPassports.Password(dispatcher, @namespace, user, password, handler)),
            $"CAMPassport from Cognos {@namespace}/{user}",
            [dispatcher, @namespace, user, password, handler]
        );

    /// <summary>CAM security with a passport the client gets from Cognos for an OpenID Connect ID
    /// token it already holds (<see cref="CognosPassports.IdToken"/>): a fresh passport for each login.</summary>
    /// <param name="dispatcher">The Cognos dispatcher's address, such as <c>http://cognos:9300</c>.</param>
    /// <param name="namespace">The OIDC namespace's ID, such as <c>OIDC</c>.</param>
    /// <param name="idToken">Supplies a current ID token; asked at each login.</param>
    /// <param name="handler">The handler to reach Cognos through; null for the client's own.</param>
    public static Tm1Authenticator CognosIdToken(
        string dispatcher,
        string @namespace,
        Func<CancellationToken, ValueTask<string>> idToken,
        System.Net.Http.HttpMessageHandler? handler = null
    ) =>
        new Described(
            CamPassport(CognosPassports.IdToken(dispatcher, @namespace, idToken, handler)),
            $"CAMPassport from Cognos {@namespace} (ID token)",
            [dispatcher, @namespace, idToken, handler]
        );

    private static string Base64(string text) => Convert.ToBase64String(Encoding.UTF8.GetBytes(text));

    /// <summary>Credentials the authenticator holds. Equal when the credentials are.</summary>
    private sealed class Fixed(string value, string description) : Tm1Authenticator
    {
        private readonly string _value = value;

        public override ValueTask<string> AuthorizationAsync(CancellationToken cancellationToken = default) => ValueTask.FromResult(_value);

        public override string Describe() => description;

        public override bool Equals(object? obj) => ReferenceEquals(this, obj) || (obj is Fixed other && _value == other._value);

        public override int GetHashCode() => _value.GetHashCode(StringComparison.Ordinal);
    }

    /// <summary>Another authenticator under its own description: the ones that ask Cognos. Equal when
    /// they were made from the same address, namespace and credentials (and handler, if one was
    /// given), which is <paramref name="madeFrom"/>.</summary>
    private sealed class Described(Tm1Authenticator inner, string description, object?[] madeFrom) : Tm1Authenticator
    {
        private readonly object?[] _madeFrom = madeFrom;

        public override ValueTask<string> AuthorizationAsync(CancellationToken cancellationToken = default) => inner.AuthorizationAsync(cancellationToken);

        public override string Describe() => description;

        public override bool Equals(object? obj) => ReferenceEquals(this, obj) || (obj is Described other && _madeFrom.SequenceEqual(other._madeFrom));

        public override int GetHashCode()
        {
            var hash = new HashCode();
            foreach (var part in _madeFrom)
            {
                hash.Add(part);
            }
            return hash.ToHashCode();
        }
    }

    private sealed class Passport(Func<CancellationToken, ValueTask<string>> passport) : Tm1Authenticator
    {
        public override async ValueTask<string> AuthorizationAsync(CancellationToken cancellationToken = default) =>
            "CAMPassport " + await passport(cancellationToken).ConfigureAwait(false);

        public override string Describe() => "CAMPassport";
    }
}

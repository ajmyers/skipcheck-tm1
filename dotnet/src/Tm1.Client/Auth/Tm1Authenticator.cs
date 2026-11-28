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
/// <see cref="CamPassport(string)"/>, <see cref="CognosLogon"/>, <see cref="Bearer(string)"/>,
/// <see cref="Application"/>) are equal when the credentials are. One that asks a supplier
/// (<see cref="CamPassport(Func{CancellationToken, ValueTask{string}})"/>,
/// <see cref="CognosIdToken"/>, <see cref="Bearer(Func{CancellationToken, ValueTask{string}})"/>)
/// is only equal to itself, so keep it and pass the same one each time; so is your own, unless it
/// defines <c>Equals</c>.</para></summary>
public abstract class Tm1Authenticator
{
    /// <summary>The header to log in with; asked for at each login.</summary>
    public abstract ValueTask<string> AuthorizationAsync(CancellationToken cancellationToken = default);

    /// <summary>Who logs in, without secrets, for logs and errors.</summary>
    public abstract string Describe();

    /// <summary>The TM1 user name for TM1 12 application (service-to-service) authentication, when
    /// logging in through <c>/&lt;instance&gt;/auth/v1/session</c>. Null for authenticators that
    /// send <c>Authorization</c> directly to the database's <c>ActiveSession</c>.</summary>
    public virtual string? ApplicationUser() => null;

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

    /// <summary>Bearer token authentication, for TM1 12 with OAuth2/OIDC:
    /// <c>Bearer &lt;token&gt;</c>. Two of these are equal when their tokens are.</summary>
    /// <param name="token">The bearer token; not blank.</param>
    /// <exception cref="ArgumentException"><paramref name="token"/> is blank or contains control
    /// characters.</exception>
    public static Tm1Authenticator Bearer(string token) => new Fixed("Bearer " + CheckToken(token), "Bearer");

    /// <summary>Bearer token authentication with a token supplied on every login, for TM1 12 with
    /// OAuth2/OIDC: <c>Bearer &lt;token&gt;</c>. Equal only to itself.</summary>
    /// <param name="token">Supplies a current bearer token; asked at each login.</param>
    public static Tm1Authenticator Bearer(Func<CancellationToken, ValueTask<string>> token) =>
        new BearerSupplier(token ?? throw new ArgumentNullException(nameof(token)));

    /// <summary>TM1 12 (Planning Analytics Engine) service-to-service application authentication.
    /// Logs in via <c>POST /&lt;instance&gt;/auth/v1/session</c> with HTTP Basic
    /// <c>clientId:clientSecret</c> and a JSON body naming <paramref name="user"/>, then resolves
    /// the session on the target database. Requires <c>Tm1Dialect.V12</c> and cannot be combined
    /// with <c>TM1-Impersonate</c>.</summary>
    /// <param name="clientId">The configured application client ID; not blank and without <c>:</c>.</param>
    /// <param name="clientSecret">The configured application client secret; not blank.</param>
    /// <param name="user">The TM1 user to act as; not blank.</param>
    /// <exception cref="ArgumentException">Any argument is blank or contains invalid characters.</exception>
    public static Tm1Authenticator Application(string clientId, string clientSecret, string user) => new ApplicationAuth(clientId, clientSecret, user);

    private static string Base64(string text) => Convert.ToBase64String(Encoding.UTF8.GetBytes(text));

    private static string CheckToken(string token)
    {
        ArgumentNullException.ThrowIfNull(token);
        if (string.IsNullOrWhiteSpace(token))
        {
            throw new ArgumentException("bearer token must not be blank", nameof(token));
        }
        foreach (var c in token)
        {
            if (c <= 0x20 || c >= 0x7F)
            {
                throw new ArgumentException("bearer token must contain only printable ASCII characters without whitespace", nameof(token));
            }
        }
        return token;
    }

    private static string CheckNonBlank(string value, string paramName)
    {
        ArgumentNullException.ThrowIfNull(value, paramName);
        if (string.IsNullOrWhiteSpace(value))
        {
            throw new ArgumentException($"{paramName} must not be blank", paramName);
        }
        return value;
    }

    private static bool HasControlChars(string value)
    {
        foreach (var c in value)
        {
            if (char.IsControl(c))
            {
                return true;
            }
        }
        return false;
    }

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

    private sealed class BearerSupplier(Func<CancellationToken, ValueTask<string>> token) : Tm1Authenticator
    {
        public override async ValueTask<string> AuthorizationAsync(CancellationToken cancellationToken = default) =>
            "Bearer " + CheckToken(await token(cancellationToken).ConfigureAwait(false));

        public override string Describe() => "Bearer";
    }

    private sealed class ApplicationAuth : Tm1Authenticator
    {
        private readonly string _clientId;
        private readonly string _clientSecret;
        private readonly string _user;
        private readonly string _headerValue;
        private readonly string _description;

        public ApplicationAuth(string clientId, string clientSecret, string user)
        {
            _clientId = CheckNonBlank(clientId, nameof(clientId));
            if (_clientId.Contains(':', StringComparison.Ordinal))
            {
                throw new ArgumentException("clientId must not contain ':'", nameof(clientId));
            }
            if (HasControlChars(_clientId))
            {
                throw new ArgumentException("clientId must not contain control characters", nameof(clientId));
            }
            _clientSecret = CheckNonBlank(clientSecret, nameof(clientSecret));
            _user = CheckNonBlank(user, nameof(user));
            if (HasControlChars(_user))
            {
                throw new ArgumentException("user must not contain control characters", nameof(user));
            }
            _headerValue = "Basic " + Base64($"{_clientId}:{_clientSecret}");
            _description = $"Application {_clientId} as {_user}";
        }

        public override ValueTask<string> AuthorizationAsync(CancellationToken cancellationToken = default) => ValueTask.FromResult(_headerValue);

        public override string? ApplicationUser() => _user;

        public override string Describe() => _description;

        public override bool Equals(object? obj) =>
            ReferenceEquals(this, obj)
            || (
                obj is ApplicationAuth other
                && string.Equals(_clientId, other._clientId, StringComparison.Ordinal)
                && string.Equals(_clientSecret, other._clientSecret, StringComparison.Ordinal)
                && string.Equals(_user, other._user, StringComparison.Ordinal)
            );

        public override int GetHashCode() => HashCode.Combine(_clientId, _clientSecret, _user);
    }
}

// SPDX-License-Identifier: Apache-2.0

using System;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace Skipcheck.Tm1.Testcontainers;

/// <summary>A user's login to a TM1 v12 instance, through one of the instance's applications
/// ("service-to-service"), as the Java <c>Tm1Login</c>: TM1 v12 has no passwords of its own, so the
/// application's client ID and secret vouch for the user's name. A login belongs to an instance: its
/// session serves every database there. Whether the user may do anything in a database depends on
/// the user existing in it. Get one from <see cref="Tm1Database.Login()"/> or
/// <see cref="Tm1Database.Login(string)"/>, then either let it log in for you
/// (<see cref="AuthorizeAsync"/> or <see cref="CookieAsync"/>), or hand <see cref="ClientId"/>,
/// <see cref="ClientSecret"/> and <see cref="User"/> to a client that logs in itself.</summary>
public sealed class Tm1Login
{
    private static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(30);

    // Without a cookie container: a session's cookie is this login's to keep, not the process's
    private static readonly HttpClient Http = new(
        new SocketsHttpHandler
        {
            UseCookies = false,
            // The login's own answer carries the cookie
            AllowAutoRedirect = false,
            ConnectTimeout = RequestTimeout,
        }
    )
    {
        Timeout = RequestTimeout,
    };

    // The session request's property, which is TM1's name for it and not this class's
    private const string UserProperty = "User";

    private readonly Lock gate = new();
    private Task<string>? cookie;
    private readonly string authUrl;

    // An OAuth 2.0 login: where its tokens come from, and a database's address to turn a token into a session at
    private readonly KeycloakContainer? keycloak;
    private readonly Tm1Database? database;

    internal Tm1Login(string authUrl, string instance, string clientId, string clientSecret, string user)
    {
        ArgumentNullException.ThrowIfNull(user);
        this.authUrl = authUrl;
        Instance = instance;
        ClientId = clientId;
        ClientSecret = clientSecret;
        User = user;
        Password = "";
    }

    /// <summary>A login through OAuth 2.0, with tokens from the engine's Keycloak.</summary>
    internal Tm1Login(KeycloakContainer keycloak, Tm1Database database, string user, string password)
    {
        // Addresses are asked of the database when they're needed: a login can be made before the engine has started
        authUrl = "";
        this.database = database;
        this.keycloak = keycloak;
        Instance = database.Instance;
        ClientId = Tm1EngineContainer.OAuth2Client;
        ClientSecret = "";
        User = user;
        Password = password;
    }

    /// <summary>Whether this is a login through OAuth 2.0 (<see cref="Tm1Database.OAuth2Login"/>), rather than through
    /// the instance's application.</summary>
    public bool IsOAuth2 => keycloak is not null;

    /// <summary>The user's password at the identity provider, for an OAuth 2.0 login; empty otherwise, as TM1 v12
    /// keeps none.</summary>
    public string Password { get; }

    /// <summary>Where a token is asked for: the identity provider's token endpoint for an OAuth 2.0 login, as seen from
    /// the host, and TM1's own otherwise.</summary>
    /// <exception cref="InvalidOperationException">An OAuth 2.0 login's Keycloak isn't started.</exception>
    public string TokenUrl =>
        keycloak is null ? authUrl + "token" : keycloak.Url + "/realms/" + Tm1EngineContainer.OAuth2Realm + "/protocol/openid-connect/token";

    /// <summary>The instance this login is for.</summary>
    public string Instance { get; }

    /// <summary>The TM1 user this logs in as.</summary>
    public string User { get; }

    /// <summary>The application's client ID; for an OAuth 2.0 login, the client its tokens are asked for as.</summary>
    public string ClientId { get; }

    /// <summary>The application's client secret; empty for an OAuth 2.0 login, whose client is a public one.</summary>
    public string ClientSecret { get; }

    /// <summary>Where a session or a token is asked for, as seen from the host, ending in <c>/</c>,
    /// e.g. <c>http://localhost:54321/tm1/auth/v1/</c>.</summary>
    public string AuthUrl => database is null ? authUrl : database.Engine.Url + Instance + "/auth/v1/";

    /// <summary>The session cookie, <c>TM1SessionId=...</c>, for a <c>Cookie</c> header. Logs in the
    /// first time, with the application's credentials as Basic authentication, and keeps the session. An OAuth 2.0
    /// login sends TM1 a token from the provider, and keeps the session TM1 answers with.</summary>
    /// <exception cref="InvalidOperationException">TM1 refuses the login.</exception>
    public async Task<string> CookieAsync(CancellationToken cancellationToken = default)
    {
        Task<string> login;
        lock (gate)
        {
            // Not the caller's token: the one login serves every caller of this, whoever asked first
            login = cookie ??= keycloak is not null
                ? TokenSessionAsync()
                : SessionAsync("Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes(ClientId + ":" + ClientSecret)), CancellationToken.None);
        }
        try
        {
            return await login.WaitAsync(cancellationToken).ConfigureAwait(false);
        }
        catch when (login.IsFaulted || login.IsCanceled)
        {
            // A login that failed isn't kept: the next call tries again
            lock (gate)
            {
                if (ReferenceEquals(cookie, login))
                {
                    cookie = null;
                }
            }
            throw;
        }
    }

    /// <summary>A bearer token for the application, from TM1's token endpoint (the OAuth 2.0
    /// client-credentials grant). It names no user: send it as <c>Authorization: Bearer</c> with the
    /// user's name to get a session, as <see cref="SessionAsync"/> does. For an OAuth 2.0 login, a new access token for the
    /// user from the identity provider, with the user's password: one TM1 takes as <c>Authorization: Bearer</c> on any
    /// request.</summary>
    /// <returns>The access token, a JWT.</returns>
    /// <exception cref="InvalidOperationException">TM1 refuses the application's credentials, or the provider the user's.</exception>
    public async Task<string> TokenAsync(CancellationToken cancellationToken = default)
    {
        if (keycloak is not null)
        {
            return await keycloak.AccessTokenAsync(Tm1EngineContainer.OAuth2Realm, ClientId, User, Password, cancellationToken).ConfigureAwait(false);
        }
        using var request = new HttpRequestMessage(HttpMethod.Post, AuthUrl + "token")
        {
            Content = Json(writer =>
            {
                writer.WriteString("grant_type", "client_credentials");
                writer.WriteString("client_id", ClientId);
                writer.WriteString("client_secret", ClientSecret);
            }),
        };
        using var response = await SendAsync(request, cancellationToken).ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        var token = response.IsSuccessStatusCode ? AccessToken(body) : null;
        if (string.IsNullOrEmpty(token))
        {
            throw new InvalidOperationException($"TM1 gave no token for the application of instance '{Instance}' (HTTP {(int)response.StatusCode}): {body}");
        }
        return token;
    }

    /// <summary>Logs the user in with an <c>Authorization</c> header of your own, such as
    /// <c>"Bearer " + await TokenAsync()</c>, and returns the new session's cookie. Each call makes a
    /// session; <see cref="CookieAsync"/> keeps one.</summary>
    /// <returns>The session cookie, <c>TM1SessionId=...</c>.</returns>
    /// <exception cref="InvalidOperationException">TM1 refuses the login; or this is an OAuth 2.0 login, whose token TM1
    /// takes on any request and not here.</exception>
    public async Task<string> SessionAsync(string authorization, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(authorization);
        if (keycloak is not null)
        {
            throw new InvalidOperationException(
                "An OAuth 2.0 login has no session to ask for: TM1 takes its token on any request (AuthorizeWithTokenAsync), and CookieAsync() keeps the session that gives"
            );
        }
        using var request = new HttpRequestMessage(HttpMethod.Post, AuthUrl + "session") { Content = Json(writer => writer.WriteString(UserProperty, User)) };
        request.Headers.TryAddWithoutValidation("Authorization", authorization);
        using var response = await SendAsync(request, cancellationToken).ConfigureAwait(false);
        return await SessionCookieAsync(response, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>The session TM1 gives the first request that carries the provider's token.</summary>
    private async Task<string> TokenSessionAsync()
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, database!.RestUrl + "ActiveUser?$select=Name");
        request.Headers.TryAddWithoutValidation("Authorization", "Bearer " + await TokenAsync(CancellationToken.None).ConfigureAwait(false));
        using var response = await SendAsync(request, CancellationToken.None).ConfigureAwait(false);
        return await SessionCookieAsync(response, CancellationToken.None).ConfigureAwait(false);
    }

    private async Task<string> SessionCookieAsync(HttpResponseMessage response, CancellationToken cancellationToken)
    {
        var found = response.Headers.TryGetValues("Set-Cookie", out var cookies)
            ? cookies.Select(c => c.Split(';', 2)[0].Trim()).FirstOrDefault(c => c.StartsWith("TM1SessionId=", StringComparison.Ordinal))
            : null;
        if (!response.IsSuccessStatusCode || found is null)
        {
            var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            throw new InvalidOperationException($"TM1 refused the login of '{User}' to instance '{Instance}' (HTTP {(int)response.StatusCode}): {body}");
        }
        return found;
    }

    /// <summary>Adds this login's session cookie to <paramref name="request"/>, logging in first if
    /// it hasn't. Send it with a client that keeps no cookies of its own
    /// (<c>UseCookies = false</c>), or the header is replaced.</summary>
    /// <returns>The same request.</returns>
    public async Task<HttpRequestMessage> AuthorizeAsync(HttpRequestMessage request, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        request.Headers.TryAddWithoutValidation("Cookie", await CookieAsync(cancellationToken).ConfigureAwait(false));
        return request;
    }

    /// <summary>Adds this login's token to <paramref name="request"/> as <c>Authorization: Bearer</c>, as a program with
    /// a token of its own would send it. For an OAuth 2.0 login, a new token from the provider each time; TM1 answers
    /// with a session cookie.</summary>
    /// <returns>The same request.</returns>
    /// <exception cref="InvalidOperationException">A login through the application, whose token TM1 takes only at its
    /// session endpoint (<see cref="SessionAsync"/>).</exception>
    public async Task<HttpRequestMessage> AuthorizeWithTokenAsync(HttpRequestMessage request, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        if (keycloak is null)
        {
            throw new InvalidOperationException(
                "TM1 takes its own token only where a session is asked for: use SessionAsync(\"Bearer \" + token), or AuthorizeAsync for the cookie"
            );
        }
        request.Headers.TryAddWithoutValidation("Authorization", "Bearer " + await TokenAsync(cancellationToken).ConfigureAwait(false));
        return request;
    }

    /// <inheritdoc/>
    public override string ToString() => "Tm1Login[" + User + " in " + Instance + "]"; // never the secret

    private async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        try
        {
            return await Http.SendAsync(request, cancellationToken).ConfigureAwait(false);
        }
        catch (HttpRequestException e)
        {
            throw new IOException("Couldn't reach TM1 at " + AuthUrl, e);
        }
    }

    private static ByteArrayContent Json(Action<Utf8JsonWriter> write)
    {
        using var buffer = new MemoryStream();
        using (var writer = new Utf8JsonWriter(buffer))
        {
            writer.WriteStartObject();
            write(writer);
            writer.WriteEndObject();
        }
        var content = new ByteArrayContent(buffer.ToArray());
        content.Headers.TryAddWithoutValidation("Content-Type", "application/json");
        return content;
    }

    private static string? AccessToken(string body)
    {
        try
        {
            using var json = JsonDocument.Parse(body);
            return
                json.RootElement.ValueKind == JsonValueKind.Object
                && json.RootElement.TryGetProperty("access_token", out var token)
                && token.ValueKind == JsonValueKind.String
                ? token.GetString()
                : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }
}

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

    internal Tm1Login(string authUrl, string instance, string clientId, string clientSecret, string user)
    {
        ArgumentNullException.ThrowIfNull(user);
        AuthUrl = authUrl;
        Instance = instance;
        ClientId = clientId;
        ClientSecret = clientSecret;
        User = user;
    }

    /// <summary>The instance this login is for.</summary>
    public string Instance { get; }

    /// <summary>The TM1 user this logs in as.</summary>
    public string User { get; }

    /// <summary>The application's client ID.</summary>
    public string ClientId { get; }

    /// <summary>The application's client secret.</summary>
    public string ClientSecret { get; }

    /// <summary>Where a session or a token is asked for, as seen from the host, ending in <c>/</c>,
    /// e.g. <c>http://localhost:54321/tm1/auth/v1/</c>.</summary>
    public string AuthUrl { get; }

    /// <summary>The session cookie, <c>TM1SessionId=...</c>, for a <c>Cookie</c> header. Logs in the
    /// first time, with the application's credentials as Basic authentication, and keeps the session.</summary>
    /// <exception cref="InvalidOperationException">TM1 refuses the login.</exception>
    public async Task<string> CookieAsync(CancellationToken cancellationToken = default)
    {
        Task<string> login;
        lock (gate)
        {
            // Not the caller's token: the one login serves every caller of this, whoever asked first
            login = cookie ??= SessionAsync("Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes(ClientId + ":" + ClientSecret)), CancellationToken.None);
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
    /// user's name to get a session, as <see cref="SessionAsync"/> does.</summary>
    /// <returns>The access token, a JWT.</returns>
    /// <exception cref="InvalidOperationException">TM1 refuses the application's credentials.</exception>
    public async Task<string> TokenAsync(CancellationToken cancellationToken = default)
    {
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
    /// <exception cref="InvalidOperationException">TM1 refuses the login.</exception>
    public async Task<string> SessionAsync(string authorization, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(authorization);
        using var request = new HttpRequestMessage(HttpMethod.Post, AuthUrl + "session") { Content = Json(writer => writer.WriteString(UserProperty, User)) };
        request.Headers.TryAddWithoutValidation("Authorization", authorization);
        using var response = await SendAsync(request, cancellationToken).ConfigureAwait(false);
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

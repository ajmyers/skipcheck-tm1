// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;

namespace Skipcheck.Tm1.Client.Auth;

/// <summary>CAM passports from Cognos, for <see cref="Tm1Authenticator.CamPassport(Func{CancellationToken, ValueTask{string}})"/>, as the Java
/// client's <c>CognosPassports</c>: the client logs on to Cognos itself, rather than being handed a
/// passport. Each function logs on again every time it's called, so every TM1 login (including the
/// one after TM1 drops a session) gets a fresh passport, and an expired one is never reused.
///
/// <para>Both log on with Cognos's REST API: <c>PUT &lt;dispatcher&gt;/api/v1/session</c> with the
/// logon parameters, answered with the passport in a <c>cam_passport</c> cookie. The dispatcher is
/// the address TM1 itself is pointed at (<c>ServerCAMURI</c>, such as <c>http://cognos:9300</c>),
/// reachable from the client; TM1 names it when it refuses a login
/// (<c>WWW-Authenticate: CAMPassport http://cognos:9300/bi/v1/disp</c>).</para>
///
/// <para>A logon Cognos refuses (401, 403) fails with a <see cref="Tm1AuthenticationException"/>
/// (<see cref="Tm1ErrorKind.CredentialRejected"/>), which the session doesn't retry. The
/// <c>handler</c> parameters send through a handler of the caller's (a proxy, a test).</para></summary>
public static partial class CognosPassports
{
    private static readonly Lazy<HttpClient> Shared = new(() => new HttpClient(new SocketsHttpHandler { UseCookies = false }));

    /// <summary>Passports for a user name and password in a namespace, such as an LDAP one
    /// (<c>CAMNamespace</c>, <c>CAMUsername</c>, <c>CAMPassword</c>).</summary>
    /// <param name="dispatcher">The Cognos dispatcher's address, such as <c>http://cognos:9300</c>.</param>
    /// <param name="namespace">The namespace's ID, such as <c>LDAP</c>.</param>
    /// <param name="user">The user name.</param>
    /// <param name="password">The password.</param>
    /// <param name="handler">The handler to send through; null for the client's own.</param>
    /// <returns>A function that logs on each time it's called.</returns>
    public static Func<CancellationToken, ValueTask<string>> Password(
        string dispatcher,
        string @namespace,
        string user,
        string password,
        HttpMessageHandler? handler = null
    )
    {
        ArgumentNullException.ThrowIfNull(user);
        ArgumentNullException.ThrowIfNull(password);
        return Logon(
            dispatcher,
            @namespace,
            _ => ValueTask.FromResult<IReadOnlyList<Parameter>>([new("CAMUsername", user), new("CAMPassword", password)]),
            handler
        );
    }

    /// <summary>Passports for an OpenID Connect ID token the application already holds, through
    /// Cognos's OIDC namespace (<c>CAMNamespace</c>, <c>CAMOIDCIdToken</c>): no password ever reaches
    /// the client.</summary>
    /// <param name="dispatcher">The Cognos dispatcher's address, such as <c>http://cognos:9300</c>.</param>
    /// <param name="namespace">The OIDC namespace's ID, such as <c>OIDC</c>.</param>
    /// <param name="idToken">Supplies a current ID token; asked at each logon.</param>
    /// <param name="handler">The handler to send through; null for the client's own.</param>
    /// <returns>A function that logs on each time it's called.</returns>
    public static Func<CancellationToken, ValueTask<string>> IdToken(
        string dispatcher,
        string @namespace,
        Func<CancellationToken, ValueTask<string>> idToken,
        HttpMessageHandler? handler = null
    )
    {
        ArgumentNullException.ThrowIfNull(idToken);
        return Logon(dispatcher, @namespace, async ct => [new("CAMOIDCIdToken", await idToken(ct).ConfigureAwait(false))], handler);
    }

    /// <summary>Cognos's session resource, on the dispatcher, with or without a <c>/bi</c> path.</summary>
    internal static Uri SessionUri(string dispatcher)
    {
        var root = TrailingBi().Replace(dispatcher.TrimEnd('/'), "");
        return new Uri(root + "/api/v1/session");
    }

    private static Func<CancellationToken, ValueTask<string>> Logon(
        string dispatcher,
        string @namespace,
        Func<CancellationToken, ValueTask<IReadOnlyList<Parameter>>> credentials,
        HttpMessageHandler? handler
    )
    {
        ArgumentNullException.ThrowIfNull(@namespace);
        var session = SessionUri(dispatcher ?? throw new ArgumentNullException(nameof(dispatcher)));
        var http = handler is null ? null : new HttpClient(handler, disposeHandler: false);
        return async ct =>
        {
            var parameters = new List<Parameter> { new("CAMNamespace", @namespace) };
            parameters.AddRange(await credentials(ct).ConfigureAwait(false));
            var body = JsonSerializer.SerializeToUtf8Bytes(
                new Dictionary<string, object>
                {
                    ["parameters"] = parameters.ConvertAll(p => new Dictionary<string, string> { ["name"] = p.Name, ["value"] = p.Value }),
                }
            );
            using var request = new HttpRequestMessage(HttpMethod.Put, session) { Content = new ByteArrayContent(body) };
            request.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/json");
            using var response = await (http ?? Shared.Value).SendAsync(request, ct).ConfigureAwait(false);
            var status = (int)response.StatusCode;
            if (status is 401 or 403)
            {
                throw Tm1ApiException.Of(status, Tm1ErrorKind.CredentialRejected, ODataError.FromText("Cognos refused the logon"), "PUT", session.ToString());
            }
            var passport = status < 300 ? Cookie(response, "cam_passport") : null;
            if (string.IsNullOrWhiteSpace(passport))
            {
                throw new Tm1ProtocolException($"Cognos answered the logon with {status} and no cam_passport cookie", null, "PUT", session.ToString(), null);
            }
            return passport;
        };
    }

    private static string? Cookie(HttpResponseMessage response, string name)
    {
        if (!response.Headers.TryGetValues("Set-Cookie", out var headers))
        {
            return null;
        }
        foreach (var header in headers)
        {
            var first = header.Split(';')[0];
            var eq = first.IndexOf('=');
            if (eq > 0 && first[..eq].Trim() == name)
            {
                return first[(eq + 1)..].Trim().Trim('"');
            }
        }
        return null;
    }

    /// <summary>One logon parameter, as Cognos takes it: <c>{"name":…,"value":…}</c>.</summary>
    private sealed record Parameter(string Name, string Value)
    {
        /// <summary>The value only ever goes to Cognos: never into logs.</summary>
        public override string ToString() => $"Parameter[{Name}]";
    }

    [GeneratedRegex("/bi$")]
    private static partial Regex TrailingBi();
}

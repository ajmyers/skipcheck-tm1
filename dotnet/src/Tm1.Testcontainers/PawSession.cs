// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace Skipcheck.Tm1.Testcontainers;

/// <summary>A user's session with PAW, as a browser has one: PAW's logon form in TM1-security mode,
/// then calls to TM1 through PAW's proxy. Package-private in Java, as here; the tests use it.</summary>
internal sealed partial class PawSession : IDisposable
{
    private static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(30);
    private readonly string url;
    private readonly Dictionary<string, string> cookies = new(StringComparer.Ordinal);
    private readonly HttpClient http;

    public PawSession(string url)
    {
        this.url = url;
        http = new HttpClient(
            new SocketsHttpHandler
            {
                UseCookies = false,
                AllowAutoRedirect = false,
                ConnectTimeout = RequestTimeout,
            }
        )
        {
            Timeout = RequestTimeout,
        };
    }

    /// <summary>Logs on with PAW's form.</summary>
    /// <exception cref="IOException">PAW refused.</exception>
    public async Task<PawSession> LoginAsync(string username, string password, CancellationToken cancellationToken = default)
    {
        var page = await SendAsync(new HttpRequestMessage(HttpMethod.Get, url + "login"), cancellationToken).ConfigureAwait(false);
        if (page.Status != HttpStatusCode.OK)
        {
            throw new IOException($"PAW's logon page answered {(int)page.Status}");
        }
        var post = new HttpRequestMessage(HttpMethod.Post, url + "login/form/")
        {
            Content = new FormUrlEncodedContent(
                new Dictionary<string, string>
                {
                    ["mode"] = "basic",
                    ["username"] = username,
                    ["password"] = password,
                }
            ),
        };
        if (cookies.TryGetValue("ba-sso-csrf", out var csrf))
        {
            post.Headers.TryAddWithoutValidation("ba-sso-authenticity", csrf);
        }
        var response = await SendAsync(post, cancellationToken).ConfigureAwait(false);
        if (response.Status != HttpStatusCode.OK || response.Body.Contains("error = \"", StringComparison.Ordinal))
        {
            throw new IOException($"PAW refused the logon of {username} ({(int)response.Status})");
        }
        return this;
    }

    /// <summary>The TM1 servers PAW lists.</summary>
    public async Task<List<string>> ServersAsync(CancellationToken cancellationToken = default)
    {
        var response = await GetAsync("tm1/api/v1/Servers", cancellationToken).ConfigureAwait(false);
        if (response.Status != HttpStatusCode.OK)
        {
            throw new IOException($"PAW couldn't list TM1 servers ({(int)response.Status}): {response.Body}");
        }
        return [.. Name().Matches(response.Body).Select(m => m.Groups[1].Value)];
    }

    /// <summary>A GET of <paramref name="path"/> on <paramref name="server"/>'s REST API, through PAW.</summary>
    public Task<(HttpStatusCode Status, string Body)> Tm1GetAsync(string server, string path, CancellationToken cancellationToken = default) =>
        GetAsync("tm1/" + Uri.EscapeDataString(server) + "/api/v1/" + path, cancellationToken);

    /// <summary>A GET of <paramref name="path"/> on PAW.</summary>
    public Task<(HttpStatusCode Status, string Body)> GetAsync(string path, CancellationToken cancellationToken = default) =>
        SendAsync(new HttpRequestMessage(HttpMethod.Get, url + path), cancellationToken);

    public void Dispose() => http.Dispose();

    private async Task<(HttpStatusCode Status, string Body)> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        using (request)
        {
            if (cookies.Count > 0)
            {
                request.Headers.TryAddWithoutValidation("Cookie", string.Join("; ", cookies.Select(c => c.Key + "=" + c.Value)));
            }
            try
            {
                using var response = await http.SendAsync(request, cancellationToken).ConfigureAwait(false);
                if (response.Headers.TryGetValues("Set-Cookie", out var headers))
                {
                    foreach (var pair in headers.Select(h => h.Split(';', 2)[0].Split('=', 2)).Where(p => p.Length == 2))
                    {
                        cookies[pair[0].Trim()] = pair[1].Trim().Trim('"');
                    }
                }
                return (response.StatusCode, await ReadTextAsync(response.Content, cancellationToken).ConfigureAwait(false));
            }
            catch (HttpRequestException e)
            {
                throw new IOException(e.Message, e);
            }
        }
    }

    /// <summary>A body as text, in the charset its <c>Content-Type</c> names, or UTF-8 if it names one .NET doesn't know.
    /// PAW in CAM security answers its logon form with such a charset, and <c>ReadAsStringAsync</c> throws on it.</summary>
    internal static async Task<string> ReadTextAsync(HttpContent content, CancellationToken cancellationToken)
    {
        var bytes = await content.ReadAsByteArrayAsync(cancellationToken).ConfigureAwait(false);
        var encoding = Encoding.UTF8;
        var charset = content.Headers.ContentType?.CharSet?.Trim('"', ' ');
        if (!string.IsNullOrEmpty(charset))
        {
            try
            {
                encoding = Encoding.GetEncoding(charset);
            }
            catch (ArgumentException)
            {
                // Not a charset .NET knows: UTF-8, as a browser would assume for PAW's pages
            }
        }
        return encoding.GetString(bytes);
    }

    [GeneratedRegex("\"Name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")]
    private static partial Regex Name();
}

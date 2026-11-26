// SPDX-License-Identifier: Apache-2.0

using System;
using System.Globalization;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;

namespace Skipcheck.Tm1.Testcontainers.Tests;

/// <summary>A minimal TM1 REST session for the example tests, as Java's test Tm1Rest: it logs in
/// once, reuses the session cookie, and closes the session when done, since TM1 opens a new session
/// for every request that doesn't send one back. In your own tests you'd build a <c>Tm1Client</c>
/// from <see cref="Tm1ServerContainer.RestUrl"/> and the container's login instead.</summary>
public sealed class Tm1Rest : IAsyncDisposable
{
    private readonly HttpClient http = new(new HttpClientHandler { UseCookies = false });
    private readonly string baseUrl;
    private readonly string authorization;
    private string? cookie;

    public Tm1Rest(Tm1ServerContainer tm1)
        : this(tm1, tm1.Authorization) { }

    /// <summary>A session with its own login, e.g. a Cognos user's <c>CognosContainer.CamAuthorization</c>.</summary>
    public Tm1Rest(Tm1ServerContainer tm1, string authorization)
    {
        baseUrl = tm1.RestUrl;
        this.authorization = authorization;
    }

    /// <summary>GETs a path under the service root and returns the body. Fails on anything but 200.</summary>
    public async Task<string> GetAsync(string path)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, baseUrl + path);
        request.Headers.TryAddWithoutValidation(cookie is null ? "Authorization" : "Cookie", cookie ?? authorization);
        using var response = await http.SendAsync(request);
        var body = await response.Content.ReadAsStringAsync();
        if (response.StatusCode != HttpStatusCode.OK)
        {
            throw new InvalidOperationException($"GET {path} returned {(int)response.StatusCode}: {body}");
        }
        if (cookie is null && response.Headers.TryGetValues("Set-Cookie", out var cookies))
        {
            cookie = cookies.Select(c => c.Split(';', 2)[0].Trim()).FirstOrDefault(c => c.StartsWith("TM1SessionId=", StringComparison.Ordinal));
        }
        return body;
    }

    /// <summary>POSTs a JSON body to a path under the service root and returns the response, whatever its status.</summary>
    public async Task<(HttpStatusCode Status, string Body)> PostAsync(string path, string json)
    {
        if (cookie is null)
        {
            await GetAsync("ActiveUser?$select=Name"); // logs in and keeps the session cookie
        }
        using var request = new HttpRequestMessage(HttpMethod.Post, baseUrl + path) { Content = new StringContent(json, Encoding.UTF8, "application/json") };
        request.Headers.TryAddWithoutValidation("Cookie", cookie);
        using var response = await http.SendAsync(request);
        return (response.StatusCode, await response.Content.ReadAsStringAsync());
    }

    /// <summary>The number of cubes, excluding control cubes (whose names start with '}').</summary>
    public async Task<int> ModelCubeCountAsync()
    {
        var count = (await GetAsync("Cubes/$count?$filter=not%20startswith(Name,'%7D')")).Trim();
        return count.Length > 0 && count.All(char.IsAsciiDigit)
            ? int.Parse(count, CultureInfo.InvariantCulture)
            : throw new InvalidOperationException("Not a count: " + count);
    }

    public async ValueTask DisposeAsync()
    {
        if (cookie is not null)
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, baseUrl + "ActiveSession/tm1.Close")
            {
                Content = new StringContent("{}", Encoding.UTF8, "application/json"),
            };
            request.Headers.TryAddWithoutValidation("Cookie", cookie);
            (await http.SendAsync(request)).Dispose();
        }
        http.Dispose();
    }
}

// SPDX-License-Identifier: Apache-2.0

using System;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;

namespace Skipcheck.Tm1.Testcontainers.Tests;

/// <summary>Plain HTTP against a TM1 v12 database, for the tests, as Java's test Tm1EngineRest: no
/// client library, so a client bug can't break the tests of the containers.</summary>
public static class Tm1EngineRest
{
    // Without a cookie container: each request carries the session cookie of the login it's given
    private static readonly HttpClient Http = new(new SocketsHttpHandler { UseCookies = false, ConnectTimeout = TimeSpan.FromSeconds(30) })
    {
        Timeout = TimeSpan.FromSeconds(60),
    };

    /// <summary>GETs a path of the database's REST API, logged in.</summary>
    public static async Task<(HttpStatusCode Status, string Body)> GetAsync(Tm1Database database, Tm1Login login, string path)
    {
        using var request = await login.AuthorizeAsync(new HttpRequestMessage(HttpMethod.Get, database.RestUrl + path));
        return await SendAsync(request);
    }

    /// <summary>POSTs JSON to a path of the database's REST API, logged in.</summary>
    public static async Task<(HttpStatusCode Status, string Body)> PostAsync(Tm1Database database, Tm1Login login, string path, string json)
    {
        using var request = await login.AuthorizeAsync(
            new HttpRequestMessage(HttpMethod.Post, database.RestUrl + path) { Content = new StringContent(json, Encoding.UTF8, "application/json") }
        );
        return await SendAsync(request);
    }

    /// <summary>DELETEs a path of the database's REST API, logged in.</summary>
    public static async Task<(HttpStatusCode Status, string Body)> DeleteAsync(Tm1Database database, Tm1Login login, string path)
    {
        using var request = await login.AuthorizeAsync(new HttpRequestMessage(HttpMethod.Delete, database.RestUrl + path));
        return await SendAsync(request);
    }

    /// <summary>Sends a request of your own making.</summary>
    public static async Task<(HttpStatusCode Status, string Body)> SendAsync(HttpRequestMessage request)
    {
        using var response = await Http.SendAsync(request);
        return (response.StatusCode, await response.Content.ReadAsStringAsync());
    }
}

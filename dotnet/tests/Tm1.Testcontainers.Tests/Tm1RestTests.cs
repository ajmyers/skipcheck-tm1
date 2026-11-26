// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;

namespace Skipcheck.Tm1.Testcontainers.Tests;

/// <summary>The example tests' own TM1 REST session (<see cref="Tm1Rest"/>), without TM1.</summary>
public class Tm1RestTests
{
    private const string BaseUrl = "http://localhost:1/api/v1/";
    private const string Authorization = "Basic YWRtaW46YXBwbGU=";

    [Fact]
    public async Task PostsWithTheSessionCookieOfItsLogon()
    {
        using var tm1 = new Recording("TM1SessionId=abc; Path=/api/; HttpOnly");
        var rest = new Tm1Rest(BaseUrl, Authorization, tm1);

        await rest.PostAsync("Cubes", "{}");

        Assert.Equal([("GET", BaseUrl + "ActiveUser?$select=Name"), ("POST", BaseUrl + "Cubes")], tm1.Sent.Select(s => (s.Method, s.Url)));
        Assert.Equal(Authorization, tm1.Sent[0].Authorization);
        Assert.Null(tm1.Sent[0].Cookie);
        Assert.Equal("TM1SessionId=abc", tm1.Sent[1].Cookie);
        Assert.Null(tm1.Sent[1].Authorization);
    }

    [Fact]
    public async Task RefusesToPostWhenTheLogonSentNoSessionCookie()
    {
        using var tm1 = new Recording(null);
        var rest = new Tm1Rest(BaseUrl, Authorization, tm1);

        var e = await Assert.ThrowsAsync<InvalidOperationException>(() => rest.PostAsync("Cubes", "{}"));

        Assert.Equal("TM1 sent no TM1SessionId cookie at logon", e.Message);
        Assert.Equal([("GET", BaseUrl + "ActiveUser?$select=Name")], tm1.Sent.Select(s => (s.Method, s.Url)));
        await rest.DisposeAsync(); // no session to close: nothing more is sent
        Assert.Single(tm1.Sent);
    }

    /// <summary>Answers every request with 200, and <paramref name="setCookie"/> if any; keeps what was sent.</summary>
    private sealed class Recording(string? setCookie) : HttpMessageHandler
    {
        public List<(string Method, string Url, string? Authorization, string? Cookie)> Sent { get; } = [];

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Sent.Add((request.Method.Method, request.RequestUri!.OriginalString, Header(request, "Authorization"), Header(request, "Cookie")));
            var response = new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("{\"Name\":\"Admin\"}") };
            if (setCookie is not null)
            {
                response.Headers.TryAddWithoutValidation("Set-Cookie", setCookie);
            }
            return Task.FromResult(response);
        }

        private static string? Header(HttpRequestMessage request, string name) =>
            request.Headers.TryGetValues(name, out var values) ? string.Join(", ", values) : null;
    }
}

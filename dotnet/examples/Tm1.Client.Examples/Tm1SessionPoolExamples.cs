// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.Services;
using Skipcheck.Tm1.Client.Session;

namespace Examples;

/// <summary>Examples for <c>Tm1SessionPool</c>, shown in the guide.</summary>
public static class Tm1SessionPoolExamples
{
    public static IAsyncEnumerable<Cube> PerUser(string secret, string user)
    {
        // @start region = per-user
        // One pool for the application, built once
        var pool = Tm1SessionPool
            .Builder(Tm1Session.Builder().BaseUrl("http://tm1:8010/api/v1/").BasicAuth("service", secret))
            .IdleTimeout(TimeSpan.FromMinutes(10))
            .Build();
        // Each time it's needed: the user's session, which logs in on its first request
        var session = pool.Session(Tm1Identity.Impersonating(user));
        return new CubeService(session).ListAsync();
        // @end
    }
}

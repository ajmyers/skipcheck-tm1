// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Auth;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;
using Skipcheck.Tm1.Client.Session;

namespace Examples;

/// <summary>Examples for <c>Tm1Session</c>, shown in the guide.</summary>
public static class SessionExamples
{
    public static Tm1Session Build(string password)
    {
        // @start region = build
        // CAM security, with a label that shows in Sessions and Threads
        return Tm1Session
            .Builder()
            .Address("https://tm1.example.com:8010")
            .Authenticator(Tm1Authenticator.CamNamespace("alice", password, "LDAP"))
            .SessionContext("Sales planning")
            .Build();
        // @end
    }

    public static Tm1Session Passport(Func<CancellationToken, ValueTask<string>> cognos)
    {
        // @start region = passport
        // CAM passports expire, so the session asks for a current one on every login
        return Tm1Session.Builder().Address("https://tm1.example.com:8010").Authenticator(Tm1Authenticator.CamPassport(cognos)).Build();
        // @end
    }

    public static IAsyncEnumerable<MessageLogEntry> Request(Tm1Session session)
    {
        // @start region = request
        // GET MessageLogEntries, straight through the session: the latest 20 entries
        return session.StreamAsync(
            Tm1Request.List<MessageLogEntry>(ODataPath.Of("MessageLogEntries"), ODataQuery.None().OrderByDescending(MessageLogEntry.Fields.TimeStamp).Top(20))
        );
        // @end
    }

    public static async Task Sandbox(Tm1Session session)
    {
        // @start region = sandbox
        // The cell: every dimension, in the cube's order
        var cell = new Dictionary<string, string> { ["Region"] = "North", ["Month"] = "Jan" };
        await new SandboxService(session).CreateAsync(SandboxPatch.Builder().Name("What-if").Build());
        await new CellService(session.WithSandbox("What-if")).UpdateAsync("Sales", cell, 1200);
        // @end
    }
}

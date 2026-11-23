// SPDX-License-Identifier: Apache-2.0

using System;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Auth;

namespace Examples;

/// <summary>Examples for <c>CognosPassports</c>, shown in the guide.</summary>
public static class CognosPassportsExamples
{
    public static Tm1Client Logon(string password)
    {
        // @start region = logon
        // The client logs on to Cognos for a passport at every TM1 login, so TM1 never
        // sees the password and an expired passport is never reused
        return Tm1Client
            .Builder()
            .Address("https://tm1.example.com:8010")
            .Authenticator(Tm1Authenticator.CognosLogon("https://cognos.example.com:9300", "LDAP", "alice", password))
            .Build();
        // @end
    }

    public static Tm1Client IdToken(Func<CancellationToken, ValueTask<string>> currentIdToken)
    {
        // @start region = id-token
        // An application that signed its user in with OpenID Connect hands over their ID
        // token; Cognos's OIDC namespace turns it into a passport
        return Tm1Client
            .Builder()
            .Address("https://tm1.example.com:8010")
            .Authenticator(Tm1Authenticator.CognosIdToken("https://cognos.example.com:9300", "OIDC", currentIdToken))
            .Build();
        // @end
    }
}

// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Auth;

namespace Examples;

/// <summary>Examples for <c>Tm1Authenticator</c>, shown in the guide.</summary>
public static class Tm1AuthenticatorExamples
{
    public static IReadOnlyList<Tm1Authenticator> Kinds(Func<CancellationToken, ValueTask<string>> passports)
    {
        // @start region = kinds
        // TM1's own security
        var basic = Tm1Authenticator.Basic("admin", "apple");
        // CAM, with a user and password
        var cam = Tm1Authenticator.CamNamespace("alice", "secret", "LDAP");
        // CAM, with a passport
        var passport = Tm1Authenticator.CamPassport(passports);
        // A passport from Cognos
        var cognos = Tm1Authenticator.CognosLogon("http://cognos:9300", "LDAP", "alice", "secret");
        // @end
        return [basic, cam, passport, cognos];
    }
}

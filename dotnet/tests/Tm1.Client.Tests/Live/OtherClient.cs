// SPDX-License-Identifier: Apache-2.0

using System;
using ServerThread = Skipcheck.Tm1.Client.Model.Thread;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>Clients beside <see cref="Live.Client"/>, logging in to the same TM1: as another session
/// (as Live.Client does), or as another user (with TM1's own security).</summary>
internal static class OtherClient
{
    public static Tm1Client Build(Func<Tm1ClientBuilder, Tm1ClientBuilder>? configure = null, string? user = null, string? password = null)
    {
        return (configure ?? (b => b))(Live.Builder(user, password)).Build();
    }
}

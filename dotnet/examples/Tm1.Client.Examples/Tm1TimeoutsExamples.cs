// SPDX-License-Identifier: Apache-2.0

using System;
using Skipcheck.Tm1.Client.Session;

namespace Examples;

/// <summary>Examples for <c>Tm1Timeouts</c>, shown in the guide.</summary>
public static class Tm1TimeoutsExamples
{
    public static Tm1Timeouts LongRequests()
    {
        // @start region = long-requests
        // Let a request go two hours without data, for a long process run synchronously
        var timeouts = Tm1Timeouts.Builder().Request(TimeSpan.FromHours(2)).Build();
        // @end
        return timeouts;
    }
}

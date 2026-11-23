// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>LogService</c>, shown in the guide.</summary>
public static class LogServiceExamples
{
    public static IAsyncEnumerable<MessageLogEntry> Newest(Tm1Client tm1)
    {
        // @start region = newest
        // The 50 newest entries, newest first
        return tm1.Logs.MessageLogAsync(true, ODataQuery.None().Top(50));
        // @end
    }

    public static IAsyncEnumerable<string> Follow(Tm1Client tm1)
    {
        // @start region = follow
        // Errors as they're written, until the enumeration is cancelled; each poll is every 5 s
        return tm1
            .Logs.TailMessageLogAsync(TimeSpan.FromSeconds(5))
            .Where(entry => entry.Level == LogLevel.Error)
            .Select(entry => $"{entry.Logger}: {entry.Message}");
        // @end
    }

    public static async IAsyncEnumerable<string> ProcessErrors(Tm1Client tm1)
    {
        // @start region = process-errors
        // What each error log of one process says
        await foreach (var file in tm1.Logs.ErrorLogFilesAsync())
        {
            if (file.Filename?.EndsWith("_Load Sales.log", StringComparison.Ordinal) == true)
            {
                yield return await tm1.Logs.ErrorLogContentAsync(file.Filename);
            }
        }
        // @end
    }

    public static async Task<long> Changes(Tm1Client tm1)
    {
        // @start region = changes
        // How many cell changes the transaction log holds, leaving out its status lines
        return await tm1.Logs.TransactionLogAsync(false, true).LongCountAsync();
        // @end
    }
}

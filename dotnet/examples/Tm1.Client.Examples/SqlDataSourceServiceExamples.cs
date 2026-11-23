// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using Skipcheck.Tm1.Client;

namespace Examples;

/// <summary>Examples for <c>SqlDataSourceService</c>, shown in the guide.</summary>
public static class SqlDataSourceServiceExamples
{
    public static IAsyncEnumerable<string> Names(Tm1Client tm1)
    {
        // @start region = names
        // The ODBC sources a process could read from, on TM1's host
        return tm1.SqlDataSources.ListAsync().Where(source => source.Name is not null).Select(source => source.Name!);
        // @end
    }
}

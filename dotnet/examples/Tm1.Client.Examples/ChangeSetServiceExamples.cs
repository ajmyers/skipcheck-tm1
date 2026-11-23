// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;

namespace Examples;

/// <summary>Examples for <c>ChangeSetService</c>, shown in the guide.</summary>
public static class ChangeSetServiceExamples
{
    public static async Task<string> Within(Tm1Client tm1)
    {
        // @start region = within
        // Record some writes in a change set, and keep its ID to undo them later
        var cell = new Dictionary<string, string>
        {
            ["Version"] = "Forecast",
            ["Region"] = "North",
            ["Month"] = "Jan",
            ["Measure"] = "Revenue",
        };
        return await tm1.ChangeSets.WithinAsync(async session =>
        {
            await Tm1Client.Of(session).Cells.UpdateAsync("Sales", cell, 1500);
            return session.ChangeSet()!;
        });
        // @end
    }

    public static async Task Undo(Tm1Client tm1, string changeSet)
    {
        // @start region = undo
        // Revert every write recorded in the change set; writes outside it stay
        await tm1.ChangeSets.UndoAsync(changeSet);
        // @end
    }
}

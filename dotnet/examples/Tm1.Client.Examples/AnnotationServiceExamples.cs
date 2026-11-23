// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;

namespace Examples;

/// <summary>Examples for <c>AnnotationService</c>, shown in the guide.</summary>
public static class AnnotationServiceExamples
{
    public static async Task<Annotation> Annotate(Tm1Client tm1)
    {
        // @start region = annotate
        // A comment on one cell, as Planning Analytics Workspace makes it: the cell's
        // elements by dimension, in the cube's dimension order
        var cell = new Dictionary<string, string>
        {
            ["Version"] = "Forecast",
            ["Region"] = "North",
            ["Measure"] = "Amount",
        };
        return await tm1.Annotations.CreateAsync("Sales", cell, "Includes the March promotion");
        // @end
    }

    public static IAsyncEnumerable<string> Read(Tm1Client tm1)
    {
        // @start region = read
        // GET Annotations lists none on TM1 11.8, so read them by cube
        return tm1.Annotations.CubeAnnotationsAsync("Sales").Select(annotation => $"{annotation.Creator}: {annotation.Text}");
        // @end
    }

    public static async Task Edit(Tm1Client tm1, string id)
    {
        // @start region = edit
        // TM1 keeps Text and the commentValue PAW shows apart, so change both
        var text = "Includes the March and April promotions";
        await tm1.Annotations.UpdateAsync(id, AnnotationPatch.Builder().Text(text).Property("commentValue", text).Build());
        // @end
    }
}

// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>MemberService</c>, shown in the guide.</summary>
public static class MemberServiceExamples
{
    public static async Task<string> UniqueName(Tm1Client tm1)
    {
        // @start region = unique-name
        // A member's unique name, such as [Region].[Region].[North], for use in MDX
        var north = await tm1.Members.GetAsync("Region", "Region", "North", ODataQuery.None().Select(Member.Fields.UniqueName));
        return north.UniqueName!;
        // @end
    }

    public static IAsyncEnumerable<string> Children(Tm1Client tm1)
    {
        // @start region = children
        // The children of Total, in order
        return tm1.Members.ChildrenAsync("Region", "Region", "Total", ODataQuery.None().Select(Member.Fields.Name)).Select(member => member.Name!);
        // @end
    }

    public static async Task<string> Parent(Tm1Client tm1)
    {
        // @start region = parent
        // The parent of North (the first, if it has several); a root has none
        var parent = await tm1.Members.ParentAsync("Region", "Region", "North", ODataQuery.None().Select(Member.Fields.Name));
        return parent?.Name ?? "(a root)";
        // @end
    }
}

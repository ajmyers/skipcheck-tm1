using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>SessionService</c>, shown in the guide.</summary>
public static class SessionServiceExamples
{
    public static IAsyncEnumerable<string> List(Tm1Client tm1)
    {
        // @start region = list
        // Every session on the server, with its user's name and its label
        return tm1
            .Sessions.ListAsync(
                ODataQuery.None().Select(Session.Fields.Id, Session.Fields.Context).Expand(Session.Fields.User, user => user.Select(User.Fields.Name))
            )
            .Select(session => $"{session.Id} {session.User?.Name} {session.Context}");
        // @end
    }

    public static async Task CloseLabelled(Tm1Client tm1)
    {
        // @start region = close
        // Close the sessions labelled "Nightly load", except this client's own
        var own = (await tm1.Sessions.ActiveSessionAsync(ODataQuery.None().Select(Session.Fields.Id))).Id;
        var labelled = ODataQuery.None().Select(Session.Fields.Id).Filter(Session.Fields.Context.Eq("Nightly load"));
        await foreach (var session in tm1.Sessions.ListAsync(labelled))
        {
            if (session.Id is { } id && id != own)
            {
                await tm1.Sessions.CloseAsync(id);
            }
        }
        // @end
    }
}

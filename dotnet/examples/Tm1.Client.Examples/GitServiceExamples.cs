using System;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.Services;

namespace Examples;

/// <summary>Examples for <c>GitService</c>, shown in the guide.</summary>
public static class GitServiceExamples
{
    public static async Task<Git> Init(Tm1Client tm1, string token)
    {
        // @start region = init
        // The remote's branch must already have a commit: TM1 can't make the first one
        var credentials = GitCredentials.Password("tm1-bot", token);
        return await tm1.Git.InitAsync("https://git.example.com/finance/model.git", "dev", credentials, false, null);
        // @end
    }

    public static async Task Push(Tm1Client tm1, GitCredentials credentials)
    {
        // @start region = push
        // Plan the commit, look at the files it would push, then push them
        var commit = new GitCommitDetails("Add the Region dimension", "Ada Analyst", "ada@example.com");
        var plan = await tm1.Git.PushAsync("main", null, commit, credentials, false);
        if (plan.SourceFiles is { Count: > 0 })
        {
            await tm1.Git.ExecuteAsync(plan);
        }
        // @end
    }

    public static async Task Pull(Tm1Client tm1, GitCredentials credentials, string newest)
    {
        // @start region = pull
        // TM1 11.8 plans the commit it had on the first pull after the remote moves on:
        // pull again until the plan is for the commit wanted, then carry it out
        for (var attempt = 0; attempt < 3; attempt++)
        {
            var plan = await tm1.Git.PullAsync("main", credentials, null, false);
            if (plan.Commit?.Id is { } id && newest.StartsWith(id, StringComparison.Ordinal))
            {
                await tm1.Git.ExecuteAsync(plan);
                return;
            }
        }
        // @end
    }
}

// SPDX-License-Identifier: Apache-2.0

import { GitCommitDetails, GitCredentials, type Git, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for GitService, quoted in its documentation.

export function init(tm1: Tm1Client, token: string): Promise<Git> {
  // @start region = init
  // The remote's branch must already have a commit: TM1 can't make the first one
  const credentials = GitCredentials.password("tm1-bot", token);
  return tm1.git.init("https://git.example.com/finance/model.git", "dev", credentials, false);
  // @end
}

export async function push(tm1: Tm1Client, credentials: GitCredentials): Promise<void> {
  // @start region = push
  // Plan the commit, look at the files it would push, then push them
  const commit = new GitCommitDetails("Add the Region dimension", "Ada Analyst", "ada@example.com");
  const plan = await tm1.git.push("main", null, commit, credentials, false);
  if (plan.SourceFiles?.length) await tm1.git.execute(plan);
  // @end
}

export async function pull(tm1: Tm1Client, credentials: GitCredentials, newest: string): Promise<void> {
  // @start region = pull
  // TM1 11.8 plans the commit it had on the first pull after the remote moves on: pull
  // again until the plan is for the commit wanted, then carry it out
  for (let attempt = 0; attempt < 3; attempt++) {
    const plan = await tm1.git.pull("main", credentials, null, false);
    if (plan.Commit?.ID && newest.startsWith(plan.Commit.ID)) {
      await tm1.git.execute(plan);
      return;
    }
  }
  // @end
}

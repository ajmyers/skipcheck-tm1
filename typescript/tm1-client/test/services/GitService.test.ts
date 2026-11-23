// SPDX-License-Identifier: Apache-2.0

import { inspect } from "node:util";
import { describe, expect, it } from "vitest";
import { GitCommitDetails, GitConfigurationPatch, GitCredentials, GitPlan, GitPlanExecutionMode, ODataQuery, typeOf } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

const COMMIT = new GitCommitDetails("Add a dimension", "tm1-client", "tm1-client@example.com");
const sent = (tm1: FakeTm1): string => `${tm1.last.method} ${tm1.last.target}`;

/** GitService against a fake TM1: each method sends what the Java client's does, with the
 * credentials it was given and no others (the Java client has no unit test of its own for these;
 * this is the .NET client's GitServiceTests, with Java's requests). */
describe("GitService", () => {
  it("inits, checks and uninits", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => ({ URL: "git://localhost/model.git", Remote: { Branches: ["main"] } });
    const git = await client.git.init("git://localhost/model.git", "dev", GitCredentials.password("me", "s3cret"), true, null);
    expect(git.Remote?.Branches).toEqual(["main"]);
    expect([sent(tm1), tm1.last.text]).toEqual([
      "POST GitInit",
      '{"URL":"git://localhost/model.git","Username":"me","Password":"s3cret","Deployment":"dev","Force":true}',
    ]);
    await client.git.init("git://localhost/model.git", null, GitCredentials.none(), false, GitConfigurationPatch.builder().build());
    expect(tm1.last.text).toBe('{"URL":"git://localhost/model.git","Force":false,"Config":{}}');

    await client.git.status(GitCredentials.sshKey("pub", "priv"));
    expect([sent(tm1), tm1.last.text]).toEqual(["POST GitStatus", '{"PublicKey":"pub","PrivateKey":"priv"}']);
    await client.git.status(GitCredentials.sshKey("pub", "priv", "phrase"));
    expect(tm1.last.text).toBe('{"PublicKey":"pub","PrivateKey":"priv","Passphrase":"phrase"}');

    tm1.respond = () => null;
    await client.git.uninit(true);
    expect([sent(tm1), tm1.last.text]).toEqual(["POST GitUninit", '{"Force":true}']);
    await client.git.deploy("git://localhost/model.git", "main", "prod", GitCredentials.none(), false, null);
    expect([sent(tm1), tm1.last.text]).toEqual(["POST GitDeploy", '{"URL":"git://localhost/model.git","Branch":"main","Deployment":"prod","Force":false}']);
  });

  it("plans and executes pushes and pulls", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    // As TM1 answers an action: no @odata.type for its declared return type
    tm1.respond = () => ({ ID: "p1", SourceFiles: ["/dimensions/Region.json"] });
    const push = await client.git.push("main", "feature", COMMIT, GitCredentials.none(), false);
    expect(push.SourceFiles).toEqual(["/dimensions/Region.json"]);
    expect(typeOf(GitPlan, push)).toBe("GitPushPlan");
    expect(sent(tm1)).toBe("POST GitPush");
    expect(tm1.last.text).toBe(
      '{"Branch":"main","NewBranch":"feature","Message":"Add a dimension","Author":"tm1-client","Email":"tm1-client@example.com","Force":false}',
    );

    tm1.respond = () => ({ "@odata.type": "#ibm.tm1.api.v1.GitPullPlan", ID: "p2", Operations: ["Skip Dimensions('Region')"] });
    const pull = await client.git.pull("main", GitCredentials.none(), GitPlanExecutionMode.MultipleCommit, true);
    expect(pull.Operations).toEqual(["Skip Dimensions('Region')"]);
    expect(typeOf(GitPlan, pull)).toBe("GitPullPlan");
    expect([sent(tm1), tm1.last.text]).toEqual(["POST GitPull", '{"Branch":"main","ExecutionMode":"MultipleCommit","Force":true}']);
    await client.git.pull("main", GitCredentials.none(), null, false);
    expect(tm1.last.text).toBe('{"Branch":"main","Force":false}');

    tm1.respond = () => null;
    await client.git.execute(push);
    expect([sent(tm1), tm1.last.text]).toEqual(["POST GitPlans('p1')/tm1.GitPushPlan/tm1.Execute", "{}"]);
    await client.git.execute(pull);
    expect(sent(tm1)).toBe("POST GitPlans('p2')/tm1.GitPullPlan/tm1.Execute");
    const count = tm1.requests.length;
    await expect(client.git.execute({ ID: "p3" })).rejects.toBeInstanceOf(RangeError);
    await expect(client.git.execute({ "@odata.type": "#ibm.tm1.api.v1.GitPushPlan" })).rejects.toBeInstanceOf(TypeError);
    expect(tm1.requests.length).toBe(count);
  });

  it("finds a plan in the list", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () =>
      FakeTm1.collection({ "@odata.type": "#ibm.tm1.api.v1.GitPushPlan", ID: "p1" }, { "@odata.type": "#ibm.tm1.api.v1.GitPullPlan", ID: "p2" });
    const plans = await client.git.plans(ODataQuery.none().select("ID")).toArray();
    expect(plans.map((p) => typeOf(GitPlan, p))).toEqual(["GitPushPlan", "GitPullPlan"]);
    expect(sent(tm1)).toBe("GET GitPlans?$select=ID");
    const found = await client.git.plan("p2");
    expect(found && typeOf(GitPlan, found)).toBe("GitPullPlan");
    expect(sent(tm1)).toBe("GET GitPlans");
    expect(await client.git.plan("gone")).toBeUndefined();
  });

  it("keeps passwords out of toString, JSON and inspect", () => {
    const password = GitCredentials.password("me", "s3cret");
    expect(password.toString()).toBe("GitCredentials[username=me, password=***, sshKey=none]");
    const ssh = GitCredentials.sshKey("pub", "priv", "phrase");
    expect(ssh.toString()).toBe("GitCredentials[username=null, password=none, sshKey=***]");
    for (const shown of [
      JSON.stringify(password),
      inspect(password),
      inspect({ nested: password }, { depth: 5, showHidden: true }),
      `${password}`,
      String(Object.keys(password)),
    ]) {
      expect(shown).not.toContain("s3cret");
    }
    for (const shown of [JSON.stringify(ssh), inspect(ssh, { showHidden: true }), JSON.stringify({ ssh })]) {
      expect(shown).not.toContain("priv");
      expect(shown).not.toContain("phrase");
    }
    // The values are still there to send
    expect([password.username, password.password, ssh.publicKey, ssh.privateKey, ssh.passphrase]).toEqual(["me", "s3cret", "pub", "priv", "phrase"]);
    expect(GitCredentials.none().equals(new GitCredentials(null, null, null, null, null))).toBe(true);
    expect(() => new GitCommitDetails("message", null as unknown as string, "a@example.com")).toThrow(TypeError);
    expect([COMMIT.message, COMMIT.author, COMMIT.email]).toEqual(["Add a dimension", "tm1-client", "tm1-client@example.com"]);
  });
});

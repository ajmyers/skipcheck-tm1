import { randomUUID } from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import {
  Dimension,
  DimensionPatch,
  ElementPatch,
  GitCommitDetails,
  GitCredentials,
  GitPlan,
  HierarchyPatch,
  ODataPath,
  Tm1BadRequestException,
  Tm1Exception,
  Tm1NotFoundException,
  Tm1Request,
  typeOf,
  type Tm1Client,
} from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

const COMMIT = new GitCommitDetails("Add a dimension", "tm1-client", "tm1-client@example.com");

/** The Git remote TM1 pushes to and pulls from: `TM1_GIT_URL`, a `git://` or `https://`
 * repository whose `main` has a commit. The Java test starts a `git daemon` container in TM1's
 * network (`GitDaemon`), and changes the remote behind TM1's back; starting containers comes with
 * the testcontainers port, so until then the steps that need that are left out, as in the .NET
 * client's live test with `TM1_GIT_URL`. */
const GIT_URL = process.env["TM1_GIT_URL"]?.trim() || undefined;
const gitTest: typeof it = (Live.configured && GIT_URL !== undefined ? it : it.skip) as typeof it;
const gitReason = liveReason || (GIT_URL === undefined ? " (skipped: no Git remote configured: set TM1_GIT_URL)" : "");

/** GitService against a real TM1: a model pushed to a Git remote and pulled back. The Java
 * client's `GitServiceTm1Test`. */
describe("GitService on TM1" + gitReason, () => {
  let tm1: Tm1Client;

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
  });

  // Dimensions through the session, until DimensionService is ported
  const dimensionPath = (name: string): ODataPath => ODataPath.of("Dimensions").key(name);
  async function deleteDimension(name: string): Promise<void> {
    await tm1.session.execute(Tm1Request.delete(dimensionPath(name))).catch((e: unknown) => {
      if (!(e instanceof Tm1NotFoundException)) throw e;
    });
  }
  async function fails(call: () => Promise<unknown>, message: string): Promise<void> {
    const e = await call().then(
      () => undefined,
      (x: unknown) => x,
    );
    expect(e).toBeInstanceOf(Tm1Exception);
    expect((e as Error).message).toContain(message);
  }

  gitTest("pushes the model and pulls it back", async () => {
    const url = GIT_URL!;
    const dimension = "git" + randomUUID().replace(/-/g, "").slice(0, 8);
    const copy = dimension + "copy";
    const none = GitCredentials.none();
    // Not initialized yet
    await expect(tm1.git.status(none)).rejects.toBeInstanceOf(Tm1BadRequestException);
    const tied = await tm1.git.init(url, "dev", none, true, null);
    try {
      expect(tied.Remote?.Branches).toContain("main");
      // Nothing has changed
      await fails(() => tm1.git.push("main", null, COMMIT, none, true), "Cannot create bare commit");

      await tm1.session.execute(
        Tm1Request.post(
          ODataPath.of("Dimensions"),
          DimensionPatch.builder()
            .name(dimension)
            .hierarchies([HierarchyPatch.builder().name(dimension).elements([ElementPatch.builder().name("North").build()]).build()])
            .build(),
        ),
      );
      const push = await tm1.git.push("main", null, COMMIT, none, true);
      expect(push.SourceFiles).toContain(`/dimensions/${dimension}.json`);
      const found = await tm1.git.plan(push.ID!);
      expect(found && typeOf(GitPlan, found)).toBe("GitPushPlan");
      await tm1.git.execute(push);
      // Executing a plan uses it up
      expect(await tm1.git.plan(push.ID!)).toBeUndefined();
      expect((await tm1.git.status(none)).DeployedCommit?.Summary).toBe("Add a dimension");

      await deleteDimension(dimension);
      const skip = await tm1.git.pull("main", none, null, true);
      // A pull compares commits, not the model: the local delete stands
      expect(skip.Operations).toEqual([`Skip Dimensions('${dimension}')`]);
      await tm1.git.execute(skip);

      // Refused while initialized
      await fails(() => tm1.git.deploy(url, "main", "prod", none, true, null), "Cannot re-deploy");
    } finally {
      await tm1.git.uninit(true);
      await deleteDimension(dimension);
      await deleteDimension(copy);
    }

    await tm1.git.deploy(url, "main", "dev", none, true, null);
    try {
      expect((await tm1.git.status(none)).DeployedCommit?.Summary).toBe("Add a dimension");
      // A deploy brings back the whole branch, the deleted dimension too
      expect((await tm1.session.execute(Tm1Request.get(dimensionPath(dimension), null, Dimension))).Name).toBe(dimension);
    } finally {
      await tm1.git.uninit(true);
      await deleteDimension(dimension);
      await deleteDimension(copy);
    }
  });

  it("keeps passwords out of toString", () => {
    expect(GitCredentials.password("me", "s3cret").toString()).toBe("GitCredentials[username=me, password=***, sshKey=none]");
  });
});

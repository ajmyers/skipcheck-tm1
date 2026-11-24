// SPDX-License-Identifier: Apache-2.0

import { randomUUID } from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { StartedTestContainer } from "testcontainers";
import {
  DimensionCreate,
  ElementCreate,
  GitCommitDetails,
  GitCredentials,
  GitPlan,
  HierarchyCreate,
  Tm1BadRequestException,
  Tm1Exception,
  Tm1NotFoundException,
  typeOf,
  type Tm1Client,
} from "../../src/index.js";
import { Live, liveReason } from "./live.js";

const COMMIT = new GitCommitDetails("Add a dimension", "tm1-client", "tm1-client@example.com");

const GIVEN = process.env["TM1_GIT_URL"]?.trim() || undefined;
const RUNNING = process.env["TM1_URL"]?.trim() || undefined;

const SEED = [
  "apk add -q --no-cache git git-daemon",
  "git init -q --bare -b main /srv/model.git",
  "git clone -q /srv/model.git /tmp/seed",
  "cd /tmp/seed",
  "echo 'A TM1 model' > README.md",
  "git add README.md",
  "git -c user.name=seed -c user.email=seed@example.com commit -qm Seed",
  "git push -q origin main",
  "exec git daemon --verbose --reuseaddr --base-path=/srv --export-all --enable=receive-pack /srv",
].join(" && ");

/** The Git remote TM1 pushes to and pulls from: `TM1_GIT_URL` if it's set (a `git://` or
 * `https://` repository whose `main` has a commit), or else, when the tests start TM1 in Docker
 * themselves, a `git daemon` serving one bare repository, `model.git`, with one commit on `main`,
 * in an Alpine container sharing the TM1 container's network, as the Java test's `GitDaemon`. TM1
 * reaches it at `git://localhost/model.git`; the test can change it behind TM1's back. */
class GitDaemon {
  static readonly URL = "git://localhost/model.git";

  private constructor(
    readonly url: string,
    private readonly container: StartedTestContainer | undefined,
  ) {}

  /** Whether it's the daemon here, which the test can change behind TM1's back. */
  get local(): boolean {
    return this.container !== undefined;
  }

  static async start(): Promise<GitDaemon> {
    if (GIVEN) return new GitDaemon(GIVEN, undefined);
    const { GenericContainer, Wait } = await import("testcontainers");
    const tm1 = (await Live.target()).containerId;
    if (!tm1) throw new Error("The Git daemon shares the TM1 container's network: there's no container");
    const container = await new GenericContainer("alpine:3.21")
      .withNetworkMode("container:" + tm1)
      .withCommand(["sh", "-c", SEED])
      .withWaitStrategy(Wait.forLogMessage(/Ready to rumble/))
      .withStartupTimeout(120_000)
      .start();
    return new GitDaemon(GitDaemon.URL, container);
  }

  async stop(): Promise<void> {
    await this.container?.stop();
  }

  private async sh(...commands: string[]): Promise<string> {
    const result = await this.container!.exec(["sh", "-c", commands.join(" && ")]);
    if (result.exitCode !== 0) throw new Error(result.output);
    return result.output;
  }

  /** The files on `main`, as Git sees them, one per line. */
  filesOnMain(): Promise<string> {
    return this.sh("git --git-dir=/srv/model.git ls-tree -r --name-only main");
  }

  /** Commits, on the remote's `main`, a copy of a dimension TM1 pushed, under another name: a
   * change made elsewhere for TM1 to pull. */
  async commitCopyOf(dimension: string, copy: string): Promise<void> {
    await this.sh(
      "cd /tmp/seed",
      "git pull -q origin main",
      `mkdir -p dimensions/${copy}.hierarchies`,
      `sed 's/${dimension}/${copy}/g' dimensions/${dimension}.json > dimensions/${copy}.json`,
      `sed 's/${dimension}/${copy}/g' dimensions/${dimension}.hierarchies/${dimension}.json > dimensions/${copy}.hierarchies/${copy}.json`,
      "git add dimensions",
      `git -c user.name=elsewhere -c user.email=elsewhere@example.com commit -qm 'Copy ${dimension}'`,
      "git push -q origin main",
    );
  }
}

const canRun = Live.configured && (GIVEN !== undefined || RUNNING === undefined);
const gitTest: typeof it = (canRun ? it : it.skip) as typeof it;
const gitReason = liveReason || (canRun ? "" : " (skipped: no Git remote configured (TM1_GIT_URL) for the TM1 at TM1_URL)");

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

  async function deleteDimension(name: string): Promise<void> {
    await tm1.dimensions.delete(name).catch((e: unknown) => {
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

  gitTest("pushes the model and pulls it back", { timeout: 300_000 }, async () => {
    const remote = await GitDaemon.start();
    try {
      const dimension = "git" + randomUUID().replace(/-/g, "").slice(0, 8);
      const copy = dimension + "copy";
      const none = GitCredentials.none();
      // Not initialized yet
      await expect(tm1.git.status(none)).rejects.toBeInstanceOf(Tm1BadRequestException);
      const tied = await tm1.git.init(remote.url, "dev", none, true, null);
      try {
        expect(tied.Remote?.Branches).toContain("main");
        // Nothing has changed
        await fails(() => tm1.git.push("main", null, COMMIT, none, true), "Cannot create bare commit");

        await tm1.dimensions.create(
          DimensionCreate.builder()
            .name(dimension)
            .hierarchies([
              HierarchyCreate.builder()
                .name(dimension)
                .elements([ElementCreate.builder().name("North").build()])
                .build(),
            ])
            .build(),
        );
        const push = await tm1.git.push("main", null, COMMIT, none, true);
        expect(push.SourceFiles).toContain(`/dimensions/${dimension}.json`);
        const found = await tm1.git.plan(push.ID!);
        expect(found && typeOf(GitPlan, found)).toBe("GitPushPlan");
        await tm1.git.execute(push);
        // Executing a plan uses it up
        expect(await tm1.git.plan(push.ID!)).toBeUndefined();
        if (remote.local) expect(await remote.filesOnMain()).toContain(`dimensions/${dimension}.json`);
        expect((await tm1.git.status(none)).DeployedCommit?.Summary).toBe("Add a dimension");

        await tm1.dimensions.delete(dimension);
        const skip = await tm1.git.pull("main", none, null, true);
        // A pull compares commits, not the model: the local delete stands
        expect(skip.Operations).toEqual([`Skip Dimensions('${dimension}')`]);
        await tm1.git.execute(skip);

        if (remote.local) {
          await remote.commitCopyOf(dimension, copy);
          const stale = await tm1.git.pull("main", none, null, true);
          // The first pull after the remote moves plans the commit TM1 had
          expect(stale.Commit?.Summary).toBe("Add a dimension");
          const pull = await tm1.git.pull("main", none, null, true);
          expect(pull.Commit?.Summary).toBe("Copy " + dimension);
          expect(pull.Operations).toContain(`Create Dimensions('${copy}')`);
          await tm1.git.execute(pull);
          expect((await tm1.dimensions.get(copy)).Name).toBe(copy);
        }

        // Refused while initialized
        await fails(() => tm1.git.deploy(remote.url, "main", "prod", none, true, null), "Cannot re-deploy");
      } finally {
        await tm1.git.uninit(true);
        await deleteDimension(dimension);
        await deleteDimension(copy);
      }

      await tm1.git.deploy(remote.url, "main", "dev", none, true, null);
      try {
        expect((await tm1.git.status(none)).DeployedCommit?.Summary).toBe(remote.local ? "Copy " + dimension : "Add a dimension");
        // A deploy brings back the whole branch, the deleted dimension too
        expect((await tm1.dimensions.get(dimension)).Name).toBe(dimension);
      } finally {
        await tm1.git.uninit(true);
        await deleteDimension(dimension);
        await deleteDimension(copy);
      }
    } finally {
      await remote.stop();
    }
  });

  it("keeps passwords out of toString", () => {
    expect(GitCredentials.password("me", "s3cret").toString()).toBe("GitCredentials[username=me, password=***, sshKey=none]");
  });
});

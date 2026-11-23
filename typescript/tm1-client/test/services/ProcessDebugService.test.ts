import { describe, expect, it } from "vitest";
import {
  ODataQuery,
  ProcessDebugContextBreakpointPatch,
  ProcessDebugContextLineBreakpointPatch,
  typeOf,
  ProcessDebugContextBreakpoint,
} from "../../src/index.js";
import { FakeTm1, type Recorded } from "../support/fake-tm1.js";

const context = (status: string): Record<string, unknown> => ({ ID: "d1", Status: status });
const sent = (r: Recorded): string => `${r.method} ${r.target}`;

/** ProcessDebugService against a fake TM1: each method sends what the Java client's does, and a run
 * is finished as Java finishes it (the .NET client's ProcessDebugServiceTests; the Java client has
 * live tests only). */
describe("ProcessDebugService", () => {
  it("debugs with parameters in order", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => context("Paused");
    const client = tm1.client();
    const parameters = new Map<string, unknown>([
      ["pYear", 2026],
      ["pVersion", "Actual"],
    ]);
    const started = await client.processDebugging.debug("Load", parameters, ODataQuery.none().expand("Breakpoints"));
    expect([started.ID, started.Status]).toEqual(["d1", "Paused"]);
    expect(sent(tm1.last)).toBe("POST Processes('Load')/tm1.Debug?$expand=Breakpoints");
    expect(tm1.last.text).toBe('{"Parameters":[{"Name":"pYear","Value":2026},{"Name":"pVersion","Value":"Actual"}]}');
    await client.processDebugging.debug("Load", {});
    expect([sent(tm1.last), tm1.last.text]).toEqual(["POST Processes('Load')/tm1.Debug", "{}"]);
  });

  it("lists, gets and steps", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection();
    const client = tm1.client();
    expect(await client.processDebugging.list().toArray()).toEqual([]);
    expect(sent(tm1.last)).toBe("GET ProcessDebugContexts");
    tm1.respond = () => context("Paused");
    expect((await client.processDebugging.get("d1", ODataQuery.none().select("Status"))).Status).toBe("Paused");
    expect(sent(tm1.last)).toBe("GET ProcessDebugContexts('d1')?$select=Status");

    for (const [call, action] of [
      [() => client.processDebugging.resume("d1"), "Continue"],
      [() => client.processDebugging.stepIn("d1"), "StepIn"],
      [() => client.processDebugging.stepOut("d1"), "StepOut"],
      [() => client.processDebugging.stepOver("d1"), "StepOver"],
    ] as const) {
      expect((await call()).ID).toBe("d1");
      expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", `ProcessDebugContexts('d1')/tm1.${action}`, "{}"]);
    }
    await client.processDebugging.stepOver("d1", ODataQuery.none().expand("CurrentBreakpoint"));
    expect(tm1.last.target).toBe("ProcessDebugContexts('d1')/tm1.StepOver?$expand=CurrentBreakpoint");
  });

  it("adds, lists, updates and removes breakpoints", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection({ "@odata.type": "#ibm.tm1.api.v1.ProcessDebugContextLineBreakpoint", ID: 1, Enabled: true, LineNumber: 3 });
    const client = tm1.client();
    const listed = await client.processDebugging.breakpoints("d1").toArray();
    expect(listed.map((b) => b.ID)).toEqual([1]);
    expect(typeOf(ProcessDebugContextBreakpoint, listed[0]!)).toBe("ProcessDebugContextLineBreakpoint");
    expect(sent(tm1.last)).toBe("GET ProcessDebugContexts('d1')/Breakpoints");

    tm1.respond = () => ({ ID: 1 });
    const line = ProcessDebugContextLineBreakpointPatch.builder().id(1).enabled(true).processName("Load").lineNumber(3).build();
    expect((await client.processDebugging.addBreakpoint("d1", line)).ID).toBe(1);
    expect(sent(tm1.last)).toBe("POST ProcessDebugContexts('d1')/Breakpoints");
    expect(tm1.last.text).toBe('{"@odata.type":"#ibm.tm1.api.v1.ProcessDebugContextLineBreakpoint","ID":1,"Enabled":true,"ProcessName":"Load","LineNumber":3}');
    await client.processDebugging.addBreakpoint("d1", ProcessDebugContextBreakpointPatch.builder().id(0).build());
    expect(tm1.last.text).toBe('{"ID":0}');

    tm1.respond = () => null;
    await client.processDebugging.updateBreakpoint("d1", 1, ProcessDebugContextBreakpointPatch.builder().enabled(false).build());
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["PATCH", "ProcessDebugContexts('d1')/Breakpoints(1)", '{"Enabled":false}']);
    await client.processDebugging.removeBreakpoint("d1", 1);
    expect(sent(tm1.last)).toBe("DELETE ProcessDebugContexts('d1')/Breakpoints(1)");
  });

  it("refuses a breakpoint without an ID of 0 or more", () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    expect(() => client.processDebugging.addBreakpoint("d1", ProcessDebugContextLineBreakpointPatch.builder().lineNumber(3).build())).toThrow(RangeError);
    expect(() => client.processDebugging.addBreakpoint("d1", ProcessDebugContextBreakpointPatch.builder().id(-1).build())).toThrow(RangeError);
    expect(() => client.processDebugging.addBreakpoint("d1", ProcessDebugContextBreakpointPatch.builder().id(null).build())).toThrow(RangeError);
    expect(tm1.requests.filter((r) => r.target.includes("Breakpoints"))).toEqual([]);
  });

  it("says where the run is", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => ({ ID: 2 });
    expect((await client.processDebugging.currentBreakpoint("d1"))?.ID).toBe(2);
    expect(sent(tm1.last)).toBe("GET ProcessDebugContexts('d1')/CurrentBreakpoint");
    tm1.respond = () => null;
    expect(await client.processDebugging.currentBreakpoint("d1", ODataQuery.none().select("ID"))).toBeUndefined();
    expect(tm1.last.target).toBe("ProcessDebugContexts('d1')/CurrentBreakpoint?$select=ID");
    tm1.respond = () => FakeTm1.collection({ ID: "f0", Procedure: "Prolog", LineNumber: 3 });
    const frames = await client.processDebugging.callStack("d1", ODataQuery.none().expand("Variables")).toArray();
    expect(frames.map((f) => [f.ID, f.Procedure, f.LineNumber])).toEqual([["f0", "Prolog", 3]]);
    expect(sent(tm1.last)).toBe("GET ProcessDebugContexts('d1')/CallStack?$expand=Variables");
    tm1.respond = () => FakeTm1.collection({ Name: "NVALUE", Value: "1" });
    expect((await client.processDebugging.variables("d1", "f0", ODataQuery.none().top(1)).toArray()).map((v) => v.Name)).toEqual(["NVALUE"]);
    expect(sent(tm1.last)).toBe("GET ProcessDebugContexts('d1')/CallStack('f0')/Variables?$top=1");
    tm1.respond = () => ({ Name: "Load" });
    expect((await client.processDebugging.frameProcess("d1", "f0")).Name).toBe("Load");
    expect(sent(tm1.last)).toBe("GET ProcessDebugContexts('d1')/CallStack('f0')/Process");
    expect((await client.processDebugging.process("d1", ODataQuery.none().select("Name"))).Name).toBe("Load");
    expect(sent(tm1.last)).toBe("GET ProcessDebugContexts('d1')/Process?$select=Name");
    tm1.respond = () => ({ ID: 42, ObjectName: "Load" });
    expect((await client.processDebugging.thread("d1")).ID).toBe(42);
    expect(sent(tm1.last)).toBe("GET ProcessDebugContexts('d1')/Thread");
  });

  it("finishes by removing the breakpoints it can and resuming to the end", async () => {
    const tm1 = new FakeTm1();
    let resumes = 0;
    tm1.respond = (r) => {
      if (r.target === "ProcessDebugContexts('d1')/Breakpoints") return FakeTm1.collection({ ID: 1 }, { ID: -1 }, {}, { ID: 4 });
      if (r.target === "ProcessDebugContexts('d1')/tm1.Continue")
        return { ...context(++resumes < 3 ? "Paused" : "Complete"), Result: { ProcessExecuteStatusCode: "CompletedSuccessfully" } };
      return null;
    };
    const done = await tm1.client().processDebugging.finish("d1");
    expect(done.Status).toBe("Complete");
    expect(done.Result?.ProcessExecuteStatusCode).toBe("CompletedSuccessfully");
    expect(tm1.requests.map(sent)).toEqual([
      "GET ProcessDebugContexts('d1')/Breakpoints",
      "DELETE ProcessDebugContexts('d1')/Breakpoints(1)",
      "DELETE ProcessDebugContexts('d1')/Breakpoints(4)",
      "POST ProcessDebugContexts('d1')/tm1.Continue",
      "POST ProcessDebugContexts('d1')/tm1.Continue",
      "POST ProcessDebugContexts('d1')/tm1.Continue",
    ]);
  });

  it("gives up finishing after a hundred resumes", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = (r) => (r.target.endsWith("/Breakpoints") ? FakeTm1.collection() : context("Paused"));
    const last = await tm1.client().processDebugging.finish("d1");
    expect(last.Status).toBe("Paused");
    expect(tm1.requests.filter((r) => r.target.endsWith("tm1.Continue")).length).toBe(100);
  });

  const debuggingTm1 = (): FakeTm1 => {
    const tm1 = new FakeTm1();
    tm1.respond = (r) => (r.target.endsWith("/Breakpoints") ? FakeTm1.collection() : context(r.target.endsWith("tm1.Debug") ? "Paused" : "Complete"));
    return tm1;
  };

  it("debugging finishes the run after the work", async () => {
    const tm1 = debuggingTm1();
    const client = tm1.client();
    const debugging = client.processDebugging.debugging("Load", null, async (c) => c.ID);
    expect(tm1.requests).toEqual([]); // nothing until it's iterated, as a Flux
    expect(await debugging.toArray()).toEqual(["d1"]);
    expect(tm1.requests.map(sent)).toEqual([
      "POST Processes('Load')/tm1.Debug",
      "GET ProcessDebugContexts('d1')/Breakpoints",
      "POST ProcessDebugContexts('d1')/tm1.Continue",
    ]);

    tm1.requests.length = 0;
    async function* twice(id: string): AsyncGenerator<string> {
      yield id;
      yield id;
    }
    expect(await client.processDebugging.debugging("Load", { p: 1 }, (c) => twice(c.ID!)).toArray()).toEqual(["d1", "d1"]);
    expect([sent(tm1.requests[0]!), tm1.requests[0]!.text]).toEqual(["POST Processes('Load')/tm1.Debug", '{"Parameters":[{"Name":"p","Value":1}]}']);
    expect(sent(tm1.last)).toBe("POST ProcessDebugContexts('d1')/tm1.Continue");

    // Work that gives nothing streams nothing
    tm1.requests.length = 0;
    expect(await client.processDebugging.debugging("Load", {}, async () => undefined).toArray()).toEqual([]);
    expect(sent(tm1.last)).toBe("POST ProcessDebugContexts('d1')/tm1.Continue");
  });

  it("debugging finishes the run when the work fails", async () => {
    const tm1 = debuggingTm1();
    const client = tm1.client();
    await expect(
      client.processDebugging
        .debugging("Load", null, () => {
          throw new Error("boom");
        })
        .toArray(),
    ).rejects.toThrow("boom");
    expect(sent(tm1.last)).toBe("POST ProcessDebugContexts('d1')/tm1.Continue");

    tm1.requests.length = 0;
    async function* failing(): AsyncGenerator<string> {
      yield "x";
      throw new Error("boom");
    }
    await expect(client.processDebugging.debugging("Load", null, () => failing()).toArray()).rejects.toThrow("boom");
    expect(sent(tm1.last)).toBe("POST ProcessDebugContexts('d1')/tm1.Continue");
  });

  it("debugging finishes the run when it's left early, as a cancelled Flux", async () => {
    const tm1 = debuggingTm1();
    async function* forever(): AsyncGenerator<number> {
      for (let i = 0; ; i++) yield i;
    }
    expect(
      await tm1
        .client()
        .processDebugging.debugging("Load", null, () => forever())
        .first(),
    ).toBe(0);
    expect(sent(tm1.last)).toBe("POST ProcessDebugContexts('d1')/tm1.Continue");
  });
});

import { afterAll, afterEach, beforeAll, beforeEach, describe, expect } from "vitest";
import {
  Filter,
  ODataQuery,
  Process,
  ProcessParameterPatch,
  ProcessPatch,
  Tm1NotFoundException,
  Tm1PreconditionFailedException,
  type ProcessExecuteStatusCode,
  type Tm1Client,
} from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

const PROLOG = `If(pCount = 2);
  ProcessQuit;
EndIf;
If(pCount = 3);
  ProcessError;
EndIf;
If(pCount = 4);
  CellPutN(1, 'NoSuchCube', 'x');
EndIf;
`;

function definition(name: string, prolog: string): ProcessPatch {
  return ProcessPatch.builder()
    .name(name)
    .parameters([
      ProcessParameterPatch.builder().name("pName").value("").type("String").build(),
      ProcessParameterPatch.builder().name("pCount").value(0).type("Numeric").build(),
    ])
    .prologProcedure(prolog)
    .build();
}

const parameters = (count: number): Map<string, unknown> =>
  new Map<string, unknown>([
    ["pName", "x"],
    ["pCount", count],
  ]);

/** ProcessService against a real TM1, on a process whose numeric parameter picks how it ends: the
 * Java client's `ProcessServiceTm1Test`. */
describe("ProcessService on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  let name: string;

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
  });

  beforeEach(async () => {
    if (!Live.configured) return;
    name = Live.name();
    await tm1.processes.create(definition(name, PROLOG));
  });

  afterEach(async () => {
    if (tm1) await tm1.processes.delete(name).catch(() => undefined);
  });

  const status = async (count: number): Promise<ProcessExecuteStatusCode | undefined> =>
    (await tm1.processes.executeWithReturn(name, parameters(count), ODataQuery.none())).ProcessExecuteStatusCode;

  tm1Test("creates, reads, updates and deletes a process", async () => {
    const read = await tm1.processes.get(name, ODataQuery.none());
    expect(read.PrologProcedure).toContain("ProcessQuit");
    expect((read.Parameters ?? []).map((p) => p.Name)).toEqual(["pName", "pCount"]);
    const listed = await tm1.processes.list(ODataQuery.none().select(Process.Fields.Name).filter(Filter.startsWith(Process.Fields.Name, name))).toArray();
    expect(listed.map((p) => p.Name)).toEqual([name]);

    await tm1.processes.update(name, ProcessPatch.builder().epilogProcedure("nX = 1;\n").build(), read["@odata.etag"]);
    expect((await tm1.processes.get(name, ODataQuery.none())).EpilogProcedure).toContain("nX = 1;");
    await expect(tm1.processes.update(name, ProcessPatch.builder().epilogProcedure("").build(), read["@odata.etag"])).rejects.toBeInstanceOf(
      Tm1PreconditionFailedException,
    );

    await tm1.processes.delete(name);
    await expect(tm1.processes.get(name, ODataQuery.none())).rejects.toBeInstanceOf(Tm1NotFoundException);
  });

  tm1Test("compiles saved and unsaved code", async () => {
    expect(await tm1.processes.compile(name).toArray()).toEqual([]);
    const errors = await tm1.processes.compile(definition("unsaved", "nX = ;\n")).toArray();
    expect(errors.map((e) => [e.Procedure, e.LineNumber])).toEqual([["Prolog", 1]]);
    await tm1.processes.update(name, ProcessPatch.builder().prologProcedure("nX = ;\n").build());
    expect(await tm1.processes.compile(name).toArray(), "saved, though it doesn't compile").toHaveLength(1);
  });

  tm1Test("runs and says how it ended", async () => {
    for (const count of [3, 4]) {
      const result = await tm1.processes.executeWithReturn(name, parameters(count), ODataQuery.none().expand("ErrorLogFile"));
      expect(result.ProcessExecuteStatusCode, "ProcessError, then a runtime error").toBe("Aborted");
      expect(result.ErrorLogFile?.Filename).toMatch(/^tm1processerror_/);
      expect(result.ErrorLogFile?.Filename?.endsWith(`_${name}.log`)).toBe(true);
    }
    expect(await status(1)).toBe("CompletedSuccessfully");
    expect(await status(2)).toBe("QuitCalled");
    expect(await status(3)).toBe("Aborted");
  });

  tm1Test("runs unsaved code, and the deprecated ways", async () => {
    expect((await tm1.processes.executeWithReturn(definition("unsaved", PROLOG), parameters(2), ODataQuery.none())).ProcessExecuteStatusCode).toBe(
      "QuitCalled",
    );
    await tm1.processes.executeWithReturn(name, parameters(3), ODataQuery.none());
    await tm1.processes.execute(name, parameters(1));
    await tm1.processes.execute(definition("unsaved", PROLOG), parameters(1));
    const logs = await tm1.processes.errorLogs(name, ODataQuery.none()).toArray();
    expect(logs, "the aborted run's").toHaveLength(1);
    expect(await tm1.processes.errorLogContent(name, logs[0]!), "keyed by its quoted timestamp").toContain("ProcessError");
  });
});

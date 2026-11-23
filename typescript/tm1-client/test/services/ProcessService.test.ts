import { describe, expect, it } from "vitest";
import { ODataQuery, ProcessPatch, Tm1PreconditionFailedException, Tm1UnsupportedOperationException } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

/** ProcessService against a fake TM1: each method sends what the Java client's does (the Java
 * client has live tests only for it; these are the .NET client's ProcessServiceTests). */
describe("ProcessService", () => {
  it("lists, gets, creates, updates and deletes", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection({ Name: "Load" });
    const client = tm1.client();
    const listed = await client.processes.list(ODataQuery.none().select("Name")).toArray();
    expect(listed.map((p) => p.Name)).toEqual(["Load"]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Processes?$select=Name");

    tm1.respond = () => ({ Name: "Load", "@odata.etag": 'W/"1"' });
    expect((await client.processes.get("Load"))["@odata.etag"]).toBe('W/"1"');
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Processes('Load')");
    const created = await client.processes.create(ProcessPatch.builder().name("Load").prologProcedure("x=1;").build());
    expect(created.Name).toBe("Load");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Processes", '{"Name":"Load","PrologProcedure":"x=1;"}']);

    tm1.respond = () => null;
    await client.processes.update("Load", ProcessPatch.builder().prologProcedure("x=2;").build());
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["PATCH", "Processes('Load')", '{"PrologProcedure":"x=2;"}']);
    expect(tm1.last.headers["if-match"]).toBeUndefined();
    await client.processes.update("Load", ProcessPatch.builder().prologProcedure("x=2;").build(), 'W/"1"');
    expect(tm1.last.headers["if-match"]).toBe('W/"1"');
    await client.processes.delete("Load");
    expect([tm1.last.method, tm1.last.target]).toEqual(["DELETE", "Processes('Load')"]);
  });

  it("refuses a stale ETag", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.reply(412, { error: { code: "278", message: "stale" } });
    await expect(tm1.client().processes.update("Load", ProcessPatch.builder().epilogProcedure("").build(), 'W/"0"')).rejects.toBeInstanceOf(
      Tm1PreconditionFailedException,
    );
  });

  it("compiles by name or by definition", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection({ Procedure: "Prolog", LineNumber: 3, Message: "bad" });
    const client = tm1.client();
    const errors = await client.processes.compile("Load").toArray();
    expect(errors.map((e) => [e.Procedure, e.LineNumber])).toEqual([["Prolog", 3]]);
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Processes('Load')/tm1.Compile", "{}"]);

    tm1.respond = () => FakeTm1.collection();
    expect(await client.processes.compile(ProcessPatch.builder().name("x").build()).toArray()).toEqual([]);
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "CompileProcess", '{"Process":{"Name":"x"}}']);
  });

  it("executes with return, with parameters in order", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => ({ ProcessExecuteStatusCode: "CompletedSuccessfully", ErrorLogFile: null });
    const client = tm1.client();
    const parameters = new Map<string, unknown>([
      ["pYear", 2026],
      ["pVersion", "Actual"],
    ]);
    const result = await client.processes.executeWithReturn("Load", parameters, ODataQuery.none().expand("ErrorLogFile"));
    expect(result.ProcessExecuteStatusCode).toBe("CompletedSuccessfully");
    expect(result.ErrorLogFile).toBeNull();
    expect([tm1.last.method, tm1.last.target]).toEqual(["POST", "Processes('Load')/tm1.ExecuteWithReturn?$expand=ErrorLogFile"]);
    expect(tm1.last.text).toBe('{"Parameters":[{"Name":"pYear","Value":2026},{"Name":"pVersion","Value":"Actual"}]}');

    // No parameters: none sent, as Java's for an empty map
    await client.processes.executeWithReturn("Load", {});
    expect([tm1.last.target, tm1.last.text]).toEqual(["Processes('Load')/tm1.ExecuteWithReturn", "{}"]);
    await client.processes.executeWithReturn("Load");
    expect(tm1.last.text).toBe("{}");

    tm1.respond = () => ({ ProcessExecuteStatusCode: "Aborted", ErrorLogFile: { Filename: "tm1processerror_x_adhoc.log" } });
    const adhoc = await client.processes.executeWithReturn(ProcessPatch.builder().name("adhoc").build(), { p: "v" }, ODataQuery.none().expand("ErrorLogFile"));
    expect(adhoc.ProcessExecuteStatusCode).toBe("Aborted");
    expect(adhoc.ErrorLogFile?.Filename).toBe("tm1processerror_x_adhoc.log");
    expect([tm1.last.method, tm1.last.target]).toEqual(["POST", "ExecuteProcessWithReturn?$expand=ErrorLogFile"]);
    expect(tm1.last.text).toBe('{"Process":{"Name":"adhoc"},"Parameters":[{"Name":"p","Value":"v"}]}');
  });

  it("reads a status this client doesn't know as Unknown", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => ({ ProcessExecuteStatusCode: "SomethingNew" });
    expect((await tm1.client().processes.executeWithReturn("Load")).ProcessExecuteStatusCode).toBe("Unknown");
  });

  it("runs the deprecated execute, and reads the deprecated error logs", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => null;
    const client = tm1.client();
    await client.processes.execute("Load", { p: 1 });
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Processes('Load')/tm1.Execute", '{"Parameters":[{"Name":"p","Value":1}]}']);
    await client.processes.execute(ProcessPatch.builder().name("adhoc").build());
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "ExecuteProcess", '{"Process":{"Name":"adhoc"}}']);
    await client.processes.execute(ProcessPatch.builder().name("adhoc").build(), new Map([["p", null]]));
    expect(tm1.last.text).toBe('{"Process":{"Name":"adhoc"},"Parameters":[{"Name":"p","Value":null}]}');

    tm1.respond = () => FakeTm1.collection({ Timestamp: "2026-01-02T03:04:05Z" });
    const logs = await client.processes.errorLogs("Load", ODataQuery.none().top(1)).toArray();
    expect(logs.map((l) => l.Timestamp)).toEqual(["2026-01-02T03:04:05Z"]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Processes('Load')/ErrorLogs?$top=1");

    tm1.respond = () => "line 1";
    expect(await client.processes.errorLogContent("Load", logs[0]!)).toBe("line 1");
    expect(tm1.last.method).toBe("GET");
    expect(tm1.last.headers["accept"]).toBe("text/plain, application/json");
    // Keyed by the timestamp as Java's OffsetDateTime writes it, quoted
    expect(decodeURIComponent(tm1.last.target)).toBe("Processes('Load')/ErrorLogs('2026-01-02T03:04:05Z')/Content");
    await client.processes.errorLogContent("Load", { Timestamp: "2026-01-02T03:04:00.000+00:00" });
    expect(decodeURIComponent(tm1.last.target)).toBe("Processes('Load')/ErrorLogs('2026-01-02T03:04Z')/Content");
    await client.processes.errorLogContent("Load", { Timestamp: "2026-01-02T03:04:05.120+0100" });
    expect(decodeURIComponent(tm1.last.target)).toBe("Processes('Load')/ErrorLogs('2026-01-02T03:04:05.120+01:00')/Content");
    const sent = tm1.requests.length;
    expect(() => client.processes.errorLogContent("Load", {})).toThrow(TypeError);
    expect(tm1.requests.length).toBe(sent);
  });

  it("checks what it needs against $metadata, as Java's does", async () => {
    const tm1 = new FakeTm1();
    tm1.productVersion = "11.8.02000.2";
    tm1.metadata = new TextEncoder().encode(`<?xml version="1.0" encoding="utf-8"?>
<edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx"><edmx:DataServices>
  <Schema Namespace="ibm.tm1.api.v1" Alias="tm1" xmlns="http://docs.oasis-open.org/odata/ns/edm">
    <Annotation Term="Core.SchemaVersion"><String>11.8.20</String></Annotation>
    <EntityType Name="Process"><Key><PropertyRef Name="Name"/></Key><Property Name="Name" Type="Edm.String"/></EntityType>
    <EntityContainer Name="API"><EntitySet Name="Processes" EntityType="tm1.Process"/></EntityContainer>
  </Schema></edmx:DataServices></edmx:Edmx>`);
    tm1.respond = () => null;
    const client = tm1.client();
    // The deprecated ones aren't checked, so they're sent
    await client.processes.execute("Load");
    await client.processes.execute(ProcessPatch.builder().name("x").build());
    expect(tm1.requests.map((r) => r.target)).toEqual(["Processes('Load')/tm1.Execute", "ExecuteProcess"]);
    await expect(client.processes.executeWithReturn("Load")).rejects.toBeInstanceOf(Tm1UnsupportedOperationException);
    await expect(client.processes.executeWithReturn(ProcessPatch.builder().name("x").build())).rejects.toBeInstanceOf(Tm1UnsupportedOperationException);
    await expect(client.processes.compile("Load").toArray()).rejects.toBeInstanceOf(Tm1UnsupportedOperationException);
    await expect(client.processes.compile(ProcessPatch.builder().name("x").build()).toArray()).rejects.toBeInstanceOf(Tm1UnsupportedOperationException);
    expect(tm1.requests.length).toBe(2);
  });
});

import { describe, expect, it } from "vitest";
import { MDXViewPatch, ODataQuery, Tm1PreconditionFailedException, View, ViewPatch, ViewScope, typeOf } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

/** ViewService against a fake TM1: public and private views by their navigation, as the Java
 * client's ViewService sends them (the .NET client's ViewServiceTests). */
describe("ViewService", () => {
  it("navigates each scope to its views", () => {
    expect(ViewScope.navigation(ViewScope.Public)).toBe("Views");
    expect(ViewScope.navigation(ViewScope.Private)).toBe("PrivateViews");
    expect(() => ViewScope.navigation("Shared" as ViewScope)).toThrow(RangeError);
  });

  it("lists and gets in either scope", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection({ "@odata.type": "#ibm.tm1.api.v1.MDXView", Name: "Default" });
    const client = tm1.client();
    const views = await client.views.list("Sales", ViewScope.Public, ODataQuery.none().select("Name")).toArray();
    expect(views.map((v) => v.Name)).toEqual(["Default"]);
    expect(typeOf(View, views[0]!)).toBe("MDXView");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Cubes('Sales')/Views?$select=Name");
    await client.views.list("Sales", ViewScope.Private).toArray();
    expect(tm1.last.target).toBe("Cubes('Sales')/PrivateViews");

    tm1.respond = () => ({ Name: "Default" });
    const view = await client.views.get("Sales", ViewScope.Private, "Default");
    expect(view.Name).toBe("Default");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Cubes('Sales')/PrivateViews('Default')");
  });

  it("gets a native view by its cast", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => ({ "@odata.type": "#ibm.tm1.api.v1.NativeView", Name: "Default", SuppressEmptyRows: true });
    const view = await tm1.client().views.getNative("Sales", ViewScope.Public, "Default", ODataQuery.none().expand("Columns"));
    expect([view.Name, view.SuppressEmptyRows]).toEqual(["Default", true]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Cubes('Sales')/Views('Default')/ibm.tm1.api.v1.NativeView?$expand=Columns");
  });

  it("creates, updates and deletes", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => ({ Name: "Mine" });
    const created = await client.views.create("Sales", ViewScope.Private, ViewPatch.builder().name("Mine").build());
    expect(created.Name).toBe("Mine");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Cubes('Sales')/PrivateViews", '{"Name":"Mine"}']);
    await client.views.create("Sales", ViewScope.Public, MDXViewPatch.builder().name("Mdx").mdx("SELECT").build());
    expect([tm1.last.target, tm1.last.json]).toEqual(["Cubes('Sales')/Views", { "@odata.type": "#ibm.tm1.api.v1.MDXView", Name: "Mdx", MDX: "SELECT" }]);

    tm1.respond = () => null;
    await client.views.update("Sales", ViewScope.Public, "Default", ViewPatch.builder().name("Renamed").build());
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["PATCH", "Cubes('Sales')/Views('Default')", '{"Name":"Renamed"}']);
    expect(tm1.last.headers["if-match"]).toBeUndefined();
    await client.views.update("Sales", ViewScope.Public, "Default", ViewPatch.builder().name("Renamed").build(), 'W/"3"');
    expect(tm1.last.headers["if-match"]).toBe('W/"3"');

    await client.views.delete("Sales", ViewScope.Public, "Default");
    expect([tm1.last.method, tm1.last.target]).toEqual(["DELETE", "Cubes('Sales')/Views('Default')"]);
  });

  it("refuses a stale ETag", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.reply(412, { error: { code: "278", message: "stale" } });
    await expect(tm1.client().views.update("Sales", ViewScope.Public, "Mdx", MDXViewPatch.builder().mdx("x").build(), 'W/"0"')).rejects.toBeInstanceOf(Tm1PreconditionFailedException);
  });

  it("reads the view's cube", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => ({ Name: "Sales" });
    const cube = await tm1.client().views.cube("Sales", ViewScope.Public, "Default", ODataQuery.none().select("Name"));
    expect(cube.Name).toBe("Sales");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Cubes('Sales')/Views('Default')/Cube?$select=Name");
  });
});

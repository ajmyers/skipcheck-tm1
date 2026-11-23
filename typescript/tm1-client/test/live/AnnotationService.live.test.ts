import { afterAll, beforeAll, describe, expect } from "vitest";
import { AnnotationPatch, ODataQuery, Tm1NotFoundException, type Tm1Client } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

const CUBE = "}ClientGroups";

const cell = (): Map<string, string> =>
  new Map([
    ["}Clients", "Admin"],
    ["}Groups", "ADMIN"],
  ]);

/** AnnotationService against a real TM1: comments on cells, found by cube, cell and ID, and their
 * facets: the Java client's `AnnotationServiceTm1Test`. */
describe("AnnotationService on TM1" + liveReason, () => {
  let tm1: Tm1Client;

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
  });

  tm1Test("annotates a cell and finds it by cube, cell and ID", async () => {
    const made = await tm1.annotations.create(CUBE, cell(), "first note");
    const id = made.ID!;
    try {
      expect(id.startsWith("C" + CUBE + "~")).toBe(true);
      expect(Object.keys(made)).toEqual(expect.arrayContaining(["objectName", "commentValue", "commentLocation"]));
      expect(made["commentLocation"]).toBe("Admin,ADMIN");

      expect(await tm1.annotations.list().toArray(), "TM1 lists none here").toEqual([]);
      expect((await tm1.annotations.cubeAnnotations(CUBE).toArray()).map((a) => a.ID)).toContain(id);
      const cellset = await tm1.cellsets.execute(`SELECT {[}Clients].[Admin]} ON 0, {[}Groups].[ADMIN]} ON 1 FROM [${CUBE}]`);
      try {
        expect((await tm1.annotations.cellAnnotations(cellset.ID!, 0).toArray()).map((a) => a.ID)).toEqual([id]);
      } finally {
        await tm1.cellsets.delete(cellset.ID!);
      }

      expect((await tm1.annotations.dimensionalContext(id, ODataQuery.none().select("UniqueName")).toArray()).map((m) => m.UniqueName)).toEqual([
        "[}Clients].[}Clients].[Admin]",
        "[}Groups].[}Groups].[ADMIN]",
      ]);
      expect(await tm1.annotations.applicationContext(id).toArray(), "reads back empty, though the facet value exists").toEqual([]);
      expect((await tm1.annotations.facetValues("}Cubes").toArray()).map((v) => v.Value)).toContain(CUBE);
      expect((await tm1.annotations.facet("}Cubes", CUBE)).Name).toBe("}Cubes");
      expect((await tm1.annotations.facets().toArray()).map((f) => f.Name)).toContain("}Cubes");
      expect(await tm1.annotations.attachments(id).toArray()).toEqual([]);

      await tm1.annotations.update(id, AnnotationPatch.builder().text("second note").build());
      const changed = await tm1.annotations.get(id);
      expect(changed.Text).toBe("second note");
      expect(changed.LastUpdatedBy).not.toBeNull();
      expect(changed.LastUpdatedBy).toBeDefined();
      expect(changed["commentValue"], "kept apart from Text").toBe("first note");
    } finally {
      await tm1.annotations.delete(id);
    }
    await expect(tm1.annotations.get(id)).rejects.toBeInstanceOf(Tm1NotFoundException);
  });

  tm1Test("deletes every annotation artifact", async () => {
    await tm1.annotations.create(CUBE, cell(), "to be swept");
    await tm1.annotations.deleteAnnotationArtifacts();
    expect(await tm1.annotations.cubeAnnotations(CUBE).toArray()).toEqual([]);
  });
});

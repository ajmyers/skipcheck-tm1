import { describe, expect } from "vitest";
import {
  allOperations,
  Cube,
  CubePatch,
  Dimension,
  DimensionPatch,
  Element,
  ElementPatch,
  ElementType,
  HierarchyPatch,
  MDXViewPatch,
  Member,
  ODataPath,
  ODataQuery,
  Server,
  Tm1Request,
  typeOf,
  View,
  type MDXView,
  type Tm1Session,
} from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";
import { sessions } from "./raw-tm1.js";

const rules = async (session: Tm1Session, cube: ODataPath): Promise<string | null | undefined> =>
  (await session.execute(Tm1Request.get(cube, ODataQuery.none().select(Cube.Fields.Rules), Cube))).Rules;

/** The generated model, read straight from a real TM1 through the session: the Java client's
 * `GeneratedModelTm1Test`. Java's records are interfaces here: a subtype is told by `typeOf`. */
describe("The generated model on TM1" + liveReason, () => {
  const name = (): string => "tm1-client-" + crypto.randomUUID();

  tm1Test("reads the server and streams dimensions into generated types", async () => {
    const session = (await sessions()).build();
    try {
      const server = await session.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.none(), Server));
      // What the container read as it started; a server at TM1_URL has only the session's reading
      const version = (await Live.target()).version ?? (await session.version());
      expect(server.ProductVersion).toBe(version.toString());
      expect(server["@odata.etag"], "TM1 sends no ETag for the Server singleton").toBeUndefined();

      const dimensions = await session.stream(Tm1Request.list(ODataPath.of("Dimensions"), ODataQuery.none().expand("Hierarchies"), Dimension)).toArray();
      expect(dimensions).not.toEqual([]);
      for (const d of dimensions) {
        expect(d.Name?.trim()).toBeTruthy();
        expect(d["@odata.etag"]?.trim()).toBeTruthy();
        expect(d.Hierarchies ?? []).not.toEqual([]);
      }
    } finally {
      await session.close();
    }
  });

  tm1Test("creates a view as its subtype and reads it back through the hierarchy", async () => {
    const session = (await sessions()).build();
    const view = name();
    const views = ODataPath.of("Cubes").key("}ClientGroups").nav("Views");
    try {
      const created = await session.execute(
        Tm1Request.post(views, MDXViewPatch.builder().name(view).mdx("SELECT {[}Groups].[}Groups].MEMBERS} ON 0 FROM [}ClientGroups]").build(), View),
      );
      expect(typeOf(View, created)).toBe("MDXView");
      expect((created as MDXView).MDX).toContain("}Groups");

      const all = await session.stream(Tm1Request.list(views, ODataQuery.none(), View)).toArray();
      expect(all.filter((v) => v.Name === view).map((v) => typeOf(View, v))).toEqual(["MDXView"]);

      const members = await session
        .stream(Tm1Request.list(ODataPath.of("Dimensions").key("}Groups").nav("Hierarchies").key("}Groups").nav("Members"), ODataQuery.none(), Member))
        .toArray();
      expect(members).not.toEqual([]);
      // Plain members, none of Member's subtypes
      expect(members.filter((m) => typeOf(Member, m) !== "Member")).toEqual([]);
    } finally {
      await session.execute(Tm1Request.delete(views.key(view))).catch(() => undefined);
      await session.close();
    }
  });

  tm1Test("queries with field constants and enum literals", async () => {
    const session = (await sessions()).build();
    const elements = ODataPath.of("Dimensions").key("}Groups").nav("Hierarchies").key("}Groups").nav("Elements");
    try {
      const all = await session
        .stream(Tm1Request.list(elements, ODataQuery.none().select(Element.Fields.Name, Element.Fields.Type).orderBy(Element.Fields.Name), Element))
        .toArray();
      expect(all).not.toEqual([]);
      const listed = all.map((e) => e.Name!);
      // TM1 sorts by code point, as a plain sort does
      expect(listed).toEqual([...listed].sort());
      const type = all[0]!.Type!;
      expect(type).toBeTruthy();
      expect(type).not.toBe(ElementType.Unknown);

      const filtered = await session
        .stream(Tm1Request.list(elements, ODataQuery.none().filter(Element.Fields.Type.eq(ElementType.literal(type))), Element))
        .toArray();
      expect(filtered).toHaveLength(all.filter((e) => e.Type === type).length);
      expect(filtered.filter((e) => e.Type !== type)).toEqual([]);
    } finally {
      await session.close();
    }
  });

  tm1Test("creates a dimension and a cube from patches, then patches and clears its rules", async () => {
    const session = (await sessions()).build();
    const n = name();
    const dimension = ODataPath.of("Dimensions").key(n);
    const cube = ODataPath.of("Cubes").key(n);
    try {
      await session.execute(
        Tm1Request.post(
          ODataPath.of("Dimensions"),
          DimensionPatch.builder()
            .name(n)
            .hierarchies([
              HierarchyPatch.builder()
                .name(n)
                .elements([
                  ElementPatch.builder().name("North").type(ElementType.Numeric).build(),
                  ElementPatch.builder().name("South").type(ElementType.Numeric).build(),
                ])
                .build(),
            ])
            .build(),
          Dimension,
        ),
      );
      await session.execute(
        Tm1Request.post(ODataPath.of("Cubes"), CubePatch.builder().name(n).bindDimensions(dimension, ODataPath.of("Dimensions").key("}Groups")).build(), Cube),
      );

      const created = await session.execute(
        Tm1Request.get(
          cube,
          ODataQuery.none().expand(Cube.Fields.Dimensions, (d) => d.select(Dimension.Fields.Name)),
          Cube,
        ),
      );
      expect(
        (created.Dimensions ?? []).map((d) => d.Name),
        "TM1 lists its virtual Sandboxes dimension first",
      ).toEqual(["Sandboxes", n, "}Groups"]);
      const elements = await session.stream(Tm1Request.list(dimension.nav("Hierarchies").key(n).nav("Elements"), ODataQuery.none(), Element)).toArray();
      expect(elements.map((e) => [e.Name, e.Type]).sort()).toEqual([
        ["North", ElementType.Numeric],
        ["South", ElementType.Numeric],
      ]);

      await session.execute(Tm1Request.patch(cube, CubePatch.builder().rules("SKIPCHECK;").build()));
      expect(await rules(session, cube)).toContain("SKIPCHECK");
      await session.execute(Tm1Request.patch(cube, CubePatch.builder().rules(null).build()));
      expect((await rules(session, cube)) ?? "", "an explicit null clears them").toBe("");
    } finally {
      await session.execute(Tm1Request.delete(cube)).catch(() => undefined);
      await session.execute(Tm1Request.delete(dimension)).catch(() => undefined);
      await session.close();
    }
  });

  tm1Test("the server has every operation in the catalog", async () => {
    const session = (await sessions()).build();
    try {
      const capabilities = await session.capabilities();
      expect(allOperations).not.toEqual([]);
      expect(allOperations.filter((op) => !capabilities.supports(op.capability())).map((op) => op.endpoint())).toEqual([]);
    } finally {
      await session.close();
    }
  });
});

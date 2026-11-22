import { ODataPath, ODataQuery, Tm1Request, type Tm1Client } from "../../src/index.js";

/** Fixtures for the data and view live tests, through the session until the dimension, subset and
 * process services are ported. */
export const Fixtures = {
  /** Creates a dimension with a same-named hierarchy of these elements (numeric unless given), and
   * these edges (parent, component), weight 1. */
  async dimension(tm1: Tm1Client, name: string, elements: (string | [string, string])[], edges: [string, string][] = []): Promise<string> {
    await tm1.session.execute(
      Tm1Request.post(ODataPath.of("Dimensions"), {
        Name: name,
        Hierarchies: [
          {
            Name: name,
            Elements: elements.map((e) => (typeof e === "string" ? { Name: e, Type: "Numeric" } : { Name: e[0], Type: e[1] })),
            Edges: edges.map(([parent, component]) => ({ ParentName: parent, ComponentName: component, Weight: 1 })),
          },
        ],
      }),
    );
    return name;
  },

  /** A public subset "All" of every element of a dimension's same-named hierarchy. */
  async subsetAll(tm1: Tm1Client, dimension: string): Promise<void> {
    await tm1.session.execute(
      Tm1Request.post(Fixtures.dimensionPath(dimension).nav("Hierarchies").key(dimension).nav("Subsets"), { Name: "All", Expression: `{TM1SUBSETALL([${dimension}].[${dimension}])}` }),
    );
  },

  async deleteDimension(tm1: Tm1Client, name: string): Promise<void> {
    await tm1.session.execute(Tm1Request.delete(Fixtures.dimensionPath(name))).catch(() => undefined);
  },

  dimensionPath(name: string): ODataPath {
    return ODataPath.of("Dimensions").key(name);
  },

  /** The path of the public subset "All" of a dimension's same-named hierarchy. */
  subset(dimension: string): ODataPath {
    return Fixtures.dimensionPath(dimension).nav("Hierarchies").key(dimension).nav("Subsets").key("All");
  },

  /** The first cell's value of a query, through a cellset stream. */
  async value(tm1: Tm1Client, mdx: string): Promise<unknown> {
    return tm1.cellsets.stream(mdx, ODataQuery.none().select("Ordinal", "Value"), 10, (_, cells) => cells.map((c) => c.Value)).first();
  },

  /** A cell's value as a number: an empty cell is 0. */
  async number(tm1: Tm1Client, mdx: string): Promise<number> {
    const v = await Fixtures.value(tm1, mdx);
    return v == null ? 0 : Number(v);
  },
};

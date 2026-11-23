import { afterEach, beforeEach } from "vitest";
import { CubePatch, type Tm1Client } from "../../src/index.js";
import { Fixtures } from "./data-fixtures.js";
import { Live } from "./live.js";

/** The Java client's `SandboxAndChangeSetTm1Test` fixture: a cube of two dimensions with cells X and
 * Y, made for each test, with a client of its own. */
export function twoByTwo(): {
  tm1: () => Tm1Client;
  cube: () => string;
  cell: (element: string) => Map<string, string>;
  value: (client: Tm1Client, element: string) => Promise<number>;
} {
  let tm1: Tm1Client | undefined;
  let cube = "";
  const a = (): string => cube + "_a";
  const b = (): string => cube + "_b";

  beforeEach(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
    cube = Live.name();
    for (const d of [a(), b()]) await Fixtures.dimension(tm1, d, ["X", "Y"]);
    await tm1.cubes.create(CubePatch.builder().name(cube).bindDimensions(Fixtures.dimensionPath(a()), Fixtures.dimensionPath(b())).build());
  });

  afterEach(async () => {
    if (!tm1) return;
    // The sandboxes the tests make (Java deletes all of the user's)
    for (const s of await tm1.sandboxes.list().toArray()) if (s.Name === "S" || s.Name === "T") await tm1.sandboxes.delete(s.Name).catch(() => undefined);
    await tm1.cubes.delete(cube).catch(() => undefined);
    for (const d of [a(), b()]) await Fixtures.deleteDimension(tm1, d);
    await tm1.close();
    tm1 = undefined;
  });

  return {
    tm1: () => tm1!,
    cube: () => cube,
    cell: (element) =>
      new Map([
        [a(), element],
        [b(), element],
      ]),
    value: (client, element) => Fixtures.number(client, `SELECT {[${b()}].[${b()}].[${element}]} ON 0, {[${a()}].[${a()}].[${element}]} ON 1 FROM [${cube}]`),
  };
}

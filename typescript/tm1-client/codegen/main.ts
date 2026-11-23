// SPDX-License-Identifier: Apache-2.0

// Generates the TypeScript client's model from the language-neutral API spec (spec/tm1-api.json),
// as tm1-codegen does for Java, tm1_pygen for Python and Tm1.Codegen for .NET: run by `npm run
// generate` (before build and test) into src/generated/, never committed.
//
//   tsx codegen/main.ts <spec.json> <output directory>

import { mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { emitModel, emitSchema } from "./emit-model.js";
import { emitOperations } from "./emit-operations.js";
import { emitPatches } from "./emit-patches.js";
import { HEADER, Spec, type SpecDocument } from "./spec.js";

export const SUPPORTED_SPEC_VERSION = 1;

export function generate(doc: SpecDocument): Record<string, string> {
  if (doc.specVersion !== SUPPORTED_SPEC_VERSION) {
    throw new Error(`spec version ${doc.specVersion}; this generator reads ${SUPPORTED_SPEC_VERSION}`);
  }
  const spec = new Spec(doc);
  return {
    "model.ts": emitModel(spec),
    "patches.ts": emitPatches(spec),
    "operations.ts": emitOperations(spec),
    "schema.ts": emitSchema(spec),
    "index.ts": HEADER + 'export * from "./model.js";\nexport * from "./patches.js";\nexport * from "./operations.js";\n',
  };
}

function main(args: string[]): number {
  if (args.length !== 2) {
    console.error("Usage: tsx codegen/main.ts <spec.json> <output directory>");
    return 2;
  }
  const [specFile, outDir] = args as [string, string];
  const files = generate(JSON.parse(readFileSync(specFile, "utf8")) as SpecDocument);
  mkdirSync(outDir, { recursive: true });
  for (const old of readdirSync(outDir)) rmSync(join(outDir, old), { recursive: true, force: true });
  for (const [name, text] of Object.entries(files)) writeFileSync(join(outDir, name), text.replace(/\r\n/g, "\n"));
  return 0;
}

if (import.meta.url === `file://${process.argv[1]}` || process.argv[1]?.endsWith("main.ts")) {
  process.exitCode = main(process.argv.slice(2));
}

// SPDX-License-Identifier: Apache-2.0

// Runs the installed packages, as someone who installed them from npm would: the guide's Hello
// World against a fake TM1 on localhost, and the testcontainers package's exports and the script
// it ships. It runs in a project that has only the packed tarballs installed, so nothing here can
// come from the sources. Its argument is the repository's root. See package-tests/README.md.
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { createServer } from "node:http";
import { dirname, join, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { Cube, ODataQuery, Tm1Client } from "@skipcheck/tm1-client";
import * as model from "@skipcheck/tm1-client/model";
import { PawContainer, Tm1ServerContainer, Tm1Source } from "@skipcheck/tm1-testcontainers";

const repo = process.argv[2];
assert.ok(repo, "usage: node smoke.mjs <repository root>");
const snapshot = readFileSync(join(repo, "java", "tm1-client", "src", "model", "metadata", "tm1-schema-11.8.35.xml"));

// Answers what Hello World asks: a login, the version, $metadata and the cubes
const requests = [];
const server = createServer((request, response) => {
  requests.push(`${request.method} ${request.url}`);
  const json = (body, headers = {}) => response.writeHead(200, { "Content-Type": "application/json", ...headers }).end(JSON.stringify(body));
  if (request.method === "POST") response.writeHead(204).end();
  else if (request.url.startsWith("/api/v1/ActiveSession")) json({ ID: 1 }, { "Set-Cookie": "TM1SessionId=s1; Path=/api/; HttpOnly" });
  else if (request.url.startsWith("/api/v1/Server")) json({ ProductVersion: "11.8.03500.6" });
  else if (request.url === "/api/v1/$metadata") response.writeHead(200, { "Content-Type": "application/xml" }).end(snapshot);
  else if (request.url.startsWith("/api/v1/ModelCubes()") || request.url.startsWith("/api/v1/Cubes")) json({ value: [{ Name: "Sales" }, { Name: "Plan" }] });
  else response.writeHead(404).end();
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));

// The guide's Hello World (typescript/tm1-client/examples/HelloWorld.ts)
let version;
const cubes = [];
const tm1 = Tm1Client.builder().baseUrl(`http://127.0.0.1:${server.address().port}/api/v1/`).basicAuth("admin", "").build();
try {
  version = String(await tm1.session.version());
  for await (const cube of tm1.cubes.modelCubes(ODataQuery.none().select(Cube.Fields.Name))) cubes.push(cube.Name);
} finally {
  await tm1.close();
  server.close();
  server.closeAllConnections();
}
assert.ok(version.startsWith("11.8"), version);
assert.deepEqual(cubes, ["Sales", "Plan"]);
assert.ok(requests.some((r) => r.startsWith("POST /api/v1/ActiveSession/tm1.Close")), requests.join("\n"));
assert.ok(Object.keys(model).length > 100, "the generated model is in the package");

// tm1-testcontainers: its exports, its vitest entry point, the script it ships, and the license
assert.ok(PawContainer && Tm1ServerContainer && Tm1Source);
const packages = {};
for (const name of ["@skipcheck/tm1-client", "@skipcheck/tm1-testcontainers"]) {
  const entry = fileURLToPath(import.meta.resolve(name));
  assert.ok(entry.includes(`${sep}node_modules${sep}`), `${name} is imported from ${entry}, not from an installed package`);
  const root = dirname(dirname(entry));
  for (const file of ["LICENSE", "NOTICE", "README.md"]) assert.ok(existsSync(join(root, file)), `${name} doesn't carry ${file}`);
  packages[name] = JSON.parse(readFileSync(join(root, "package.json"), "utf8")).version;
}
const testcontainers = dirname(dirname(fileURLToPath(import.meta.resolve("@skipcheck/tm1-testcontainers"))));
assert.ok(existsSync(join(testcontainers, "paw-local.sh")), "paw-local.sh isn't in the package");
assert.ok(existsSync(fileURLToPath(import.meta.resolve("@skipcheck/tm1-testcontainers/vitest"))), "the vitest entry point isn't in the package");

console.log(`ok: Hello from TM1 ${version}, cubes ${cubes}, packages ${JSON.stringify(packages)}`);

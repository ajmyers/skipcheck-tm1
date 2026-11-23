// Copies docker/paw/paw-local.sh into the package, as the Java build puts it in its jar and the
// .NET build embeds it in its assembly. PawContainer reads the copy (or, in this repository, the
// original), and builds its runner image from it.
import { copyFileSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const from = join(here, "..", "..", "..", "docker", "paw", "paw-local.sh");
const to = join(here, "..", "paw-local.sh");
if (!existsSync(from)) throw new Error("Can't find " + from);
copyFileSync(from, to);

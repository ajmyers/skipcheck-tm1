// Copies the repository's LICENSE and NOTICE into the package being packed (npm's prepack runs this
// in the package's folder), so the tarball carries them, as Apache 2.0 asks. The copies aren't
// committed.
import { copyFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { cwd } from "node:process";
import { fileURLToPath } from "node:url";

const repository = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
for (const name of ["LICENSE", "NOTICE"]) {
  copyFileSync(join(repository, name), join(cwd(), name));
}

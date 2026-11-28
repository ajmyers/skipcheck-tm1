// SPDX-License-Identifier: Apache-2.0

import type { Entry } from "../../generated/index.js";
import { ODataPath } from "../../odata/index.js";
import { required } from "../../util.js";
import { ContentScope } from "./ContentScope.js";

interface Step {
  readonly id: string;
  readonly scope: ContentScope;
}

/** Where an entry is in the contents tree: a root folder, then each folder's child by key.
 *
 * ```ts
 * const notes = ContentPath.applications().child("Planning").child("notes.txt.blob");
 * notes.path().toString(); // Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')
 * ```
 *
 * On TM1 11.8, children are found by their `ID`, not their `Name`, though `$metadata` keys
 * entries by `Name`: TM1 (11.8) gives most entries an ID of the name plus a suffix for its kind,
 * such as `notes.txt.blob` for a document, `ibm.extr` for a link and `}ClientGroups.cube` for a
 * cube reference. A folder's ID is its name. `$metadata` deprecates `Entry.ID` in schema 11.5.0,
 * and TM1 12 omits `ID` altogether and keys entries by `Name`. {@link child} uses `ID` when
 * present and falls back to `Name`. Paths are immutable. */
export class ContentPath {
  readonly #steps: readonly Step[];

  private constructor(steps: readonly Step[]) {
    this.#steps = Object.freeze(steps);
    rootIds.set(this, steps[0]!.id);
  }

  /** A root folder: `Contents('id')`, such as `Applications`. */
  static root(id: string): ContentPath {
    return new ContentPath([{ id: checkId(id), scope: ContentScope.Public }]);
  }

  /** The `Applications` folder, where applications are built. */
  static applications(): ContentPath {
    return ContentPath.root("Applications");
  }

  /** The `Blobs` folder, holding the documents behind the document references in applications. */
  static blobs(): ContentPath {
    return ContentPath.root("Blobs");
  }

  /** The `Files` folder, holding direct documents on TM1 12. */
  static files(): ContentPath {
    return ContentPath.root("Files");
  }

  /** The `Home` folder, holding the current user's direct documents on TM1 12. */
  static home(): ContentPath {
    return ContentPath.root("Home");
  }

  /** The `Session` folder, holding session-scoped direct documents on TM1 12. */
  static session(): ContentPath {
    return ContentPath.root("Session");
  }

  /** A child of this folder, public unless `scope` says: `…/Contents('id')` or
   * `…/PrivateContents('id')`. */
  child(id: string, scope?: ContentScope): ContentPath;
  /** A child of this folder, as TM1 returned it, such as from `ContentService.create`: found by its
   * `ID` when present (TM1 11), falling back to its `Name` when `ID` is absent (TM1 12). A
   * `RangeError` if the entry has neither an ID nor a Name. */
  child(entry: Entry, scope?: ContentScope): ContentPath;
  child(idOrEntry: string | Entry, scope: ContentScope = ContentScope.Public): ContentPath {
    let id: string;
    if (typeof idOrEntry === "string") id = idOrEntry;
    else {
      // ID is deprecated in $metadata (TM1 11 keys by it; TM1 12 omits it and keys by Name)
      const entry = required(idOrEntry, "entry");
      const key = entry.ID != null && entry.ID !== "" ? entry.ID : entry.Name;
      if (key == null || key === "") throw new RangeError("The entry has neither an ID nor a Name to find it by: " + JSON.stringify(idOrEntry));
      id = key;
    }
    ContentScope.navigation(required(scope, "scope"));
    return new ContentPath([...this.#steps, { id: checkId(id), scope }]);
  }

  /** Whether this is in the `Blobs` folder, where documents themselves are. */
  inBlobs(): boolean {
    return this.#steps[0]!.id === "Blobs";
  }

  /** The OData path to the entry, such as `Contents('Applications')/Contents('Planning')`. */
  path(): ODataPath {
    let path = ODataPath.of("Contents").key(this.#steps[0]!.id);
    for (const step of this.#steps.slice(1)) path = path.nav(ContentScope.navigation(step.scope)).key(step.id);
    return path;
  }

  /** Whether `other` is a path through the same IDs, in the same scopes. */
  equals(other: unknown): boolean {
    if (!(other instanceof ContentPath) || other.#steps.length !== this.#steps.length) return false;
    return other.#steps.every((s, i) => s.id === this.#steps[i]!.id && s.scope === this.#steps[i]!.scope);
  }

  /** The IDs from the root, such as `Applications/Planning/notes.txt.blob`. */
  toString(): string {
    return this.#steps.map((s) => (s.scope === ContentScope.Private ? "(private) " : "") + s.id).join("/");
  }
}

const rootIds = new WeakMap<ContentPath, string>();

/** Package-internal helper (not re-exported from the public package entrypoint): whether `entry`
 * is under a root holding `Document` entities directly (every root other than `Applications`). */
export function isDirectDocumentRoot(entry: ContentPath): boolean {
  return (rootIds.get(entry) ?? "").toLowerCase() !== "applications";
}

function checkId(id: string): string {
  if (typeof id !== "string") throw new TypeError("id is required");
  return id;
}

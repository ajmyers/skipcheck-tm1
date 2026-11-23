import { Document, Entry, Folder, LocalizedAttributes, Tm1Operations, type EntryPatch } from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import type { Tm1Operation } from "../../operation.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";
import type { ContentPath } from "./ContentPath.js";
import { ContentScope } from "./ContentScope.js";

/** A document's content to upload, in any form Node and browsers have: bytes, a `Blob` (a browser's
 * `File`), a `ReadableStream` (a `fetch` body, `Readable.toWeb(fs.createReadStream(…))`), or an
 * iterable of chunks (a Node `Readable` is one). */
export type ContentSource = Uint8Array | ArrayBuffer | Blob | ReadableStream<Uint8Array> | AsyncIterable<Uint8Array> | Iterable<Uint8Array>;

/** The contents tree, where Planning Analytics keeps applications: folders, documents, links, and
 * references to cubes, dimensions, subsets, views, processes and chores. Entries are addressed by
 * {@link ContentPath}.
 *
 * What TM1 (11.8) does here that you might not expect:
 * - Entries are found by `ID`, not `Name` (see {@link ContentPath}).
 * - A document made in an application becomes a `DocumentReference` there, to a `Document` TM1
 *   makes in `Blobs` with a timestamp in its name, such as `notes_20261122125939.txt`. Its content
 *   goes through the reference ({@link content}, {@link upload}). A copy of the reference refers to
 *   the same document; deleting a reference leaves the document while another refers to it.
 * - Entries can't be changed: TM1 refuses a PATCH ("PATCH not supported on this resource"), so
 *   there's no update. To rename one, copy it with a new name and delete the original.
 *
 * Build an application: a folder, a link and a cube reference:
 * ```ts
 * const app = ContentPath.applications().child("Planning");
 * await tm1.contents.create(ContentPath.applications(), ContentScope.Public, FolderPatch.builder().name("Planning").build());
 * await tm1.contents.create(app, ContentScope.Public, LinkPatch.builder().name("ibm").url("https://www.ibm.com").build());
 * await tm1.contents.create(app, ContentScope.Public, CubeReferencePatch.builder().name("Sales").bindCube(ODataPath.of("Cubes").key("Sales")).build());
 * ```
 * Upload a document, and download it:
 * ```ts
 * const made = await tm1.contents.create(app, ContentScope.Public, DocumentPatch.builder().name("notes.txt").build());
 * await tm1.contents.upload(app.child(made), new TextEncoder().encode("hello there"));
 * for await (const chunk of tm1.contents.content(app.child(made))) process.stdout.write(chunk);
 * ```
 */
export class ContentService extends Tm1Service {
  static readonly endpoints = {
    roots: ["EntitySet Contents"],
    get: ["EntitySet Contents"],
    contents: [
      "Navigation Folder/Contents",
      "Navigation Folder/PrivateContents",
      "Navigation ChoreReference/Chore",
      "Navigation CubeReference/Cube",
      "Navigation DimensionReference/Dimension",
      "Navigation ProcessReference/Process",
      "Navigation SubsetReference/Subset",
      "Navigation ViewReference/View",
    ],
    create: ["Navigation Folder/Contents", "Navigation Folder/PrivateContents"],
    delete: ["EntitySet Contents"],
    containedBy: ["Navigation Entry/ContainedBy"],
    localizedAttributes: ["Navigation Entry/LocalizedAttributes"],
    copy: ["Action Entry/Copy"],
    move: ["Action Entry/Move"],
    document: ["Navigation DocumentReference/Document"],
    content: ["Navigation DocumentReference/Document"],
    upload: ["Navigation DocumentReference/Document"],
  } as const satisfies Tm1Endpoints<ContentService>;

  /** `GET Contents`, streamed: the root folders, `Applications` and `Blobs`. */
  roots(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Entry> {
    return this.session.stream(Tm1Request.list(ODataPath.of("Contents"), query, Entry), options);
  }

  /** `GET` of one entry, of its own kind (`Folder`, `Link`, `CubeReference` …: see `typeOf`), or a
   * `Tm1NotFoundException` if there's none. A reference's object comes by a cast expand, such as
   * `expand("tm1.CubeReference/Cube")`. */
  get(entry: ContentPath, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Entry> {
    return this.session.execute(Tm1Request.get(pathOf(entry, "entry"), query, Entry), options);
  }

  /** A folder's public or private entries, streamed, each of its own kind. References' objects
   * come by a cast expand, such as `expand("tm1.CubeReference/Cube($select=Name)")`. */
  contents(folder: ContentPath, scope: ContentScope, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Entry> {
    return this.session.stream(Tm1Request.list(pathOf(folder, "folder").nav(ContentScope.navigation(scope)), query, Entry), options);
  }

  /** Makes an entry in a folder, public or private: a `FolderPatch`, `DocumentPatch`, `LinkPatch`
   * or a reference, such as a `CubeReferencePatch` with `bindCube(ODataPath.of("Cubes").key("Sales"))`.
   * A document made outside `Blobs` comes back as a `DocumentReference`, with an empty document
   * behind it; {@link upload} gives it content. Returns the entry as made, with the `ID` to find it by. */
  create(folder: ContentPath, scope: ContentScope, entry: EntryPatch, options?: Tm1CallOptions): Promise<Entry> {
    return this.session.execute(Tm1Request.post(pathOf(folder, "folder").nav(ContentScope.navigation(scope)), required(entry, "entry"), Entry), options);
  }

  /** `DELETE` of an entry, and everything in it if it's a folder. */
  delete(entry: ContentPath, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(pathOf(entry, "entry")), options);
  }

  /** `ContainedBy`: the folder an entry is in. */
  containedBy(entry: ContentPath, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Folder> {
    return this.session.execute(Tm1Request.get(pathOf(entry, "entry").nav("ContainedBy"), query, Folder), options);
  }

  /** `LocalizedAttributes`, streamed: an entry's attributes in each locale. */
  localizedAttributes(entry: ContentPath, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<LocalizedAttributes> {
    return this.session.stream(Tm1Request.list(pathOf(entry, "entry").nav("LocalizedAttributes"), query, LocalizedAttributes), options);
  }

  /** `tm1.Copy`: copies an entry into a folder, public or private, named `name` or, with none, as
   * the entry. A copied document reference refers to the same document. */
  copy(entry: ContentPath, target: ContentPath, scope: ContentScope, name?: string | null, options?: Tm1CallOptions): Promise<Entry> {
    return this.relocate(Tm1Operations.EntryCopy, entry, target, scope, name, options);
  }

  /** `tm1.Move`: moves an entry into a folder, public or private, renamed `name` if given;
   * returns the entry where it is now. */
  move(entry: ContentPath, target: ContentPath, scope: ContentScope, name?: string | null, options?: Tm1CallOptions): Promise<Entry> {
    return this.relocate(Tm1Operations.EntryMove, entry, target, scope, name, options);
  }

  /** `Document`: the document a reference refers to, in `Blobs`, with its `Size` and `LastUpdated`. */
  document(reference: ContentPath, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Document> {
    return this.session.execute(Tm1Request.get(documentPath(reference, "reference"), query, Document), options);
  }

  /** A document's `Content`, as chunks of bytes. The path is a document reference, or a document
   * in `Blobs`. Nothing is sent until it's iterated; each iteration sends it again. */
  content(document: ContentPath, options?: Tm1CallOptions): Tm1Stream<Uint8Array> {
    const request = Tm1Request.bytes(documentPath(document, "document").nav("Content"));
    const session = this.session;
    return Tm1Stream.from(async function* () {
      const bytes = await session.execute(request, options);
      if (bytes.length > 0) yield bytes;
    });
  }

  /** Replaces a document's `Content`. The path is a document reference, or a document in `Blobs`.
   * TM1 (11.8) refuses a chunked upload, so the content is gathered and sent with its length: it's
   * held in memory whole while it's sent (and so can be sent again if the session has to log in
   * again). */
  async upload(document: ContentPath, content: ContentSource, options?: Tm1CallOptions): Promise<void> {
    const path = documentPath(document, "document").nav("Content");
    const bytes = await gather(required(content, "content"));
    await this.session.execute(Tm1Request.put(path, bytes), options);
  }

  private relocate(
    operation: Tm1Operation,
    entry: ContentPath,
    target: ContentPath,
    scope: ContentScope,
    name: string | null | undefined,
    options: Tm1CallOptions | undefined,
  ): Promise<Entry> {
    ContentScope.navigation(scope);
    const body: Record<string, unknown> = { "Target@odata.bind": pathOf(target, "target").toString(), Private: scope === ContentScope.Private };
    if (name != null) body["Name"] = name;
    return this.session.execute(Tm1Request.post(pathOf(entry, "entry").action(operation.name), body, Entry).requires(operation.capability()), options);
  }
}

function pathOf(entry: ContentPath, name: string): ODataPath {
  return required(entry, name).path();
}

function documentPath(entry: ContentPath, name: string): ODataPath {
  const path = pathOf(entry, name);
  return entry.inBlobs() ? path.cast("tm1.Document") : path.cast("tm1.DocumentReference").nav("Document");
}

async function gather(content: ContentSource): Promise<Uint8Array> {
  if (content instanceof Uint8Array) return content;
  if (content instanceof ArrayBuffer) return new Uint8Array(content);
  if (typeof Blob !== "undefined" && content instanceof Blob) return new Uint8Array(await content.arrayBuffer());
  const chunks: Uint8Array[] = [];
  if (typeof ReadableStream !== "undefined" && content instanceof ReadableStream) {
    // Not for await: not every browser's ReadableStream is async iterable
    const reader = (content as ReadableStream<Uint8Array>).getReader();
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        chunks.push(chunk(value));
      }
    } finally {
      reader.releaseLock();
    }
  } else if (Symbol.asyncIterator in (content as object)) {
    for await (const c of content as AsyncIterable<Uint8Array>) chunks.push(chunk(c));
  } else if (Symbol.iterator in (content as object)) {
    for (const c of content as Iterable<Uint8Array>) chunks.push(chunk(c));
  } else {
    throw new TypeError("content is bytes, a Blob, a ReadableStream or an iterable of Uint8Array chunks");
  }
  const out = new Uint8Array(chunks.reduce((n, c) => n + c.length, 0));
  let at = 0;
  for (const c of chunks) {
    out.set(c, at);
    at += c.length;
  }
  return out;
}

function chunk(value: unknown): Uint8Array {
  if (!(value instanceof Uint8Array)) throw new TypeError("content's chunks are Uint8Array");
  return value;
}

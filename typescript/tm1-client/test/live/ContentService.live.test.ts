// SPDX-License-Identifier: Apache-2.0

import { afterAll, beforeAll, describe, expect } from "vitest";
import {
  ContentPath,
  ContentScope,
  CubeReference,
  CubeReferencePatch,
  DocumentPatch,
  Entry,
  FolderPatch,
  Link,
  LinkPatch,
  ODataPath,
  ODataQuery,
  Tm1BadRequestException,
  Tm1NotFoundException,
  Tm1Request,
  typeOf,
  type Tm1Client,
} from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

const utf8 = new TextEncoder();

async function text(content: AsyncIterable<Uint8Array>): Promise<string> {
  const decoder = new TextDecoder();
  let out = "";
  for await (const chunk of content) out += decoder.decode(chunk, { stream: true });
  return out + decoder.decode();
}

/** ContentService against a real TM1: the contents tree, and what TM1 does with documents. The
 * Java client's `ContentServiceTm1Test`. */
describe("ContentService on TM1" + liveReason, () => {
  let tm1: Tm1Client;

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
  });

  /** A folder of its own in Applications, deleted afterwards. */
  async function inFolder(names: string[], test: (folders: ContentPath[]) => Promise<void>): Promise<void> {
    const folders = names.map((n) => ContentPath.applications().child(n));
    for (const n of names) await tm1.contents.create(ContentPath.applications(), ContentScope.Public, FolderPatch.builder().name(n).build());
    try {
      await test(folders);
    } finally {
      await Promise.all(folders.map((f) => tm1.contents.delete(f).catch(() => undefined)));
    }
  }

  tm1Test("lists the roots", async () => {
    const roots = (await tm1.contents.roots(ODataQuery.none()).toArray()).map((e) => e.Name);
    expect(roots).toEqual(expect.arrayContaining(["Applications", "Blobs"]));
  });

  tm1Test("keeps a document in Blobs behind a reference", async () => {
    await inFolder([Live.name()], async ([folder]) => {
      const made = await tm1.contents.create(folder!, ContentScope.Public, DocumentPatch.builder().name("notes.txt").build());
      // A document made in an application is a reference, found by ID, the name plus a suffix
      expect(typeOf(Entry, made)).toBe("DocumentReference");
      expect(made.ID).toBe("notes.txt.blob");
      const notes = folder!.child(made);
      // Not by name
      await expect(tm1.contents.get(folder!.child("notes.txt"), ODataQuery.none())).rejects.toBeInstanceOf(Tm1NotFoundException);

      await tm1.contents.upload(notes, utf8.encode("hello there"));
      expect(await text(tm1.contents.content(notes))).toBe("hello there");
      const document = await tm1.contents.document(notes, ODataQuery.none());
      expect(document.Size).toBe(11);
      // Named by TM1, with a timestamp
      expect(document.Name).toMatch(/^notes_.*\.txt$/);
      const blob = ContentPath.blobs().child(document.ID!);
      expect(await text(tm1.contents.content(blob))).toBe("hello there");

      const copy = await tm1.contents.copy(notes, folder!, ContentScope.Public, "copy.txt");
      expect(copy.ID).toBe("copy.txt.blob");
      // A copy refers to the same document
      expect((await tm1.contents.document(folder!.child(copy.ID!), ODataQuery.none())).ID).toBe(document.ID);
      await tm1.contents.delete(notes);
      // Kept while the copy refers to it
      expect(await text(tm1.contents.content(blob))).toBe("hello there");
    });
  });

  tm1Test("makes links and references and moves them", async () => {
    const name = Live.name();
    await inFolder([name, name + "-2"], async ([folder, other]) => {
      const link = await tm1.contents.create(folder!, ContentScope.Public, LinkPatch.builder().name("ibm").url("https://www.ibm.com").build());
      expect(link.ID).toBe("ibm.extr");
      const reference = await tm1.contents.create(
        folder!,
        ContentScope.Public,
        CubeReferencePatch.builder().name("}ClientGroups").bindCube(ODataPath.of("Cubes").key("}ClientGroups")).build(),
      );
      expect(reference.ID).toBe("}ClientGroups.cube");

      const listed = await tm1.contents.contents(folder!, ContentScope.Public, ODataQuery.none().expand("tm1.CubeReference/Cube($select=Name)")).toArray();
      const references = listed.filter((e) => typeOf(Entry, e) === "CubeReference") as CubeReference[];
      expect(references.map((r) => r.Cube?.Name)).toEqual(["}ClientGroups"]);
      const got = await tm1.contents.get(folder!.child(link.ID!), ODataQuery.none());
      expect(typeOf(Entry, got)).toBe("Link");
      expect((got as Link).URL).toBe("https://www.ibm.com");
      const parent = await tm1.contents.containedBy(folder!.child(link.ID!), ODataQuery.none());
      expect(parent.ID).toBe(name);

      // Entries can't be changed
      await expect(tm1.session.execute(Tm1Request.patch(folder!.child(link.ID!).path(), { Name: "renamed" }))).rejects.toBeInstanceOf(Tm1BadRequestException);

      const moved = await tm1.contents.move(folder!.child(link.ID!), other!, ContentScope.Public, null);
      expect(moved.ID).toBe("ibm.extr");
      expect((await tm1.contents.contents(other!, ContentScope.Public, ODataQuery.none()).toArray()).map((e) => e.ID)).toEqual(["ibm.extr"]);

      await tm1.contents.create(folder!, ContentScope.Private, FolderPatch.builder().name("mine").build());
      expect((await tm1.contents.contents(folder!, ContentScope.Private, ODataQuery.none()).toArray()).map((e) => e.ID)).toEqual(["mine"]);
      expect(typeOf(Entry, await tm1.contents.get(folder!.child("mine", ContentScope.Private), ODataQuery.none()))).toBe("Folder");
      expect(await tm1.contents.localizedAttributes(folder!, ODataQuery.none()).toArray()).toEqual([]);
    });
  });

  tm1Test("refuses a chunked upload", async () => {
    await inFolder([Live.name()], async ([folder]) => {
      const made = await tm1.contents.create(folder!, ContentScope.Public, DocumentPatch.builder().name("notes.txt").build());
      const target = await Live.target();
      const root = target.url.endsWith("/") ? target.url : target.url + "/";
      // A stream of unknown length is sent chunked
      const body = new ReadableStream<Uint8Array>({
        start(c) {
          c.enqueue(utf8.encode("hello"));
          c.close();
        },
      });
      const response = await fetch(root + folder!.child(made.ID!).path().toString() + "/tm1.DocumentReference/Document/Content", {
        method: "PUT",
        headers: { Authorization: "Basic " + btoa(target.user + ":" + target.password), "Content-Type": "application/octet-stream" },
        body,
        duplex: "half",
      } as RequestInit);
      await response.arrayBuffer();
      expect(response.status).toBe(400);
    });
  });
});

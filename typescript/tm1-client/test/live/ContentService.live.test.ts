// SPDX-License-Identifier: Apache-2.0

import { afterAll, beforeAll, describe, expect } from "vitest";
import {
  ContentPath,
  ContentScope,
  CubeReference,
  CubeReferenceCreate,
  DocumentCreate,
  Entry,
  FolderCreate,
  Link,
  LinkCreate,
  ODataPath,
  ODataQuery,
  Tm1BadRequestException,
  Tm1NotFoundException,
  Tm1Request,
  typeOf,
  type Tm1Client,
} from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";
import { RawTm1 } from "./raw-tm1.js";
import { isV12 } from "./server-assumptions.js";

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
    for (const n of names) await tm1.contents.create(ContentPath.applications(), ContentScope.Public, FolderCreate.builder().name(n).build());
    try {
      await test(folders);
    } finally {
      await Promise.all(folders.map((f) => tm1.contents.delete(f).catch(() => undefined)));
    }
  }

  tm1Test("lists the roots", async () => {
    const roots = (await tm1.contents.roots(ODataQuery.none()).toArray()).map((e) => e.Name);
    if (await isV12(tm1)) {
      expect(roots).toEqual(expect.arrayContaining(["Applications", "Files", "Home", "Session"]));
    } else {
      expect(roots).toEqual(expect.arrayContaining(["Applications", "Blobs"]));
    }
  });

  tm1Test("keeps a document in Blobs behind a reference", async () => {
    const v12 = await isV12(tm1);
    const name = Live.name();
    // TM1 keeps a document as }externals/<name>_<second>.<ext>, server-wide: two made with one name in
    // one second collide (400, code 226), so each test names its own
    const doc = name + "-notes";
    await inFolder([name], async ([folder]) => {
      const made = await tm1.contents.create(
        folder!,
        ContentScope.Public,
        DocumentCreate.builder()
          .name(doc + ".txt")
          .build(),
      );
      // A document made in an application is a reference, found by ID on v11 and Name on v12
      expect(typeOf(Entry, made)).toBe("DocumentReference");
      if (v12) {
        expect(made.ID, "TM1 v12 omits deprecated Entry.ID").toBeUndefined();
        expect(made.Name).toBe(doc + ".txt");
      } else {
        expect(made.ID).toBe(doc + ".txt.blob");
      }
      const notes = folder!.child(made);
      if (!v12) {
        // Not by name
        await expect(tm1.contents.get(folder!.child(doc + ".txt"), ODataQuery.none())).rejects.toBeInstanceOf(Tm1NotFoundException);
      }

      await tm1.contents.upload(notes, utf8.encode("hello there"));
      expect(await text(tm1.contents.content(notes))).toBe("hello there");
      const document = await tm1.contents.document(notes, ODataQuery.none());
      expect(document.Size).toBe(11);
      // Named by TM1, with a timestamp
      expect(document.Name?.startsWith(doc + "_") && document.Name.endsWith(".txt"), document.Name).toBe(true);

      const copy = await tm1.contents.copy(notes, folder!, ContentScope.Public, doc + "-copy.txt");
      if (v12) {
        expect(copy.Name).toBe(doc + "-copy.txt");
        const copiedDoc = await tm1.contents.document(folder!.child(copy), ODataQuery.none());
        expect(copiedDoc.Name?.startsWith(doc + "-copy_") && copiedDoc.Name.endsWith(".txt"), copiedDoc.Name).toBe(true);
        await tm1.contents.delete(notes);
        expect(await text(tm1.contents.content(folder!.child(copy)))).toBe("hello there");
      } else {
        const blob = ContentPath.blobs().child(document.ID!);
        expect(await text(tm1.contents.content(blob))).toBe("hello there");
        expect(copy.ID).toBe(doc + "-copy.txt.blob");
        // A copy refers to the same document
        expect((await tm1.contents.document(folder!.child(copy.ID!), ODataQuery.none())).ID).toBe(document.ID);
        await tm1.contents.delete(notes);
        // Kept while the copy refers to it
        expect(await text(tm1.contents.content(blob))).toBe("hello there");
      }
    });
  });

  tm1Test("reads and writes documents in Files, Home and Session", async (context) => {
    if (!(await isV12(tm1))) return context.skip("Direct-document roots (Files, Home, Session) are TM1 v12+");
    const filename = Live.name() + ".txt";
    for (const root of [ContentPath.files(), ContentPath.home(), ContentPath.session()]) {
      const created = await tm1.contents.create(root, ContentScope.Public, DocumentCreate.builder().name(filename).build());
      expect(typeOf(Entry, created)).toBe("Document");
      const doc = root.child(created);
      try {
        await tm1.contents.upload(doc, utf8.encode("hello v12"));
        expect(await text(tm1.contents.content(doc))).toBe("hello v12");
        const read = await tm1.contents.document(doc, ODataQuery.none());
        expect(read.Size).toBe(9);
        expect(read.Name).toBe(filename);
      } finally {
        await tm1.contents.delete(doc).catch(() => undefined);
      }
    }
  });

  tm1Test("makes links and references and moves them", async () => {
    const v12 = await isV12(tm1);
    const name = Live.name();
    await inFolder([name, name + "-2"], async ([folder, other]) => {
      const link = await tm1.contents.create(folder!, ContentScope.Public, LinkCreate.builder().name("ibm").url("https://www.ibm.com").build());
      const linkPath = folder!.child(link);
      expect(v12 ? link.Name : link.ID).toBe(v12 ? "ibm" : "ibm.extr");
      const reference = await tm1.contents.create(
        folder!,
        ContentScope.Public,
        CubeReferenceCreate.builder().name("}ClientGroups").bindCube(ODataPath.of("Cubes").key("}ClientGroups")).build(),
      );
      expect(v12 ? reference.Name : reference.ID).toBe(v12 ? "}ClientGroups" : "}ClientGroups.cube");

      const listed = await tm1.contents.contents(folder!, ContentScope.Public, ODataQuery.none().expand("tm1.CubeReference/Cube($select=Name)")).toArray();
      const references = listed.filter((e) => typeOf(Entry, e) === "CubeReference") as CubeReference[];
      expect(references.map((r) => r.Cube?.Name)).toEqual(["}ClientGroups"]);
      const got = await tm1.contents.get(linkPath, ODataQuery.none());
      expect(typeOf(Entry, got)).toBe("Link");
      expect((got as Link).URL).toBe("https://www.ibm.com");
      const parent = await tm1.contents.containedBy(linkPath, ODataQuery.none());
      expect(v12 ? parent.Name : parent.ID).toBe(name);

      // Entries can't be changed
      await expect(tm1.session.execute(Tm1Request.patch(linkPath.path(), { Name: "renamed" }))).rejects.toBeInstanceOf(Tm1BadRequestException);

      const moved = await tm1.contents.move(linkPath, other!, ContentScope.Public, null);
      const expectedMoved = v12 ? "ibm" : "ibm.extr";
      expect(v12 ? moved.Name : moved.ID).toBe(expectedMoved);
      expect((await tm1.contents.contents(other!, ContentScope.Public, ODataQuery.none()).toArray()).map((e) => (v12 ? e.Name : e.ID))).toEqual([
        expectedMoved,
      ]);

      await tm1.contents.create(folder!, ContentScope.Private, FolderCreate.builder().name("mine").build());
      expect((await tm1.contents.contents(folder!, ContentScope.Private, ODataQuery.none()).toArray()).map((e) => (v12 ? e.Name : e.ID))).toEqual(["mine"]);
      expect(typeOf(Entry, await tm1.contents.get(folder!.child("mine", ContentScope.Private), ODataQuery.none()))).toBe("Folder");
      expect(await tm1.contents.localizedAttributes(folder!, ODataQuery.none()).toArray()).toEqual([]);
    });
  });

  tm1Test("refuses a chunked upload", async () => {
    const name = Live.name();
    await inFolder([name], async ([folder]) => {
      // A name of its own: see "keeps a document in Blobs behind a reference"
      const made = await tm1.contents.create(
        folder!,
        ContentScope.Public,
        DocumentCreate.builder()
          .name(name + "-notes.txt")
          .build(),
      );
      const target = await Live.target();
      const root = target.url.endsWith("/") ? target.url : target.url + "/";
      const raw = target.engine ? await RawTm1.connect() : undefined;
      const cookie = raw ? await raw.login() : undefined;
      try {
        // A stream of unknown length is sent chunked
        const body = new ReadableStream<Uint8Array>({
          start(c) {
            c.enqueue(utf8.encode("hello"));
            c.close();
          },
        });
        const authHeader: Record<string, string> = cookie ? { Cookie: cookie } : { Authorization: "Basic " + btoa(target.user + ":" + target.password) };
        const response = await fetch(root + folder!.child(made).path().toString() + "/tm1.DocumentReference/Document/Content", {
          method: "PUT",
          headers: { ...authHeader, "Content-Type": "application/octet-stream" },
          body,
          duplex: "half",
        } as RequestInit);
        await response.arrayBuffer();
        // TM1 11 refuses chunked uploads (400); TM1 v12's gateway accepts them (204)
        expect(response.status).toBe((await isV12(tm1)) ? 204 : 400);
      } finally {
        if (raw && cookie) await raw.close(cookie);
      }
    });
  });
});

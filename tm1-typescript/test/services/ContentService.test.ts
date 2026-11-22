import { inspect } from "node:util";
import { describe, expect, it } from "vitest";
import { ContentPath, ContentScope, DocumentPatch, DocumentReference, Entry, Folder, FolderPatch, Link, ODataQuery, typeOf } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

const utf8 = new TextEncoder();
const text = (bytes: Uint8Array): string => new TextDecoder().decode(bytes);
const sent = (tm1: FakeTm1): string => `${tm1.last.method} ${tm1.last.target}`;

async function joined(chunks: AsyncIterable<Uint8Array>): Promise<string> {
  let out = "";
  for await (const c of chunks) out += text(c);
  return out;
}

/** ContentService and ContentPath against a fake TM1: each method sends what the Java client's
 * does (the Java client has no unit test of its own for these; this is the .NET client's
 * ContentServiceTests, with Java's requests). */
describe("ContentService", () => {
  it("finds children by ID", () => {
    const notes = ContentPath.applications().child("Planning").child("notes.txt.blob");
    expect(notes.path().toString()).toBe("Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')");
    expect(notes.toString()).toBe("Applications/Planning/notes.txt.blob");
    expect(notes.inBlobs()).toBe(false);
    expect(ContentPath.blobs().child("a.txt").inBlobs()).toBe(true);

    const mine = ContentPath.applications().child("mine", ContentScope.Private);
    expect(mine.path().toString()).toBe("Contents('Applications')/PrivateContents('mine')");
    expect(mine.toString()).toBe("Applications/(private) mine");
    expect(ContentPath.root("Applications").child("Planning").equals(ContentPath.applications().child({ ID: "Planning" }))).toBe(true);
    expect(ContentPath.applications().child({ ID: "mine" }, ContentScope.Private).equals(mine)).toBe(true);
    expect(mine.equals(ContentPath.applications().child("mine"))).toBe(false);
    expect(ContentPath.root("O'Brien").path().toString()).toBe("Contents('O''Brien')");
    expect(() => ContentPath.applications().child({ Name: "Planning" })).toThrow(RangeError);
    expect(() => ContentPath.root(null as unknown as string)).toThrow(TypeError);
    expect(ContentScope.navigation(ContentScope.Private)).toBe("PrivateContents");
    expect(ContentScope.navigation(ContentScope.Public)).toBe("Contents");
    // Immutable: a child is a new path
    const apps = ContentPath.applications();
    apps.child("x");
    expect(apps.toString()).toBe("Applications");
  });

  it("lists, gets and deletes", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection({ "@odata.type": "#ibm.tm1.api.v1.Folder", ID: "Applications", Name: "Applications" });
    const roots = await client.contents.roots(ODataQuery.none().select("Name")).toArray();
    expect(roots).toHaveLength(1);
    expect(typeOf(Entry, roots[0]!)).toBe("Folder");
    expect(sent(tm1)).toBe("GET Contents?$select=Name");

    const planning = ContentPath.applications().child("Planning");
    await client.contents.contents(planning, ContentScope.Public, ODataQuery.none().expand("tm1.CubeReference/Cube", (q) => q.select("Name"))).toArray();
    expect(decodeURIComponent(sent(tm1))).toBe("GET Contents('Applications')/Contents('Planning')/Contents?$expand=tm1.CubeReference/Cube($select=Name)");
    await client.contents.contents(planning, ContentScope.Private).toArray();
    expect(sent(tm1)).toBe("GET Contents('Applications')/Contents('Planning')/PrivateContents");

    tm1.respond = () => ({ "@odata.type": "#ibm.tm1.api.v1.Link", ID: "ibm.extr", URL: "https://www.ibm.com" });
    const link = await client.contents.get(planning.child("ibm.extr"));
    expect(typeOf(Entry, link)).toBe("Link");
    expect((link as Link).URL).toBe("https://www.ibm.com");
    expect(sent(tm1)).toBe("GET Contents('Applications')/Contents('Planning')/Contents('ibm.extr')");

    tm1.respond = () => ({ ID: "Planning" });
    const parent: Folder = await client.contents.containedBy(planning.child("ibm.extr"), ODataQuery.none().select("ID"));
    expect(parent.ID).toBe("Planning");
    expect(sent(tm1)).toBe("GET Contents('Applications')/Contents('Planning')/Contents('ibm.extr')/ContainedBy?$select=ID");
    tm1.respond = () => FakeTm1.collection();
    expect(await client.contents.localizedAttributes(planning).toArray()).toEqual([]);
    expect(sent(tm1)).toBe("GET Contents('Applications')/Contents('Planning')/LocalizedAttributes");

    tm1.respond = () => null;
    await client.contents.delete(planning);
    expect(sent(tm1)).toBe("DELETE Contents('Applications')/Contents('Planning')");
  });

  it("creates, copies and moves", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    const planning = ContentPath.applications().child("Planning");
    tm1.respond = () => ({ "@odata.type": "#ibm.tm1.api.v1.DocumentReference", ID: "notes.txt.blob" });
    const made = await client.contents.create(planning, ContentScope.Public, DocumentPatch.builder().name("notes.txt").build());
    expect(typeOf(Entry, made)).toBe("DocumentReference");
    expect(typeOf(DocumentReference, made as DocumentReference)).toBe("DocumentReference");
    expect([sent(tm1), tm1.last.json.Name]).toEqual(["POST Contents('Applications')/Contents('Planning')/Contents", "notes.txt"]);
    expect(tm1.last.json["@odata.type"]).toBe("#ibm.tm1.api.v1.Document");
    await client.contents.create(planning, ContentScope.Private, FolderPatch.builder().name("mine").build());
    expect(sent(tm1)).toBe("POST Contents('Applications')/Contents('Planning')/PrivateContents");

    const other = ContentPath.applications().child("Other");
    await client.contents.copy(planning.child(made), other, ContentScope.Public, "copy.txt");
    expect(sent(tm1)).toBe("POST Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')/tm1.Copy");
    expect(tm1.last.text).toBe(`{"Target@odata.bind":"Contents('Applications')/Contents('Other')","Private":false,"Name":"copy.txt"}`);
    await client.contents.move(planning.child(made), other, ContentScope.Private, null);
    expect(sent(tm1)).toBe("POST Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')/tm1.Move");
    expect(tm1.last.text).toBe(`{"Target@odata.bind":"Contents('Applications')/Contents('Other')","Private":true}`);
    await client.contents.move(planning.child(made), other, ContentScope.Public);
    expect(tm1.last.text).toBe(`{"Target@odata.bind":"Contents('Applications')/Contents('Other')","Private":false}`);
  });

  it("reads and writes documents", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    const notes = ContentPath.applications().child("Planning").child("notes.txt.blob");
    tm1.respond = () => ({ ID: "notes_20261122125939.txt", Size: 11 });
    expect((await client.contents.document(notes)).Size).toBe(11);
    expect(sent(tm1)).toBe("GET Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')/tm1.DocumentReference/Document");

    tm1.respond = () => FakeTm1.reply(200, utf8.encode("hello there"), { "content-type": "application/octet-stream" });
    const content = client.contents.content(notes);
    expect(tm1.requests).toHaveLength(1); // nothing sent until it's iterated
    expect(await joined(content)).toBe("hello there");
    expect(sent(tm1)).toBe("GET Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')/tm1.DocumentReference/Document/Content");
    expect(tm1.last.headers["accept"]).toBe("*/*");
    await client.contents.content(ContentPath.blobs().child("notes_1.txt")).toArray();
    expect(sent(tm1)).toBe("GET Contents('Blobs')/Contents('notes_1.txt')/tm1.Document/Content");

    tm1.respond = () => null;
    await client.contents.upload(notes, utf8.encode("hello"));
    expect(sent(tm1)).toBe("PUT Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')/tm1.DocumentReference/Document/Content");
    expect(tm1.last.text).toBe("hello");
    expect(tm1.last.body.length).toBe(5); // gathered, so sent with its length: TM1 refuses chunked uploads
    expect(tm1.last.headers["content-type"]).toBe("application/octet-stream");
  });

  it("uploads content in the forms Node and browsers have", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    const notes = ContentPath.blobs().child("notes_1.txt");
    tm1.respond = () => null;
    const sources = [
      utf8.encode("hello there").buffer,
      new Blob(["hello", " there"]),
      new ReadableStream<Uint8Array>({
        start(c) {
          c.enqueue(utf8.encode("hello"));
          c.enqueue(utf8.encode(" there"));
          c.close();
        },
      }),
      (async function* () {
        yield utf8.encode("hello");
        yield utf8.encode(" there");
      })(),
      [utf8.encode("hello"), utf8.encode(" there")],
    ];
    for (const source of sources) {
      await client.contents.upload(notes, source);
      expect([sent(tm1), tm1.last.text], inspect(source)).toEqual(["PUT Contents('Blobs')/Contents('notes_1.txt')/tm1.Document/Content", "hello there"]);
    }
    await expect(client.contents.upload(notes, [42 as unknown as Uint8Array])).rejects.toBeInstanceOf(TypeError);
  });
});

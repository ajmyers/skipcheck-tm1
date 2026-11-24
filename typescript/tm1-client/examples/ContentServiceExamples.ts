// SPDX-License-Identifier: Apache-2.0

import { createWriteStream } from "node:fs";
import { readFile } from "node:fs/promises";
import { pipeline } from "node:stream/promises";
import {
  ContentPath,
  ContentScope,
  CubeReference,
  CubeReferenceCreate,
  DocumentCreate,
  Entry,
  FolderCreate,
  LinkCreate,
  ODataPath,
  ODataQuery,
  typeOf,
  type Tm1Client,
} from "@skipcheck/tm1-client";

// Examples for ContentService and ContentPath, quoted in their documentation.

export function path(): ContentPath {
  // @start region = path
  // Children by ID, which for most entries is the name plus a suffix for the kind
  const notes = ContentPath.applications().child("Planning").child("notes.txt.blob");
  // Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')
  // @end
  return notes;
}

export async function application(tm1: Tm1Client): Promise<Entry> {
  // @start region = application
  // An application: a folder with a link and a reference to a cube
  const planning = ContentPath.applications().child("Planning");
  await tm1.contents.create(ContentPath.applications(), ContentScope.Public, FolderCreate.builder().name("Planning").build());
  await tm1.contents.create(planning, ContentScope.Public, LinkCreate.builder().name("Guide").url("https://example.com/planning-guide").build());
  const sales = CubeReferenceCreate.builder().name("Sales").bindCube(ODataPath.of("Cubes").key("Sales")).build();
  return tm1.contents.create(planning, ContentScope.Public, sales);
  // @end
}

export async function upload(tm1: Tm1Client, file: string): Promise<void> {
  // @start region = upload
  // A document made in an application is a reference to one TM1 keeps in Blobs; the
  // reference, as create returns it, is where its content goes
  const planning = ContentPath.applications().child("Planning");
  const reference = await tm1.contents.create(planning, ContentScope.Public, DocumentCreate.builder().name("notes.txt").build());
  // TM1 refuses a chunked upload, so the content is sent whole
  await tm1.contents.upload(planning.child(reference), await readFile(file));
  // @end
}

export async function download(tm1: Tm1Client, file: string): Promise<void> {
  // @start region = download
  // Streamed to the file, never held whole
  const notes = ContentPath.applications().child("Planning").child("notes.txt.blob");
  await pipeline(tm1.contents.content(notes), createWriteStream(file));
  // @end
}

export async function cubes(tm1: Tm1Client): Promise<string[]> {
  // @start region = cubes
  // The cubes an application refers to, by a cast expand
  const planning = ContentPath.applications().child("Planning");
  const names: string[] = [];
  for await (const entry of tm1.contents.contents(planning, ContentScope.Public, ODataQuery.none().expand("tm1.CubeReference/Cube($select=Name)"))) {
    const cube = typeOf(Entry, entry) === "CubeReference" ? (entry as CubeReference).Cube : undefined;
    if (cube?.Name) names.push(cube.Name);
  }
  return names;
  // @end
}

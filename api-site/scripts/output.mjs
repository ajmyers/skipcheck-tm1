// SPDX-License-Identifier: Apache-2.0

// What the examples printed, as the live tests recorded it: api-site/example-output/<project>/<language>/<Example>.json,
// one file per examples file, written by each language's ExampleOutput (the Java one is
// java/tm1-client/src/test/java/examples/ExampleOutput.java). Each region's entry has what it
// printed, as lines, and a hash of its code when it ran:
//
//   { "example": "ConnectWalkthrough", "language": "java",
//     "regions": { "list-cubes": { "source": "<sha256>", "output": ["PNLCube", "PriceCube"] } } }
//
// The pages show a region's output under its code. The build fails when a region's code has
// changed since its output was recorded (the hash differs), when the languages printed different
// things, when a walkthrough's region prints but has no recording, and when a recording names a
// file or region that isn't there. Run the live tests to record, and commit what they write.

import { createHash } from 'node:crypto';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { LANGUAGES, languagesOf, PROJECTS, repoDir, siteDir } from './site.mjs';

export const outputDir = path.join(siteDir, 'example-output');

const START = /@start\s+region\s*=\s*"?([\w-]+)"?/;
const END = /@end\b/;

/** The hash of a region's code, as every language's recorder works it out: the lines between its
 * "@start region = <name>" and "@end", each without the whitespace around it, blank ones left
 * out, joined with newlines, as the SHA-256 of their UTF-8, in hexadecimal. Indentation doesn't
 * count, so reformatting a file only changes the hash where a line's own text changes. */
export function sourceHash(source, region) {
	const lines = [];
	let inside = false;
	let found = false;
	for (const line of source.split(/\r\n|\r|\n/)) {
		const start = START.exec(line);
		if (start) {
			inside = start[1] === region;
			found ||= inside;
		} else if (inside && END.test(line)) {
			inside = false;
		} else if (inside && line.trim() !== '') {
			lines.push(line.trim());
		}
	}
	if (!found) throw new Error('no region ' + region);
	return createHash('sha256').update(lines.join('\n'), 'utf8').digest('hex');
}

const recordings = new Map();

/** A language's recording of an examples file, or null. */
export async function recording(project, language, example) {
	const file = path.join(outputDir, project.id, language, example + '.json');
	if (!recordings.has(file)) recordings.set(file, await readFile(file, 'utf8').then(JSON.parse, () => null));
	return recordings.get(file);
}

/** What a region printed, in each of the project's languages: { outputs, problems }. outputs maps a
 * language's id to its lines; problems says what keeps the recording from being trusted. With
 * required, a region that prints in any language, or has a recording in one, must have a
 * recording in every one. */
export async function exampleOutput(project, example, region, { required = false } = {}) {
	const found = [];
	for (const language of languagesOf(project)) {
		const where = language.examples(project, example);
		const source = await readFile(path.join(repoDir, where), 'utf8');
		found.push({
			id: language.id,
			where,
			hash: sourceHash(source, region),
			prints: language.prints.test(regionText(source, region)),
			entry: (await recording(project, language.id, example))?.regions?.[region],
		});
	}
	return judge(project.id + ': ' + example + ' ' + region, found, required);
}

/** The verdict on a region's recordings, one per language: { id, where, hash, prints, entry }, the
 * entry being the language's recording of the region, if it has one. */
export function judge(what, languages, required) {
	const problems = [];
	const outputs = new Map();
	for (const { id, where, hash, entry } of languages) {
		if (entry && entry.source !== hash) problems.push(where + ': ' + what + ' has changed since its output was recorded. Run the live tests, which record it again.');
		else if (entry) outputs.set(id, entry.output);
	}
	const prints = languages.some((language) => language.prints);
	if (required && (prints || languages.some((language) => language.entry))) {
		for (const { where, entry } of languages.filter((language) => !language.entry)) {
			problems.push(where + ': ' + what + (prints ? ' prints, but its output was never recorded' : " has no recorded output, and another language's has") + '. Run the live tests, which record it.');
		}
	}
	const texts = [...outputs].map(([id, lines]) => [id, lines.join('\n')]);
	if (new Set(texts.map(([, text]) => text)).size > 1) {
		const differ = texts.filter(([, text]) => text !== texts[0][1]).map(([id]) => id);
		problems.push(what + ' printed something else in ' + differ.join(', ') + ' than in ' + texts[0][0] + ':\n' + texts.map(([id, text]) => '    ' + id + ': ' + JSON.stringify(text)).join('\n'));
	}
	return { outputs, problems };
}

function regionText(source, region) {
	const lines = [];
	let inside = false;
	for (const line of source.split(/\r\n|\r|\n/)) {
		const start = START.exec(line);
		if (start) inside = start[1] === region;
		else if (inside && END.test(line)) inside = false;
		else if (inside) lines.push(line);
	}
	return lines.join('\n');
}

/** Recordings that name a project, a language, an examples file or a region that isn't there. */
export async function strayRecordings() {
	const problems = [];
	const entries = async (dir) => readdir(dir, { withFileTypes: true }).catch(() => []);
	for (const projectDir of await entries(outputDir)) {
		const project = PROJECTS.find((p) => p.id === projectDir.name);
		const at = path.relative(repoDir, path.join(outputDir, projectDir.name));
		if (!project) {
			problems.push(at + ' is no project in scripts/site.mjs');
			continue;
		}
		for (const languageDir of await entries(path.join(outputDir, projectDir.name))) {
			const language = LANGUAGES.find((l) => l.id === languageDir.name && project.languages.includes(l.id));
			if (!language) {
				problems.push(at + '/' + languageDir.name + ' is no language of ' + project.id);
				continue;
			}
			for (const file of await entries(path.join(outputDir, projectDir.name, languageDir.name))) {
				const example = file.name.replace(/\.json$/, '');
				const where = language.examples(project, example);
				const source = await readFile(path.join(repoDir, where), 'utf8').catch(() => null);
				if (source === null) {
					problems.push(at + '/' + language.id + '/' + file.name + ' records ' + where + ", which isn't there");
					continue;
				}
				for (const region of Object.keys((await recording(project, language.id, example))?.regions ?? {})) {
					if (!source.split(/\r\n|\r|\n/).some((line) => START.exec(line)?.[1] === region)) {
						problems.push(at + '/' + language.id + '/' + file.name + ' records the region ' + region + ', which ' + where + " doesn't have");
					}
				}
			}
		}
	}
	return problems;
}

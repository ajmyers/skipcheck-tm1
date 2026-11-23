// SPDX-License-Identifier: Apache-2.0

// The search index of one project's docs in one language: every type and member on its pages, with
// a short description and where it is. reference.mjs writes it beside the pages, as
// /<project>/docs/<language>/search.json, and assets/search.js loads it when the reader first uses
// the search box. Matching and ranking are in assets/search-rank.js, which reads this shape:
//
//   { "types":   [[name, page, summary], ...],
//     "members": [[name, type, summary, anchor?], ...],    type: the index of its type in types;
//                                                           anchor: on its page, when not its name
//     "walkthroughs": [[title, page, summary], ...],       the project's walkthroughs, if it has any
//     "model": [[name, page, summary], ...] }             TM1's own types (docs/model/), if the
//                                                           project's pages link any
//
// Arrays rather than objects, and no constructors (a constructor is its type, by name), keep the
// largest index (the TM1 client's) small enough to load on the first keystroke.

/** The longest summary kept, in characters: enough to tell two members apart in a list of results. */
export const SUMMARY_LENGTH = 110;

/** A summary cut at a word to at most SUMMARY_LENGTH characters, with an ellipsis if it was cut. */
export function shorten(text, length = SUMMARY_LENGTH) {
	const plain = (text ?? '').replace(/\s+/g, ' ').trim();
	if (plain.length <= length) return plain;
	const cut = plain.slice(0, length - 1);
	const space = cut.lastIndexOf(' ');
	return (space > length / 2 ? cut.slice(0, space) : cut).replace(/[\s,;:.]+$/, '') + '…';
}

/**
 * The index of a list of types, in the order given. Each type is { name, page, summary, members },
 * each member { name, anchor, summary, kind }. A member named twice (an overload) is listed once,
 * at its first anchor: the reader is looking for the name, and lands on the first of them.
 * walkthroughs are { title, page, summary }: found by their title, or by words of their summary.
 * model is TM1's own types, { name, page, summary }: the types only, since their properties have no
 * anchors, and listing them would make the index several times larger.
 */
export function searchIndex(types, walkthroughs = [], model = []) {
	const index = { types: [], members: [] };
	for (const type of types) {
		const at = index.types.push([type.name, type.page, shorten(type.summary)]) - 1;
		const seen = new Set();
		for (const member of type.members ?? []) {
			if (member.kind === 'constructor' || seen.has(member.name)) continue;
			seen.add(member.name);
			const entry = [member.name, at, shorten(member.summary)];
			if (member.anchor !== member.name) entry.push(member.anchor);
			index.members.push(entry);
		}
	}
	if (walkthroughs.length > 0) index.walkthroughs = walkthroughs.map((w) => [w.title, w.page, shorten(w.summary)]);
	if (model.length > 0) index.model = model.map((t) => [t.name, t.page, shorten(t.summary)]);
	return index;
}

/**
 * The index of the Docker pages (docker.mjs), /pa-docker/search.json: each page by its title and
 * summary, and each of its headings, at its anchor. pages are { title, address, summary, headings },
 * each heading { text, id }. Its own index, not part of a docs language's: the Docker pages have no
 * language, and their headings are setup steps, not names in the code. The shape:
 *
 *   { "pages":    [[title, address, summary], ...],
 *     "headings": [[text, page, id], ...] }               page: the index of its page in pages
 */
export function headingIndex(pages) {
	const index = { pages: [], headings: [] };
	for (const page of pages) {
		const at = index.pages.push([page.title, page.address, shorten(page.summary)]) - 1;
		for (const heading of page.headings ?? []) index.headings.push([heading.text, at, heading.id]);
	}
	return index;
}

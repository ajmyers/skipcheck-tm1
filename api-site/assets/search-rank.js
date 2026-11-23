// SPDX-License-Identifier: Apache-2.0

// Matching and ranking for the docs search: what search.js shows for what the reader types, from a
// language's index (scripts/search.mjs writes it). Its own module so the tests can run it.
//
// A name is read as its words, whatever the language's casing: model_cubes, modelCubes and
// ModelCubesAsync are all "model cubes ...". So a query matches case-insensitively, with or
// without separators, and by any word: "cubes" finds model_cubes and ModelCubes alike. A query
// with a dot ("Tm1Client.model") is a type, then a member of it.

/** A name's words, in lower case: Tm1Client is tm1 client, ODataQuery o data query. */
export function words(name) {
	return name
		.replace(/([a-z0-9])([A-Z])/g, '$1 $2')
		.replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
		.toLowerCase()
		.split(/[^a-z0-9]+/)
		.filter(Boolean);
}

// How well a name matches, best first; 0 is no match. A type counts one better than a member.
const EXACT = 6; // the whole name: modelcubes for ModelCubes or model_cubes
const START = 5; // its first words: model cubes for ModelCubesAsync, cube for CubeService
const START_PART = 4; // the start of its first word: cub for CubeService, cube for cubes
const WORD = 3; // a later word, or its start: cubes or cub for model_cubes
const PART = 2; // words, the last in part: modelc for ModelCubes, cubes for CubeService (cube s...);
//                 or the starts of some of its words, in order: get cubes for getModelCubes
const INSIDE = 1; // anywhere in it: data for metadata
const TYPE_BONUS = 1;

/** How well a name (as its words) matches a query (as its words). */
export function matchName(nameWords, queryWords) {
	const query = queryWords.join('');
	if (!query) return 0;
	const name = nameWords.join('');
	if (name === query) return EXACT;
	// Where each word starts in the name, and where the last one ends
	const starts = [];
	let at = 0;
	for (const word of nameWords) {
		starts.push(at);
		at += word.length;
	}
	let best = 0;
	for (let i = 0; i < nameWords.length; i++) {
		if (!name.startsWith(query, starts[i])) continue;
		const end = starts[i] + query.length;
		const whole = end === name.length || starts.includes(end);
		const across = end > starts[i] + nameWords[i].length;
		best = Math.max(best, across && !whole ? PART : i > 0 ? WORD : whole ? START : START_PART);
	}
	if (best > 0) return best;
	let next = 0;
	for (const word of queryWords) {
		while (next < nameWords.length && !nameWords[next].startsWith(word)) next++;
		if (next++ >= nameWords.length) return name.includes(query) ? INSIDE : 0;
	}
	return PART;
}

/** An index as search() reads it: each name split into words once. */
export function prepare(index) {
	const types = index.types.map(([name, page, summary]) => ({ kind: 'type', name, words: words(name), summary, href: page }));
	const members = index.members.map(([name, type, summary, anchor = name]) => ({
		kind: 'member',
		name,
		words: words(name),
		type: types[type],
		summary,
		href: types[type].href + '#' + anchor,
	}));
	return { types, members };
}

/**
 * The entries of a prepared index that match a query, best first, at most limit of them. A better
 * match comes first, a type counting one better than a member, so a type comes before a member
 * matched as well or one step better (CubeService before AttributeOwner.cube for "cube", but
 * after Tm1Client.cubes for "cubes"). Then the shorter name, then the order of the docs.
 */
export function search(prepared, query, limit = 12) {
	const text = query.trim();
	const queryWords = words(text);
	if (queryWords.length === 0) return [];
	const found = [];
	const consider = (entry, score) => {
		if (score > 0) found.push({ entry, score, order: found.length });
	};
	for (const type of prepared.types) {
		const score = matchName(type.words, queryWords);
		consider(type, score && score + TYPE_BONUS);
	}
	const dot = text.lastIndexOf('.');
	const typeWords = dot > 0 ? words(text.slice(0, dot)) : [];
	const memberWords = dot > 0 ? words(text.slice(dot + 1)) : queryWords;
	for (const member of prepared.members) {
		if (typeWords.length > 0 && !matchName(member.type.words, typeWords)) continue;
		// "Tm1Client." lists the type's members
		consider(member, memberWords.length > 0 ? matchName(member.words, memberWords) : INSIDE);
	}
	found.sort(
		(a, b) =>
			b.score - a.score ||
			(a.entry.kind === 'type' ? 0 : 1) - (b.entry.kind === 'type' ? 0 : 1) ||
			a.entry.name.length - b.entry.name.length ||
			a.order - b.order,
	);
	return found.slice(0, limit).map(({ entry }) => entry);
}

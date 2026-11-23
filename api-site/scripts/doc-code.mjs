// SPDX-License-Identifier: Apache-2.0

// Code on the docs site comes from compiled code, never typed by hand: the content files are held
// to it by markdown.mjs, and doc comments by this. typedCode finds a block of code typed into a doc
// comment, in each language's syntax, and the reference (reference.mjs) fails on any in a comment
// it reads. A Javadoc quotes an example instead: {@snippet class = examples.X region = y}, a region
// every language has and compiles, or {@snippet file = examples/f}, a file the examples' tests read.
// The other languages' pages show the same region in their own language.

// A Javadoc {@snippet}'s attributes, up to its end or the colon that starts an inline body
const SNIPPET = /\{@snippet\b([^:}]*)([:}])/g;

/** Each block of code typed into a doc comment in a language, described with its line in the
 * comment; none for a comment that only quotes examples. */
export function typedCode(doc, language) {
	const found = [];
	const lines = (doc ?? '').split('\n');
	const at = (index) => ' at line ' + (index + 1);
	if (language === 'java') {
		lines.forEach((line, i) => {
			if (/<pre\b/i.test(line)) found.push('a <pre> block' + at(i));
			for (const [, attributes, end] of line.matchAll(SNIPPET)) {
				const quotes = (/\bclass\s*=\s*"?examples\.\w+/.test(attributes) && /\bregion\s*=/.test(attributes)) || /\bfile\s*=/.test(attributes);
				if (end === ':') found.push('an inline {@snippet}' + at(i));
				else if (!quotes) found.push('a {@snippet} that names no example region or file' + at(i));
			}
		});
	} else if (language === 'dotnet') {
		// <code> is a block in an XML doc comment; a name in running text is <c>
		lines.forEach((line, i) => {
			if (/<code\b/.test(line)) found.push('a <code> block' + at(i));
		});
	} else if (language === 'typescript') {
		let fenced = false;
		lines.forEach((line, i) => {
			if (/^\s*(```|~~~)/.test(line)) {
				if (!fenced) found.push('a fenced code block' + at(i));
				fenced = !fenced;
			} else if (!fenced && /^\s*@example\b/.test(line)) found.push('an @example block' + at(i));
		});
	} else if (language === 'python') {
		lines.forEach((line, i) => {
			if (/::\s*$/.test(line)) found.push('a literal block (::)' + at(i));
			else if (/^\s*>>>/.test(line)) found.push('a doctest' + at(i));
		});
	}
	return found;
}

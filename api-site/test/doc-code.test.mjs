// SPDX-License-Identifier: Apache-2.0

// Tests of the gate that no doc comment has code typed into it (scripts/doc-code.mjs).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { typedCode } from '../scripts/doc-code.mjs';

test('a Javadoc that quotes examples passes', () => {
	const doc = 'Lists cubes:\n\n{@snippet class = examples.CubeServiceExamples region = list}\n\n<p>\nAnd {@code inline} code.\n{@snippet file = examples/application.yml}';
	assert.deepEqual(typedCode(doc, 'java'), []);
});

test('a Javadoc <pre> block fails, with or without {@code}', () => {
	assert.deepEqual(typedCode('Text.\n\n<pre>{@code\ntm1.cubes();\n}</pre>', 'java'), ['a <pre> block at line 3']);
	assert.deepEqual(typedCode('<pre class="code">\ntm1:\n  user: admin\n</pre>', 'java'), ['a <pre> block at line 1']);
});

test('an inline {@snippet} fails, as does one that names no region or file', () => {
	assert.deepEqual(typedCode('{@snippet :\ntm1.cubes();\n}', 'java'), ['an inline {@snippet} at line 1']);
	assert.deepEqual(typedCode('{@snippet lang = java :\ntm1.cubes();\n}', 'java'), ['an inline {@snippet} at line 1']);
	assert.deepEqual(typedCode('{@snippet class = examples.CubeServiceExamples}', 'java'), ['a {@snippet} that names no example region or file at line 1']);
});

test('a .NET <code> block fails, and <c> passes', () => {
	assert.deepEqual(typedCode('<summary>A <c>}Drill_Sales</c> process.</summary>', 'dotnet'), []);
	assert.deepEqual(typedCode('<summary>Streams:\n<code>\nawait foreach (var c in cells) { }\n</code></summary>', 'dotnet'), ['a <code> block at line 2']);
	assert.deepEqual(typedCode('<example><code>var env = Build();</code></example>', 'dotnet'), ['a <code> block at line 1']);
});

test('a TSDoc fence or @example fails, and inline code passes', () => {
	assert.deepEqual(typedCode('Calls `build()`, once.', 'typescript'), []);
	assert.deepEqual(typedCode('Starts it:\n\n```ts\nawait env.start();\n```\nThen more.\n~~~\nx\n~~~', 'typescript'), ['a fenced code block at line 3', 'a fenced code block at line 7']);
	assert.deepEqual(typedCode('Text.\n@example\nfromConfig("pa-environment.yaml");', 'typescript'), ['an @example block at line 2']);
});

test('a Python literal block or doctest fails, and inline code passes', () => {
	assert.deepEqual(typedCode('Builds a ``Tm1Client``: see :meth:`build`.', 'python'), []);
	assert.deepEqual(typedCode('Cell coordinates::\n\n    Tuples.of(cell)', 'python'), ['a literal block (::) at line 1']);
	assert.deepEqual(typedCode('Example:\n\n>>> ODataQuery.none()', 'python'), ['a doctest at line 3']);
});

test('no comment, no code', () => {
	for (const language of ['java', 'python', 'dotnet', 'typescript']) assert.deepEqual(typedCode(undefined, language), []);
});

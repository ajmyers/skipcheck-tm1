// SPDX-License-Identifier: Apache-2.0

// Tests of the docs search: the index each docs language gets (scripts/search.mjs).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { searchIndex, shorten, SUMMARY_LENGTH } from '../scripts/search.mjs';

test('the index lists each type, then its members, by the index of their type', () => {
	const index = searchIndex([
		{ name: 'Tm1Client', page: 'Tm1Client.html', summary: 'The client for one TM1 server.', members: [{ name: 'model_cubes', anchor: 'model_cubes', summary: 'The cubes.', kind: 'method' }] },
		{ name: 'CubeService', page: 'CubeService.html', summary: 'Cubes.', members: [{ name: 'get', anchor: 'get', summary: 'One cube.', kind: 'method' }] },
	]);
	assert.deepEqual(index, {
		types: [
			['Tm1Client', 'Tm1Client.html', 'The client for one TM1 server.'],
			['CubeService', 'CubeService.html', 'Cubes.'],
		],
		members: [
			['model_cubes', 0, 'The cubes.'],
			['get', 1, 'One cube.'],
		],
	});
});

test('an overload is listed once, at its first anchor, and constructors not at all', () => {
	const index = searchIndex([
		{
			name: 'Tm1Client',
			page: 'Tm1Client.html',
			summary: '',
			members: [
				{ name: 'Tm1Client', anchor: 'Tm1Client', summary: 'Makes one.', kind: 'constructor' },
				{ name: 'execute', anchor: 'execute', summary: 'Runs a request.', kind: 'method' },
				{ name: 'execute', anchor: 'execute-2', summary: 'Runs a request, with a timeout.', kind: 'method' },
			],
		},
	]);
	assert.deepEqual(index.members, [['execute', 0, 'Runs a request.']]);
});

test('a member whose anchor is not its name keeps the anchor', () => {
	const index = searchIndex([{ name: 'T', page: 'T.html', summary: '', members: [{ name: 'x', anchor: 'x-2', summary: '', kind: 'field' }] }]);
	assert.deepEqual(index.members, [['x', 0, '', 'x-2']]);
});

test('a long summary is cut at a word, with an ellipsis', () => {
	assert.equal(shorten('Short.'), 'Short.');
	assert.equal(shorten('  spread\n  over   lines  '), 'spread over lines');
	const long = shorten('word '.repeat(60));
	assert.ok(long.length <= SUMMARY_LENGTH, long);
	assert.match(long, /^word( word)*…$/);
	assert.equal(shorten('one, two three four', 12), 'one, two…');
	assert.equal(shorten(undefined), '');
});

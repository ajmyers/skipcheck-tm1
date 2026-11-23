// SPDX-License-Identifier: Apache-2.0

// Tests of the docs search: the index each docs language gets (scripts/search.mjs), and how the
// search box matches and ranks what's in it (assets/search-rank.js).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { matchName, prepare, search, words } from '../assets/search-rank.js';
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

// The same types in Python and .NET, as their indexes have them
const page = (name, members) => ({ name, page: name + '.html', summary: name + '.', members: members.map((m) => ({ name: m, anchor: m, summary: m + '.', kind: 'method' })) });
const python = prepare(
	searchIndex([
		page('Tm1Client', ['cubes', 'processes', 'process_debugging']),
		page('CubeService', ['get', 'model_cubes', 'control_cubes']),
		page('AttributeOwner', ['cube']),
		page('ProcessService', ['execute_process']),
	]),
);
const dotnet = prepare(searchIndex([page('Tm1Client', ['Cubes', 'Processes']), page('CubeService', ['GetAsync', 'ModelCubesAsync', 'ControlCubesAsync']), page('AttributeOwner', ['Cube'])]));
const names = (results) => results.map((r) => (r.type ? r.type.name + '.' : '') + r.name);

test('a name is read as its words, whatever its casing', () => {
	assert.deepEqual(words('model_cubes'), ['model', 'cubes']);
	assert.deepEqual(words('ModelCubesAsync'), ['model', 'cubes', 'async']);
	assert.deepEqual(words('modelCubes'), ['model', 'cubes']);
	assert.deepEqual(words('Tm1Client.Builder'), ['tm1', 'client', 'builder']);
	assert.deepEqual(words('ODataQuery'), ['o', 'data', 'query']);
	assert.deepEqual(words('DATA_SPREAD_FAILED'), ['data', 'spread', 'failed']);
});

test("a member's name finds it in the page's language, and links to its anchor", () => {
	const [first] = search(python, 'model_cubes');
	assert.equal(first.name, 'model_cubes');
	assert.equal(first.type.name, 'CubeService');
	assert.equal(first.href, 'CubeService.html#model_cubes');
	assert.deepEqual(names(search(dotnet, 'ModelCubes')).slice(0, 1), ['CubeService.ModelCubesAsync']);
});

test('names match case-insensitively, with or without separators', () => {
	for (const query of ['model_cubes', 'MODEL_CUBES', 'modelcubes', 'ModelCubes', 'model cubes']) assert.equal(names(search(python, query))[0], 'CubeService.model_cubes', query);
	for (const query of ['modelcubes', 'model_cubes', 'modelCubesAsync']) assert.equal(names(search(dotnet, query))[0], 'CubeService.ModelCubesAsync', query);
});

test('a word of a name finds it: cubes finds model_cubes and ModelCubesAsync', () => {
	assert.ok(names(search(python, 'cubes')).includes('CubeService.model_cubes'));
	assert.ok(names(search(dotnet, 'cubes')).includes('CubeService.ModelCubesAsync'));
	assert.ok(names(search(python, 'cub')).includes('CubeService.control_cubes'));
	assert.deepEqual(names(search(python, 'exec proc')), ['ProcessService.execute_process']);
});

test('a type ranks above a member matched as well, or a step better', () => {
	// CubeService starts with the whole word cube; AttributeOwner.cube is all of it
	assert.deepEqual(names(search(python, 'cube')).slice(0, 2), ['CubeService', 'AttributeOwner.cube']);
	assert.deepEqual(names(search(python, 'process')).slice(0, 1), ['ProcessService']);
	// cubes is exactly a member's name, and only part of a word of CubeService's
	assert.deepEqual(names(search(dotnet, 'cubes')).slice(0, 3), ['Tm1Client.Cubes', 'CubeService', 'CubeService.ModelCubesAsync']);
});

test('the better match comes first: whole name, its start, a word, part of a word, anywhere', () => {
	const name = words('ModelCubesAsync');
	const scores = ['modelcubesasync', 'model cubes', 'mod', 'cubes', 'modelc', 'elcub', 'xyz'].map((q) => matchName(name, words(q)));
	assert.deepEqual(scores, [...scores].sort((a, b) => b - a));
	assert.equal(new Set(scores).size, scores.length);
	assert.equal(scores.at(-1), 0);
});

test('a query with a dot is a type, then its member', () => {
	assert.deepEqual(names(search(python, 'Tm1Client.proc')), ['Tm1Client.processes', 'Tm1Client.process_debugging']);
	assert.deepEqual(names(search(python, 'tm1client.')), ['Tm1Client', 'Tm1Client.cubes', 'Tm1Client.processes', 'Tm1Client.process_debugging']);
});

test('nothing to search for finds nothing, and the results are limited', () => {
	assert.deepEqual(search(python, ''), []);
	assert.deepEqual(search(python, '  _. '), []);
	assert.deepEqual(search(python, 'nothing'), []);
	assert.equal(search(python, 'e', 3).length, 3);
});

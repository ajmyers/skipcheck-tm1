// SPDX-License-Identifier: Apache-2.0

// Tests of the gate that every Java member has its counterpart in each language
// (scripts/members.mjs).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { checkMembers, findMember, listFile, parseList, readList, stale } from '../scripts/members.mjs';
import { PROJECTS, repoDir } from '../scripts/site.mjs';

const member = (name, kind = 'method', extra = {}) => ({ name, kind, static: false, signature: name, returns: '', params: [], endpoints: [], doc: '', deprecated: false, ...extra });
const type = (name, members) => ({ name, package: 'p', kind: 'class', signature: name, doc: '', deprecated: false, members });

/** The gate on one Java type and its counterpart, with a list, and the list's stale lines. */
function gate(language, javaMembers, members, listText = '') {
	const list = parseList(listText, 'list.txt');
	const used = new Set();
	const java = type('Thing', javaMembers);
	const result = checkMembers({ language, javaTypes: [java], counterparts: new Map([['Thing', type('Thing', members)]]), list, used });
	return { ...result, stale: stale(list, used) };
}

test('a Java member is found by the parity rule in each language', () => {
	const java = [member('modelCubes'), member('getUrl'), member('NOT_FOUND', 'constant'), member('DEFAULT_IMAGE', 'field', { static: true }), member('and')];
	assert.deepEqual(gate('python', java, [member('model_cubes'), member('url', 'property'), member('NOT_FOUND', 'constant'), member('DEFAULT_IMAGE', 'constant'), member('and_')]).missing, []);
	assert.deepEqual(gate('dotnet', java, [member('ModelCubesAsync'), member('Url', 'property'), member('NotFound', 'constant'), member('DefaultImage', 'constant'), member('And')]).missing, []);
	assert.deepEqual(gate('typescript', java, [member('modelCubes'), member('url', 'property'), member('NOT_FOUND', 'constant'), member('DEFAULT_IMAGE', 'property'), member('and')]).missing, []);
});

test('.NET names are compared without case: odataLiteral is ODataLiteral, ETAG is ETag', () => {
	assert.deepEqual(gate('dotnet', [member('odataLiteral'), member('ETAG', 'field', { static: true })], [member('ODataLiteral', 'property'), member('ETag', 'constant')]).missing, []);
	assert.deepEqual(gate('python', [member('odataLiteral')], [member('odataliteral_x')]).missing, ['Thing.odataLiteral']);
});

test("a Java member a language lacks fails, unless it's listed with a reason", () => {
	const java = [member('jsonMapper'), member('build')];
	assert.deepEqual(gate('python', java, [member('build')]).missing, ['Thing.jsonMapper']);
	assert.deepEqual(gate('python', java, [member('build')], 'Thing.jsonMapper | Jackson is Java\'s'), { missing: [], wrong: [], stale: [] });
});

test("Java's Object and enum plumbing, constructors and instance fields aren't held to a counterpart", () => {
	const java = [member('toString'), member('hashCode'), member('equals'), member('values'), member('valueOf'), member('Thing', 'constructor'), member('count', 'field')];
	assert.deepEqual(gate('typescript', java, []).missing, []);
});

test('a listed member the language has after all fails', () => {
	const { wrong } = gate('python', [member('compareTo')], [member('compare_to')], '# a comment\n\nThing.compareTo | Python compares with <');
	assert.equal(wrong.length, 1);
	assert.match(wrong[0], /Thing\.compareTo is listed as missing, but Thing has compare_to: take its line out \(line 3\)/);
});

test('a renamed member is found by its new name, and fails when that is missing', () => {
	assert.deepEqual(gate('dotnet', [member('PRIVATE_IMAGE', 'field', { static: true })], [member('PrivateImageSetting', 'constant')], 'Thing.PRIVATE_IMAGE -> PrivateImageSetting | the record has the name'), { missing: [], wrong: [], stale: [] });
	const { wrong } = gate('dotnet', [member('PRIVATE_IMAGE', 'field', { static: true })], [member('PrivateImage', 'constant')], 'Thing.PRIVATE_IMAGE -> PrivateImageSetting | the record has the name');
	assert.match(wrong[0], /Thing\.PRIVATE_IMAGE is listed as PrivateImageSetting, which Thing lacks/);
	// Its usual name may be something else's: ACCEPT_LICENSE is AcceptLicenseSetting, beside an AcceptLicense()
	assert.deepEqual(gate('dotnet', [member('ACCEPT_LICENSE', 'field', { static: true })], [member('AcceptLicense'), member('AcceptLicenseSetting', 'constant')], 'Thing.ACCEPT_LICENSE -> AcceptLicenseSetting | the method has the name').wrong, []);
	assert.equal(findMember('dotnet', member('ACCEPT_LICENSE', 'field', { static: true }), type('Thing', [member('AcceptLicense'), member('AcceptLicenseSetting')]), 'AcceptLicenseSetting').name, 'AcceptLicenseSetting');
});

test('a line for a member Java no longer has fails', () => {
	assert.deepEqual(gate('python', [member('build')], [member('build')], 'Thing.gone | was Java only').stale, ["Thing.gone (line 1): Java has no such member, or the language no such type: take its line out"]);
});

test("a list's lines need a reason, and say each member once", () => {
	assert.throws(() => parseList('Thing.member', 'x.txt'), /x\.txt:1: expected "Type\.member \| reason"/);
	assert.throws(() => parseList('Thing.member |', 'x.txt'), /x\.txt:1/);
	assert.throws(() => parseList('Thing.a | one\nThing.a | two', 'x.txt'), /x\.txt:2: Thing\.a is listed twice/);
	assert.deepEqual([...parseList('A.B.c -> d | why', 'x.txt')], [['A.B.c', { renamed: 'd', reason: 'why', line: 1 }]]);
});

test("every list in the repository reads, and gives each line a reason", async () => {
	for (const project of PROJECTS) {
		for (const language of project.languages.filter((l) => l !== 'java')) {
			for (const [key, entry] of await readList(repoDir, listFile(project, language))) assert.ok(entry.reason.length > 10, key + ' needs a reason');
		}
	}
});

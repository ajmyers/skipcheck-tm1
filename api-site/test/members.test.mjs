// SPDX-License-Identifier: Apache-2.0

// Tests of the gate that every Java member has its counterpart in each language
// (scripts/members.mjs).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { checkMembers, checkTypes, findMember, guessMember, javaIsms, javaOnlyNames, languageNames, listFile, parseList, readList, stale, typeCandidates, withoutComments } from '../scripts/members.mjs';
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
	// An enum is a string union in TypeScript, whose values are PascalCase
	assert.deepEqual(gate('typescript', [member('NOT_FOUND', 'constant')], [member('NotFound', 'constant')]).missing, []);
});

/** The type gate on Java types (by name) and a language's (by name), with a list, and the list's
 * stale lines. */
function typeGate(language, javaNames, names, listText = '') {
	const list = parseList(listText, 'list.txt');
	const used = new Set();
	const api = names.map((n) => type(n, []));
	const counterparts = new Map(javaNames.map((n) => [n, typeCandidates(language, n, list.get(n)?.renamed).map((c) => api.find((t) => t.name === c)).find(Boolean) ?? null]));
	const result = checkTypes({ javaTypes: javaNames.map((n) => type(n, [])), counterparts, list, used });
	return { ...result, stale: stale(list, used) };
}

test("a Java type a language lacks fails, unless it's listed with a reason", () => {
	assert.deepEqual(typeGate('python', ['Tm1Client', 'Utf8Repair'], ['Tm1Client']).missing, ['Utf8Repair']);
	assert.deepEqual(typeGate('python', ['Tm1Client', 'Utf8Repair'], ['Tm1Client'], "Utf8Repair | a Java internal"), { missing: [], wrong: [], stale: [] });
});

test('a nested Java type is found nested, joined or alone, and as an Error in Python', () => {
	assert.deepEqual(typeGate('typescript', ['Tm1Timeouts.Builder', 'Tm1Client.Builder', 'CognosContainer.User'], ['Tm1Timeouts.Builder', 'Tm1ClientBuilder', 'User']).missing, []);
	assert.deepEqual(typeGate('python', ['Tm1TransportException.Phase', 'Tm1NotFoundException'], ['Tm1TransportError.Phase', 'Tm1NotFoundError']).missing, []);
});

test("a line for a type covers the types nested in it, but a renamed one doesn't", () => {
	const java = ['ODataCollectionDecoder', 'ODataCollectionDecoder.Event', 'ODataCollectionDecoder.Event.Item'];
	assert.deepEqual(typeGate('dotnet', java, [], 'ODataCollectionDecoder | a Java internal'), { missing: [], wrong: [], stale: [] });
	assert.deepEqual(typeGate('dotnet', ['Tm1Endpoint', 'Tm1Endpoint.List'], ['Tm1EndpointAttribute'], 'Tm1Endpoint -> Tm1EndpointAttribute | an attribute').missing, ['Tm1Endpoint.List']);
});

test('a listed type the language has after all fails, as does a renamed one it lacks, and a line for a type Java lacks', () => {
	assert.match(typeGate('typescript', ['Tuples'], ['Tuples'], 'Tuples | an object').wrong[0], /Tuples is listed as missing, but the language has Tuples: take its line out \(line 1\)/);
	assert.match(typeGate('dotnet', ['Tm1Timeouts.Builder'], ['Tm1Timeouts.Builder'], 'Tm1Timeouts.Builder -> TimeoutsBuilder | the method has the name').wrong[0], /Tm1Timeouts\.Builder is listed as TimeoutsBuilder, which the language lacks \(line 1\)/);
	assert.deepEqual(typeGate('dotnet', ['Tm1Timeouts.Builder'], ['Tm1Timeouts.TimeoutsBuilder'], 'Tm1Timeouts.Builder -> TimeoutsBuilder | the method has the name'), { missing: [], wrong: [], stale: [] });
	assert.match(typeGate('python', [], [], 'Gone | was Java only').stale[0], /^Gone \(line 1\): Java has no such type or member/);
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
	assert.deepEqual(gate('python', [member('build')], [member('build')], 'Thing.gone | was Java only').stale, ["Thing.gone (line 1): Java has no such type or member, or the language no such type: take its line out"]);
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

test("a Java name a language's code has is that language's: a constant may be PascalCase where the type has no page", () => {
	const names = languageNames([type('Tm1Client', [member('with_sandbox')])], 'const kind = Tm1ErrorKind.CredentialRejected;');
	assert.ok(names.has('with_sandbox') && names.has('Tm1ErrorKind') && names.has('CredentialRejected'));
	assert.equal(guessMember('typescript', member('CREDENTIAL_REJECTED', 'constant'), names), 'CredentialRejected');
	assert.equal(guessMember('python', member('withSandbox'), names), 'with_sandbox');
	// Nothing to go by: the parity rule's first name
	assert.equal(guessMember('dotnet', member('close'), new Set()), 'CloseAsync');
});

test("a language's comments are left out of its names, which they may give as Java's", () => {
	assert.equal(withoutComments('x = 1  # as Java\'s Mono\n"""Java\'s Flux"""\ny = "#1"', 'python').includes('Mono'), false);
	assert.equal(withoutComments('/** Java\'s Mono */ const url = "http://x"; // Flux', 'typescript').trim(), 'const url = "http://x";');
});

test("the names only Java has: its types, members and signatures' types, less the language's", () => {
	const java = [type('Tm1Batch', [member('client', 'method', { signature: 'Mono<Void> run()' })])];
	const only = javaOnlyNames(java, languageNames([type('Tm1Batch', [member('client')])], 'batch.run()'));
	assert.deepEqual([...only].sort(), ['Mono', 'Void']);
});

test("an overview's Java-only names: any in a code fragment, a capitalized one mid-sentence, none in an example", () => {
	const only = new Set(['Mono', 'Flux', 'withSandbox', 'List', 'paw', 'JsonNode']);
	assert.deepEqual(javaIsms('<p>Each returns its own <code>Mono</code> or Flux.</p>', only), ['Flux', 'Mono']);
	assert.deepEqual(javaIsms('<p>See <a href="x"><code>Tm1Client.withSandbox</code></a>.</p>', only), ['withSandbox']);
	// English at the start of a sentence or an item, a file name, and the page's own examples don't count
	assert.deepEqual(javaIsms('<p>List cubes: <code>paw.sh</code>, <code>docker/paw/x</code></p><ul><li>Flux</li></ul><pre><code>Mono.just(1)</code></pre>', only), []);
	assert.deepEqual(javaIsms('<p><code>Map&lt;String, JsonNode&gt;</code></p>', only), ['JsonNode']);
});

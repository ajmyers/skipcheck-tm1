// SPDX-License-Identifier: Apache-2.0

// Tests of the extractors (extract/): what they read of each language's sources, and the members a
// type inherits, which count as its own when the docs look for a Java member's counterpart.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { siteDir } from '../scripts/site.mjs';

/** Runs an extractor on sources written to a temporary folder, and reads what it wrote. */
function extract(files, run) {
	const dir = mkdtempSync(path.join(tmpdir(), 'extract-'));
	try {
		for (const [name, text] of Object.entries(files)) writeFileSync(path.join(dir, name), text);
		const out = path.join(dir, 'out.json');
		run(dir, out);
		return Object.fromEntries(JSON.parse(readFileSync(out, 'utf8')).map((t) => [t.name, t]));
	} finally {
		rmSync(dir, { recursive: true, force: true });
	}
}
const names = (t) => t.members.map((m) => m.name + (m.inherited ? '<' + m.inherited : ''));

test('the .NET extractor follows base classes and extension methods, and joins a generic and a static class', () => {
	const types = extract(
		{
			'Errors.cs': [
				'namespace N;',
				'public class Tm1Exception : Exception',
				'{',
				'    public string Method { get; }',
				'    public virtual bool IsRetryable() => false;',
				'}',
				'public sealed class Tm1ApiException : Tm1Exception',
				'{',
				'    public override bool IsRetryable() => true;',
				'    public static readonly Tm1ApiException Empty = new();',
				'    public IAsyncEnumerable<R> StreamAsync<R>(string mdx, Func<Cell, R> map) => throw null;',
				'}',
				'public enum Scope',
				'{',
				'    Public,',
				'}',
				'public static class Scopes',
				'{',
				'    public static string Navigation(this Scope scope) => "x";',
				'}',
				'public static class Tm1Request',
				'{',
				'    public static Tm1Request<T> Get<T>(string path) => null;',
				'}',
				'public sealed record Tm1Request<T>',
				'{',
				'    public string Path { get; }',
				'}',
			].join('\n'),
		},
		(dir, out) => execFileSync(process.execPath, [path.join(siteDir, 'extract', 'dotnet_api.mjs'), dir, out]),
	);
	assert.deepEqual(names(types.Tm1ApiException), ['IsRetryable', 'Empty', 'StreamAsync', 'Method<Tm1Exception']);
	assert.equal(types.Tm1ApiException.members[1].kind, 'field');
	assert.deepEqual(names(types.Scope), ['Public', 'Navigation<Scopes']);
	assert.deepEqual(types.Scope.members[1].params, []);
	assert.deepEqual(names(types.Tm1Request), ['Get', 'Path']);
});

test('the TypeScript extractor follows extends, and reads parameter properties and set statics', () => {
	const types = extract(
		{
			'errors.ts': [
				'export class Tm1Exception extends Error {',
				'  constructor(',
				'    message: string,',
				'    /** The request\'s method. */',
				'    readonly method: string,',
				'    private readonly secret: string,',
				'  ) {',
				'    super(message);',
				'  }',
				'',
				'  isRetryable(): boolean {',
				'    return false;',
				'  }',
				'}',
				'',
				'export class Tm1ApiException extends Tm1Exception {}',
				'',
				'export class ODataProperty {',
				'  static readonly DEFAULTS = new ODataProperty("x");',
				'',
				'  private constructor(readonly path: string) {}',
				'',
				'  eq(value: unknown): string {',
				'    return "";',
				'  }',
				'}',
			].join('\n'),
		},
		(dir, out) => execFileSync(process.execPath, [path.join(siteDir, 'extract', 'typescript_api.mjs'), dir, out, 'pkg']),
	);
	assert.deepEqual(names(types.Tm1Exception), ['Tm1Exception', 'method', 'isRetryable']);
	assert.equal(types.Tm1Exception.members[1].doc, "The request's method.");
	assert.deepEqual(names(types.Tm1ApiException), ['method<Tm1Exception', 'isRetryable<Tm1Exception']);
	assert.deepEqual(names(types.ODataProperty), ['DEFAULTS', 'path', 'eq']);
});

test("the TypeScript extractor reads a namespace's types and functions, string unions and exported objects", () => {
	const types = extract(
		{
			'kinds.ts': [
				'/** What went wrong. */',
				'export type Tm1ErrorKind =',
				'  /** The object doesn\'t exist. */',
				'  | "NotFound"',
				'  | "Other";',
				'',
				'/** As values. */',
				'export const Tm1ErrorKind = {',
				'  NotFound: "NotFound",',
				'  Other: "Other",',
				'  /** The kind for a status. */',
				'  of(status: number): Tm1ErrorKind {',
				'    return "Other";',
				'  },',
				'} as const;',
				'',
				'export type Alias = Tm1ErrorKind | undefined;',
				'',
				'export const Tuples = {',
				'  NONE: "",',
				'  of(tuple: Map<string, string>): string[] {',
				'    return [];',
				'  },',
				'};',
				'',
				'export class Tm1Timeouts {',
				'  connect(): number {',
				'    return 1;',
				'  }',
				'}',
				'',
				'/** The builder, nested as in Java. */',
				'export namespace Tm1Timeouts {',
				'  export type Kind = "A" | "B";',
				'',
				'  /** Java\'s builder. */',
				'  export class Builder {',
				'    build(): Tm1Timeouts {',
				'      return new Tm1Timeouts();',
				'    }',
				'  }',
				'',
				'  export class Child extends Builder {}',
				'}',
				'',
				'/** Literals. */',
				'export namespace ODataLiterals {',
				'  /** Quotes a string. */',
				'  export function string(value: string): string {',
				'    return value;',
				'  }',
				'}',
			].join('\n'),
		},
		(dir, out) => execFileSync(process.execPath, [path.join(siteDir, 'extract', 'typescript_api.mjs'), dir, out, 'pkg']),
	);
	assert.deepEqual(Object.keys(types).sort(), ['ODataLiterals', 'Tm1ErrorKind', 'Tm1Timeouts', 'Tm1Timeouts.Builder', 'Tm1Timeouts.Child', 'Tm1Timeouts.Kind', 'Tuples']);
	assert.deepEqual(names(types.Tm1ErrorKind), ['NotFound', 'Other', 'of']);
	assert.equal(types.Tm1ErrorKind.kind, 'enum');
	assert.equal(types.Tm1ErrorKind.doc, 'What went wrong.');
	assert.equal(types.Tm1ErrorKind.members[0].doc, "The object doesn't exist.");
	assert.equal(types.Tm1ErrorKind.members[2].doc, 'The kind for a status.');
	assert.deepEqual(names(types.Tuples), ['NONE', 'of']);
	assert.deepEqual(types.Tuples.members.map((m) => m.kind + (m.static ? ' static' : '')), ['constant static', 'method static']);
	assert.deepEqual(names(types.Tm1Timeouts), ['connect']);
	assert.deepEqual(names(types['Tm1Timeouts.Kind']), ['A', 'B']);
	assert.deepEqual(names(types['Tm1Timeouts.Builder']), ['build']);
	assert.equal(types['Tm1Timeouts.Builder'].doc, "Java's builder.");
	assert.deepEqual(names(types['Tm1Timeouts.Child']), ['build<Tm1Timeouts.Builder']);
	assert.deepEqual(names(types.ODataLiterals), ['string']);
	assert.equal(types.ODataLiterals.doc, 'Literals.');
	assert.equal(types.ODataLiterals.members[0].doc, 'Quotes a string.');
	assert.deepEqual(types.ODataLiterals.members[0].params, [{ name: 'value', type: 'string' }]);
});

/** A Python of 3.11 or later, as scripts/extract.mjs finds one, or null. */
function python() {
	for (const candidate of [process.env.PYTHON, 'python3', 'python3.14', 'python3.13', 'python3.12', 'python3.11'].filter(Boolean)) {
		try {
			if (execFileSync(candidate, ['-c', 'import sys; print(sys.version_info >= (3, 11))'], { encoding: 'utf8' }).trim() === 'True') return candidate;
		} catch {
			// not there: try the next
		}
	}
	return null;
}

test('the Python extractor follows base classes, and reads what __init__ sets', { skip: python() ? false : 'no Python 3.11 or later' }, () => {
	const types = extract(
		{
			'__init__.py': '',
			'errors.py': [
				'class Tm1Error(Exception):',
				'    def __init__(self, message: str, method: str | None = None):',
				'        super().__init__(message)',
				'        self.method = method',
				'        self._hidden = 1',
				'',
				'    def is_retryable(self) -> bool:',
				'        return False',
				'',
				'',
				'class Tm1ApiError(Tm1Error):',
				'    path_value = staticmethod(len)',
				'',
				'    def __init__(self, status: int):',
				'        super().__init__("x")',
				'        self.status: int = status',
			].join('\n'),
		},
		(dir, out) => execFileSync(python(), ['-I', path.join(siteDir, 'extract', 'python_api.py'), dir, out]),
	);
	assert.deepEqual(names(types.Tm1Error), ['__init__', 'is_retryable', 'method']);
	assert.deepEqual(names(types.Tm1ApiError), ['path_value', '__init__', 'status', 'is_retryable<Tm1Error', 'method<Tm1Error']);
	assert.equal(types.Tm1ApiError.members[0].static, true);
	assert.equal(types.Tm1ApiError.members[2].returns, 'int');
});

test('the Python extractor reads nested classes, and the private classes a class names as its nested types', { skip: python() ? false : 'no Python 3.11 or later' }, () => {
	const types = extract(
		{
			'__init__.py': '',
			'sources.py': [
				'from typing import ClassVar',
				'',
				'',
				'class Tm1Source:',
				'    PRIVATE_IMAGE: ClassVar[str] = "TM1_PRIVATE_IMAGE"',
				'    PrivateImage: ClassVar[type[_Tm1PrivateImage]]',
				'',
				'    def describe(self) -> str:',
				'        raise NotImplementedError',
				'',
				'    class Kind:',
				'        """A kind."""',
				'',
				'        class Deeper:',
				'            pass',
				'',
				'    class _Hidden:',
				'        pass',
				'',
				'',
				'class _Tm1PrivateImage(Tm1Source):',
				'    """An image with TM1 already in it."""',
				'',
				'    image: str',
				'',
				'',
				'Tm1Source.PrivateImage = _Tm1PrivateImage',
			].join('\n'),
		},
		(dir, out) => execFileSync(python(), ['-I', path.join(siteDir, 'extract', 'python_api.py'), dir, out]),
	);
	assert.deepEqual(Object.keys(types).sort(), ['Tm1Source', 'Tm1Source.Kind', 'Tm1Source.Kind.Deeper', 'Tm1Source.PrivateImage']);
	assert.deepEqual(names(types.Tm1Source), ['PRIVATE_IMAGE', 'describe']);
	assert.equal(types['Tm1Source.Kind'].doc, 'A kind.');
	assert.equal(types['Tm1Source.PrivateImage'].doc, 'An image with TM1 already in it.');
	assert.deepEqual(names(types['Tm1Source.PrivateImage']), ['image', 'PRIVATE_IMAGE<Tm1Source', 'describe<Tm1Source']);
});

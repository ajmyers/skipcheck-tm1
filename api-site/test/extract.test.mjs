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

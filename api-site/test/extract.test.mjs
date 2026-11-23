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

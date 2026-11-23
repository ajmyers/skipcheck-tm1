// SPDX-License-Identifier: Apache-2.0

// Gathers each project's API, per language, into .generated/api/<project>.<language>.json, for
// reference.mjs: Java's from the docsModel task (./gradlew docsModel, which runs the doclet in
// java/tools/docs-doclet), Python's by extract/python_api.py, .NET's and TypeScript's by
// extract/dotnet_api.mjs and extract/typescript_api.mjs.
//
// Python is found as the build finds it: $PYTHON, or the first of python3, python3.14 ... python3.10
// that is 3.10 or later (macOS's python3 is 3.9).
//
//   node scripts/extract.mjs

import { execFileSync } from 'node:child_process';
import { copyFile, mkdir, readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { generatedDir, PROJECTS, repoDir, siteDir } from './site.mjs';

const out = path.join(generatedDir, 'api');
await mkdir(out, { recursive: true });

function python() {
	const candidates = [process.env.PYTHON, 'python3', ...[14, 13, 12, 11, 10].map((minor) => 'python3.' + minor), '/opt/homebrew/bin/python3.14'].filter(Boolean);
	for (const candidate of candidates) {
		try {
			const version = execFileSync(candidate, ['-c', 'import sys; print(sys.version_info >= (3, 10))'], { encoding: 'utf8' }).trim();
			if (version === 'True') return candidate;
		} catch {
			// not there: try the next
		}
	}
	throw new Error('No Python 3.10 or later found: set PYTHON');
}

let interpreter = null;
for (const project of PROJECTS) {
	const model = path.join(repoDir, 'java', project.id, 'build', 'docs', 'model.json');
	if (!(await stat(model).catch(() => null))) throw new Error('No ' + path.relative(repoDir, model) + ': run ./gradlew docsModel first');
	await copyFile(model, path.join(out, project.id + '.java.json'));
	if (project.dotnet) {
		execFileSync(process.execPath, [path.join(siteDir, 'extract', 'dotnet_api.mjs'), path.join(repoDir, 'dotnet', 'src', project.dotnet), path.join(out, project.id + '.dotnet.json')], { stdio: 'inherit' });
	}
	if (project.languages.includes('typescript')) {
		const pkg = JSON.parse(await readFile(path.join(repoDir, 'typescript', project.id, 'package.json'), 'utf8')).name;
		execFileSync(process.execPath, [path.join(siteDir, 'extract', 'typescript_api.mjs'), path.join(repoDir, 'typescript', project.id, 'src'), path.join(out, project.id + '.typescript.json'), pkg], { stdio: 'inherit' });
	}
	if (project.python) {
		interpreter ??= python();
		execFileSync(interpreter, ['-I', path.join(siteDir, 'extract', 'python_api.py'), path.join(repoDir, project.python), path.join(out, project.id + '.python.json')], { stdio: 'inherit' });
	}
}
console.log('APIs: ' + PROJECTS.map((p) => p.id + ' (' + p.languages.join(', ') + ')').join('; '));

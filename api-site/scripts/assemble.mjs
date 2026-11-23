// Collects each library's themed Javadoc (./gradlew apiSiteJavadoc) into
// dist/projects/<module>/api/, and works out the Content-Security-Policy hashes for the inline
// scripts, event handlers and style attributes Javadoc writes into its pages, so the Worker's CSP
// can allow exactly those and nothing else inline.
//
// The modules are the ones wrangler.jsonc has routes for. Run from anywhere: node scripts/assemble.mjs

import { createHash } from 'node:crypto';
import { cp, mkdir, readdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';

const siteDir = path.resolve(import.meta.dirname, '..');
const repoDir = path.resolve(siteDir, '..');
const dist = path.join(siteDir, 'dist');

const wrangler = await readFile(path.join(siteDir, 'wrangler.jsonc'), 'utf8');
const modules = [...wrangler.matchAll(/"pattern":\s*"[^"/]+\/projects\/([^/"]+)\/api\*"/g)].map((m) => m[1]);
if (modules.length === 0) throw new Error('No /projects/<module>/api* routes in wrangler.jsonc');

await rm(dist, { recursive: true, force: true });
for (const module of modules) {
	const built = path.join(repoDir, 'java', module, 'build', 'docs', 'api-site');
	if (!(await exists(path.join(built, 'index.html')))) {
		throw new Error(`No Javadoc for ${module} at ${built}: run ./gradlew apiSiteJavadoc first`);
	}
	await cp(built, path.join(dist, 'projects', module, 'api'), { recursive: true });
}

// The browser hashes the attribute's value after decoding entities, and a script's text as written
const scripts = new Set();
const styles = new Set();
for await (const file of htmlFiles(dist)) {
	const html = await readFile(file, 'utf8');
	for (const [, body] of html.matchAll(/<script(?![^>]*\ssrc=)[^>]*>([\s\S]*?)<\/script>/g)) scripts.add(body);
	for (const [, value] of html.matchAll(/\son[a-z]+="([^"]*)"/g)) scripts.add(decode(value));
	for (const [, value] of html.matchAll(/\sstyle="([^"]*)"/g)) styles.add(decode(value));
}
const hash = (text) => `sha256-${createHash('sha256').update(text, 'utf8').digest('base64')}`;
const csp = { scripts: [...scripts].map(hash).sort(), styles: [...styles].map(hash).sort() };
await mkdir(path.join(siteDir, '.generated'), { recursive: true });
await writeFile(path.join(siteDir, '.generated', 'csp.json'), JSON.stringify(csp, null, 2) + '\n');

let files = 0;
for await (const _ of walk(dist)) files++;
console.log(`Assembled ${modules.join(', ')}: ${files} files, ${csp.scripts.length} script and ${csp.styles.length} style hashes`);

async function exists(file) {
	return stat(file).then(() => true, () => false);
}

async function* walk(dir) {
	for (const entry of await readdir(dir, { withFileTypes: true })) {
		const full = path.join(dir, entry.name);
		if (entry.isDirectory()) yield* walk(full);
		else yield full;
	}
}

async function* htmlFiles(dir) {
	for await (const file of walk(dir)) if (file.endsWith('.html')) yield file;
}

function decode(value) {
	return value
		.replaceAll('&quot;', '"')
		.replaceAll('&#39;', "'")
		.replaceAll('&lt;', '<')
		.replaceAll('&gt;', '>')
		.replaceAll('&amp;', '&');
}

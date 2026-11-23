// SPDX-License-Identifier: Apache-2.0

// How to install a project in each language, read from that language's own build files, so the
// pages can't name a package, a version or a requirement the build doesn't.
//
//   java        gradle.properties (group, version), build-logic (the Java release it targets)
//   python      python/<project>/pyproject.toml (name, requires-python)
//   dotnet      dotnet/src/<Project>/<Project>.csproj (PackageId), dotnet/Directory.Build.props (TargetFramework)
//   typescript  typescript/<project>/package.json (name, engines.node)
//
// The version shown is the release being documented: DOCS_VERSION (the release workflow sets it
// from the tag), or else the version in gradle.properties. Each ecosystem writes a pre-release its
// own way (0.1.0-SNAPSHOT, 0.1.0.dev0, 0.1.0-dev), so before a release the commands ask for the
// latest pre-release instead of naming a version that may not be published: pip --pre, dotnet
// --prerelease, and npm's dev tag, which the release workflow publishes pre-releases under.

import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { codeBlock, repoDir } from './site.mjs';

const read = (file) => readFile(path.join(repoDir, file), 'utf8');

function need(match, what, file) {
	if (!match) throw new Error('No ' + what + ' in ' + file);
	return match[1];
}

/** The version the docs are for, and whether it is a release (1.2.3) rather than a pre-release. */
export async function docsVersion(env = process.env) {
	const version = env.DOCS_VERSION || need(/^version=(.+)$/m.exec(await read('gradle.properties')), 'version', 'gradle.properties').trim();
	return { version, release: /^\d+\.\d+\.\d+$/.test(version) };
}

/** What installing a project takes in one language: { name, needs }. */
export async function packageOf(project, language) {
	if (language === 'java') {
		const group = need(/^group=(.+)$/m.exec(await read('gradle.properties')), 'group', 'gradle.properties').trim();
		const file = 'build-logic/src/main/kotlin/tm1.java-library.gradle.kts';
		const release = need(/options\.release\s*=\s*(\d+)/.exec(await read(file)), 'options.release', file);
		return { name: group + ':' + project.id, needs: 'Java ' + release + ' or later' };
	}
	if (language === 'python') {
		const file = 'python/' + project.id + '/pyproject.toml';
		const toml = await read(file);
		const python = need(/^requires-python\s*=\s*">=\s*([\d.]+)"/m.exec(toml), 'requires-python', file);
		return { name: need(/^name\s*=\s*"([^"]+)"/m.exec(toml), 'name', file), needs: 'Python ' + python + ' or later' };
	}
	if (language === 'dotnet') {
		const file = 'dotnet/src/' + project.dotnet + '/' + project.dotnet + '.csproj';
		const props = 'dotnet/Directory.Build.props';
		const framework = need(/<TargetFramework>net(\d+)\.\d+<\/TargetFramework>/.exec(await read(props)), 'TargetFramework', props);
		return { name: need(/<PackageId>([^<]+)<\/PackageId>/.exec(await read(file)), 'PackageId', file), needs: '.NET ' + framework };
	}
	if (language === 'typescript') {
		const file = 'typescript/' + project.id + '/package.json';
		const json = JSON.parse(await read(file));
		const node = need(/^>=\s*([\d.]+)$/.exec(json.engines?.node ?? ''), 'engines.node', file);
		return { name: json.name, needs: 'Node ' + node + ' or later' };
	}
	throw new Error('No such language: ' + language);
}

/** The commands that install a package, as [{ caption, code, highlight }]. */
export function installCommands(language, name, { version, release }, test) {
	if (language === 'java') {
		const [group, artifact] = name.split(':');
		const scope = test ? 'testImplementation' : 'implementation';
		const maven =
			'<dependency>\n  <groupId>' + group + '</groupId>\n  <artifactId>' + artifact + '</artifactId>\n  <version>' + version + '</version>\n' + (test ? '  <scope>test</scope>\n' : '') + '</dependency>';
		return [
			{ caption: 'Gradle (Kotlin)', code: scope + '("' + name + ':' + version + '")', highlight: 'kotlin' },
			{ caption: 'Maven', code: maven, highlight: null },
		];
	}
	if (language === 'python') {
		return [{ caption: 'Terminal', code: release ? 'pip install ' + name + '==' + version : 'pip install --pre ' + name, highlight: 'sh' }];
	}
	if (language === 'dotnet') {
		return [{ caption: 'Terminal', code: 'dotnet add package ' + name + (release ? ' --version ' + version : ' --prerelease'), highlight: 'sh' }];
	}
	if (language === 'typescript') {
		const flag = test ? 'npm install --save-dev ' : 'npm install ';
		// Pre-releases go out under npm's dev tag (release.yml); latest is for releases only
		return [{ caption: 'Terminal', code: flag + name + (release ? '@' + version : '@dev'), highlight: 'sh' }];
	}
	throw new Error('No such language: ' + language);
}

/** A tab panel's content: what the package needs, then the commands. */
export function installHtml(language, pkg, version, test) {
	const blocks = installCommands(language, pkg.name, version, test)
		.map((block) => '<p class="caption">' + block.caption + '</p>' + codeBlock(block.code, block.highlight))
		.join('');
	const snapshot =
		language === 'java' && !version.release
			? '<p class="needs">Not on Maven Central yet: build it from source with <code>./gradlew publishToMavenLocal</code>, and add <code>mavenLocal()</code> to your repositories.</p>'
			: '';
	return '<p class="needs">Needs ' + pkg.needs + '.</p>' + blocks + snapshot;
}

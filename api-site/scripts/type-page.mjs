// SPDX-License-Identifier: Apache-2.0

// The top of a type's page in the reference (reference.mjs): its name, the line that imports it, and
// the walkthroughs that use it. The walkthroughs come before the overview, so a reader sees them on
// arriving, however long the overview and its examples are. Its own module so the tests can run it.

import { escape } from './site.mjs';

/**
 * The heading, import line and "Used in" note of a type's page. highlight is the language's class
 * for the site's highlighter; uses is [{ project, walkthrough }], the walkthroughs whose examples use
 * the type, linked to their own pages (whose tabs open in the language last chosen).
 */
export function typeHeader({ name, importLine, highlight, uses = [] }) {
	const used =
		uses.length > 0
			? '<p class="note">Used in ' + (uses.length > 1 ? 'the walkthroughs ' : 'the walkthrough ') +
				uses.map(({ project, walkthrough }) => '<a href="/' + project.id + '/walkthroughs/' + walkthrough.slug + '/">' + escape(walkthrough.meta.title) + '</a>').join(', ') + '.</p>'
			: '';
	return '<h1>' + escape(name) + '</h1><pre class="import"><code class="lang-' + highlight + '">' + escape(importLine) + '</code></pre>' + used;
}

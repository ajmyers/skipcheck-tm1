// SPDX-License-Identifier: Apache-2.0

// The docs pages' language: each page is in one language, and its switch goes to the same page in
// another. Choosing one is remembered (the same choice as the landing pages' tabs, tabs.js), and
// the chooser at /<project>/docs/ opens the language last chosen.
const KEY = 'tm1-guide-language';

function stored() {
	try {
		return localStorage.getItem(KEY);
	} catch {
		return null;
	}
}

function remember(language) {
	try {
		localStorage.setItem(KEY, language);
	} catch {
		// storage is off: the choice lasts for the page
	}
}

document.addEventListener('click', (event) => {
	const link = event.target instanceof Element ? event.target.closest('a[data-lang]') : null;
	if (link) remember(link.dataset.lang);
});

// Reading a page in a language counts as choosing it
if (document.body.dataset.lang) remember(document.body.dataset.lang);

if (document.body.hasAttribute('data-choose')) {
	const language = stored() ?? 'java';
	const link = document.querySelector('a[data-lang="' + language + '"]') ?? document.querySelector('a[data-lang]');
	if (link) location.replace(link.href);
}

// The sidebar opens scrolled to the page's own entry, which it marks
const here = location.pathname.split('/').pop();
const entry = here && document.querySelector('.sidebar a[href="' + here + '"]');
if (entry) {
	entry.setAttribute('aria-current', 'page');
	const sidebar = entry.closest('.sidebar');
	sidebar.scrollTop = entry.offsetTop - sidebar.clientHeight / 3;
}

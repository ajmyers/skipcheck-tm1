// SPDX-License-Identifier: Apache-2.0

// The language tabs of the docs and landing pages: choosing a language on one example chooses it on every example, on
// this page and the next, and the choice is remembered. An example a language doesn't have keeps
// showing the first language it does have.
const KEY = 'tm1-guide-language';

function show(language) {
	for (const group of document.querySelectorAll('.tabs')) {
		const tabs = [...group.querySelectorAll('[role="tab"]')];
		const chosen = tabs.find((tab) => tab.dataset.lang === language) ?? tabs[0];
		if (!chosen) continue;
		for (const tab of tabs) {
			const selected = tab === chosen;
			tab.setAttribute('aria-selected', String(selected));
			tab.tabIndex = selected ? 0 : -1;
		}
		for (const panel of group.querySelectorAll('[role="tabpanel"]')) {
			panel.hidden = panel.dataset.lang !== chosen.dataset.lang;
		}
	}
}

function stored() {
	try {
		return localStorage.getItem(KEY);
	} catch {
		return null; // storage is off: the choice lasts for the page
	}
}

document.addEventListener('click', (event) => {
	const tab = event.target instanceof Element ? event.target.closest('.tabs [role="tab"]') : null;
	if (!tab) return;
	// Keep the clicked example where it is on screen while the others change height
	const before = tab.getBoundingClientRect().top;
	try {
		localStorage.setItem(KEY, tab.dataset.lang);
	} catch {
		// storage is off
	}
	show(tab.dataset.lang);
	window.scrollBy(0, tab.getBoundingClientRect().top - before);
});

document.addEventListener('keydown', (event) => {
	if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
	const tab = event.target instanceof Element ? event.target.closest('.tabs [role="tab"]') : null;
	if (!tab) return;
	const tabs = [...tab.parentElement.querySelectorAll('[role="tab"]')];
	const next = tabs[(tabs.indexOf(tab) + (event.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
	next.focus();
	next.click();
});

show(stored() ?? 'java');

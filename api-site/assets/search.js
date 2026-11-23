// SPDX-License-Identifier: Apache-2.0

// The docs pages' search box: a combobox (the ARIA pattern, with a list of options) over the
// page's language's index, search.json beside the page, loaded when the reader first uses the
// box. "/" focuses it; the arrow keys move through the results, Enter opens one, Escape closes
// the list and then clears the box. The box is in the page, hidden, so without this script there
// is no box that does nothing. Matching and ranking are in search-rank.js.
import { prepare, search } from './search-rank.js';

const box = document.querySelector('.search');
const input = box?.querySelector('input');
const list = box?.querySelector('[role="listbox"]');
const status = box?.querySelector('[role="status"]');
const none = box?.querySelector('.none');
if (input && list) start();

function start() {
	box.hidden = false;
	let index = null;
	let loading = null;
	let results = [];
	let active = -1;

	const load = () => {
		loading ??= fetch(input.dataset.index)
			.then((response) => (response.ok ? response.json() : Promise.reject(new Error(response.status))))
			.then((json) => {
				index = prepare(json);
				if (document.activeElement === input) update();
			})
			.catch(() => {
				loading = null;
				none.textContent = "Search isn't available: its index didn't load.";
				none.hidden = false;
			});
		return loading;
	};

	const option = (entry, i) => {
		const item = document.createElement('li');
		item.id = 'docs-search-' + i;
		item.setAttribute('role', 'option');
		item.setAttribute('aria-selected', 'false');
		const link = document.createElement('a');
		link.href = entry.href;
		link.tabIndex = -1;
		// A walkthrough's title is words, not a name in the code
		const name = document.createElement(entry.kind === 'walkthrough' ? 'span' : 'code');
		if (entry.kind === 'walkthrough') name.className = 'title';
		name.textContent = entry.name;
		const where = document.createElement('span');
		where.className = 'where';
		where.textContent = { type: 'type', model: 'TM1 type', walkthrough: 'walkthrough' }[entry.kind] ?? entry.type.name;
		link.append(name, where);
		if (entry.summary) {
			const summary = document.createElement('span');
			summary.className = 'summary';
			summary.textContent = entry.summary;
			link.append(summary);
		}
		item.append(link);
		return item;
	};

	const show = (open) => {
		list.hidden = !open;
		input.setAttribute('aria-expanded', String(open));
		if (!open) input.removeAttribute('aria-activedescendant');
	};

	const select = (i) => {
		const items = list.children;
		if (active >= 0 && items[active]) items[active].setAttribute('aria-selected', 'false');
		active = i;
		if (active < 0 || !items[active]) {
			input.removeAttribute('aria-activedescendant');
			return;
		}
		items[active].setAttribute('aria-selected', 'true');
		input.setAttribute('aria-activedescendant', items[active].id);
		items[active].scrollIntoView({ block: 'nearest' });
	};

	function update() {
		const query = input.value;
		if (!query.trim()) {
			results = [];
			list.replaceChildren();
			status.textContent = '';
			none.hidden = true;
			show(false);
			return;
		}
		if (!index) {
			load();
			return;
		}
		results = search(index, query);
		list.replaceChildren(...results.map(option));
		active = -1;
		if (results.length > 0) select(0);
		status.textContent = results.length === 0 ? 'No results' : results.length + (results.length === 1 ? ' result' : ' results');
		none.textContent = 'Nothing here is called that.';
		none.hidden = results.length > 0;
		show(results.length > 0);
	}

	const go = (i) => {
		const entry = results[i];
		if (!entry) return;
		show(false);
		input.blur();
		location.href = new URL(entry.href, location.href).href;
	};

	input.addEventListener('focus', () => {
		load();
		if (input.value.trim()) update();
	});
	input.addEventListener('input', update);
	input.addEventListener('keydown', (event) => {
		const open = !list.hidden && results.length > 0;
		if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
			event.preventDefault();
			if (!open) return update();
			const step = event.key === 'ArrowDown' ? 1 : -1;
			select((active + step + results.length) % results.length);
		} else if (event.key === 'Enter') {
			if (open) {
				event.preventDefault();
				go(Math.max(active, 0));
			}
		} else if (event.key === 'Escape') {
			event.preventDefault();
			if (!list.hidden) show(false);
			else if (input.value) {
				input.value = '';
				update();
			} else input.blur();
		}
	});
	// A click on a result opens it; pressing on the list doesn't take the focus from the box first
	list.addEventListener('mousedown', (event) => event.preventDefault());
	list.addEventListener('click', (event) => {
		const item = event.target instanceof Element ? event.target.closest('[role="option"]') : null;
		if (!item || event.ctrlKey || event.metaKey || event.shiftKey || event.button !== 0) return;
		event.preventDefault();
		go([...list.children].indexOf(item));
	});
	input.addEventListener('blur', () => {
		show(false);
		none.hidden = true;
	});

	// "/" anywhere but in a field focuses the box, as on most docs sites
	document.addEventListener('keydown', (event) => {
		if (event.key !== '/' || event.ctrlKey || event.metaKey || event.altKey) return;
		const target = event.target;
		if (target instanceof HTMLElement && (target.isContentEditable || /^(?:INPUT|TEXTAREA|SELECT)$/.test(target.tagName))) return;
		event.preventDefault();
		input.focus();
		input.select();
	});
}

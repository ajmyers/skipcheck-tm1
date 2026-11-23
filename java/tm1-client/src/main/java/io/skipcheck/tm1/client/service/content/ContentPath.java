// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.skipcheck.tm1.client.model.Entry;
import io.skipcheck.tm1.client.odata.ODataPath;

/**
 * Where an entry is in the contents tree: a root folder, then each folder's child by ID.
 *
 * {@snippet class = examples.ContentServiceExamples region = path}
 *
 * <p>
 * Children are found by their {@code ID}, not their {@code Name}, though
 * {@code $metadata} keys entries by {@code Name}: TM1 (11.8) gives most entries an ID of
 * the name plus a suffix for its kind, such as {@code notes.txt.blob} for a document,
 * {@code ibm.extr} for a link and <code>&#125;ClientGroups.cube</code> for a cube
 * reference. A folder's ID is its name. The ID is in the entry TM1 returns when it's
 * made. {@code $metadata} deprecates {@code Entry.ID} in schema 11.5.0, but TM1 11.8
 * still keys by it. Paths are immutable.
 */
public final class ContentPath {

	private final List<Step> steps;

	private ContentPath(List<Step> steps) {
		this.steps = steps;
	}

	/**
	 * A root folder: {@code Contents('id')}.
	 * @param id the folder's ID, such as {@code Applications}
	 * @return the path
	 */
	public static ContentPath root(String id) {
		return new ContentPath(List.of(new Step(Objects.requireNonNull(id, "id"), ContentScope.PUBLIC)));
	}

	/**
	 * The {@code Applications} folder, where applications are built.
	 * @return the path
	 */
	public static ContentPath applications() {
		return root("Applications");
	}

	/**
	 * The {@code Blobs} folder, holding the documents behind the document references in
	 * applications.
	 * @return the path
	 */
	public static ContentPath blobs() {
		return root("Blobs");
	}

	/**
	 * A public child of this folder: {@code …/Contents('id')}.
	 * @param id the child's ID
	 * @return the child's path
	 */
	public ContentPath child(String id) {
		return child(id, ContentScope.PUBLIC);
	}

	/**
	 * A public child of this folder, as TM1 returned it, such as from
	 * {@code ContentService.create}: found by its {@code ID}, which {@code $metadata}
	 * deprecates but TM1 (11.8) still keys entries by, so callers needn't read it.
	 * @param entry the entry
	 * @return the child's path
	 * @throws IllegalArgumentException if the entry has no ID (it was selected away)
	 */
	public ContentPath child(Entry entry) {
		return child(entry, ContentScope.PUBLIC);
	}

	/**
	 * A child of this folder as TM1 returned it, public or private: see
	 * {@link #child(Entry)}.
	 * @param entry the entry
	 * @param scope which of the folder's collections holds it
	 * @return the child's path
	 * @throws IllegalArgumentException if the entry has no ID
	 */
	@SuppressWarnings("deprecation") // ID is deprecated in $metadata, yet it's TM1's key
	public ContentPath child(Entry entry, ContentScope scope) {
		String id = entry.id();
		if (id == null) {
			throw new IllegalArgumentException("The entry has no ID to find it by: " + entry);
		}
		return child(id, scope);
	}

	/**
	 * A child of this folder, public or private.
	 * @param id the child's ID
	 * @param scope which of the folder's collections holds it
	 * @return the child's path
	 */
	public ContentPath child(String id, ContentScope scope) {
		List<Step> next = new ArrayList<>(steps);
		next.add(new Step(Objects.requireNonNull(id, "id"), Objects.requireNonNull(scope, "scope")));
		return new ContentPath(List.copyOf(next));
	}

	/**
	 * Whether this is in the {@code Blobs} folder, where documents themselves are.
	 * @return whether the root is {@code Blobs}
	 */
	public boolean inBlobs() {
		return steps.get(0).id().equals("Blobs");
	}

	/**
	 * The OData path to the entry.
	 * @return the path, such as {@code Contents('Applications')/Contents('Planning')}
	 */
	public ODataPath path() {
		ODataPath path = ODataPath.of("Contents").key(steps.get(0).id());
		for (Step step : steps.subList(1, steps.size())) {
			path = path.nav(step.scope().navigation()).key(step.id());
		}
		return path;
	}

	/** The IDs from the root, such as {@code Applications/Planning/notes.txt.blob}. */
	@Override
	public String toString() {
		StringBuilder text = new StringBuilder();
		for (Step step : steps) {
			if (!text.isEmpty()) {
				text.append('/');
			}
			text.append(step.scope() == ContentScope.PRIVATE ? "(private) " : "").append(step.id());
		}
		return text.toString();
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof ContentPath other && steps.equals(other.steps);
	}

	@Override
	public int hashCode() {
		return steps.hashCode();
	}

	private record Step(String id, ContentScope scope) {
	}

}

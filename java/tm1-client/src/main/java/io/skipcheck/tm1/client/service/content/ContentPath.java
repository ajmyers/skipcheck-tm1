// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.skipcheck.tm1.client.model.Entry;
import io.skipcheck.tm1.client.odata.ODataPath;

/**
 * Where an entry is in the contents tree: a root folder, then each folder's child by key.
 *
 * {@snippet class = examples.ContentServiceExamples region = path}
 *
 * <p>
 * On TM1 11.8, children are found by their {@code ID}, not their {@code Name}, though
 * {@code $metadata} keys entries by {@code Name}: TM1 (11.8) gives most entries an ID of
 * the name plus a suffix for its kind, such as {@code notes.txt.blob} for a document,
 * {@code ibm.extr} for a link and <code>&#125;ClientGroups.cube</code> for a cube
 * reference. A folder's ID is its name. {@code $metadata} deprecates {@code Entry.ID} in
 * schema 11.5.0, and TM1 12 omits {@code ID} altogether and keys entries by {@code Name}.
 * {@link #child(Entry)} uses {@code ID} when present and falls back to {@code Name}.
 * Paths are immutable.
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
	 * The {@code Files} folder, holding direct documents on TM1 12.
	 * @return the path
	 */
	public static ContentPath files() {
		return root("Files");
	}

	/**
	 * The {@code Home} folder, holding the current user's direct documents on TM1 12.
	 * @return the path
	 */
	public static ContentPath home() {
		return root("Home");
	}

	/**
	 * The {@code Session} folder, holding session-scoped direct documents on TM1 12.
	 * @return the path
	 */
	public static ContentPath session() {
		return root("Session");
	}

	/**
	 * A public child of this folder: {@code …/Contents('id')}.
	 * @param id the child's key ({@code ID} on TM1 11, {@code Name} on TM1 12)
	 * @return the child's path
	 */
	public ContentPath child(String id) {
		return child(id, ContentScope.PUBLIC);
	}

	/**
	 * A public child of this folder, as TM1 returned it, such as from
	 * {@code ContentService.create}: found by its {@code ID} when present (TM1 11),
	 * falling back to its {@code Name} when {@code ID} is absent (TM1 12).
	 * @param entry the entry
	 * @return the child's path
	 * @throws IllegalArgumentException if the entry has neither an ID nor a Name
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
	 * @throws IllegalArgumentException if the entry has neither an ID nor a Name
	 */
	@SuppressWarnings("deprecation") // ID is deprecated in $metadata, yet TM1 11 keys by
										// it
	public ContentPath child(Entry entry, ContentScope scope) {
		Objects.requireNonNull(entry, "entry");
		String id = entry.id();
		String key = id != null && !id.isEmpty() ? id : entry.name();
		if (key == null || key.isEmpty()) {
			throw new IllegalArgumentException("The entry has neither an ID nor a Name to find it by: " + entry);
		}
		return child(key, scope);
	}

	/**
	 * A child of this folder, public or private.
	 * @param id the child's key ({@code ID} on TM1 11, {@code Name} on TM1 12)
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
	 * Whether this path is under a root that holds {@code Document} entities directly
	 * ({@code Blobs}, {@code Files}, {@code Home}, {@code Session}, or any root other
	 * than {@code Applications}, where documents are {@code DocumentReference}s).
	 */
	boolean isDirectDocumentRoot() {
		return !steps.get(0).id().equalsIgnoreCase("Applications");
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

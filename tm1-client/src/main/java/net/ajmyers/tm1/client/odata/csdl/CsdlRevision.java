package net.ajmyers.tm1.client.odata.csdl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * One record of a {@code Core.Revisions} annotation: the schema version in which a model
 * element was added, modified or deprecated.
 *
 * <p>
 * TM1 annotates most of its API this way, back to 10.2.2. The versions are schema
 * versions (as in {@code Core.SchemaVersion}, e.g. {@code 11.8.35}), not server build
 * numbers.
 *
 * @param version the schema version, or {@code null} if the record has none
 * @param kind the kind of revision
 * @param rawKind the kind as written, e.g. {@code Core.RevisionKind/Added}, for kinds
 * this client doesn't know
 * @param description the reason given, or {@code null}
 */
public record CsdlRevision(@Nullable String version, Kind kind, @Nullable String rawKind,
		@Nullable String description) {

	/** The term holding revisions. */
	public static final String TERM = "Core.Revisions";

	/**
	 * The kinds of revision in the Core vocabulary, plus {@link #UNKNOWN} for anything
	 * else.
	 */
	public enum Kind {

		/** {@code Core.RevisionKind/Added}. */
		ADDED,

		/** {@code Core.RevisionKind/Modified}. */
		MODIFIED,

		/** {@code Core.RevisionKind/Deprecated}. */
		DEPRECATED,

		/**
		 * Missing, or a kind this client doesn't know; see
		 * {@link CsdlRevision#rawKind()}.
		 */
		UNKNOWN

	}

	/**
	 * Reads the {@code Core.Revisions} records from an element's annotations, in document
	 * order.
	 * @param annotations the element's annotations
	 * @return the revisions, or an empty list if there's no {@code Core.Revisions}
	 * collection
	 */
	public static List<CsdlRevision> of(List<CsdlAnnotation> annotations) {
		Optional<CsdlAnnotation> revisions = CsdlAnnotation.find(annotations, TERM);
		if (revisions.isEmpty() || !(revisions.get().value() instanceof CsdlValue.Collection collection)) {
			return List.of();
		}
		List<CsdlRevision> result = new ArrayList<>();
		for (CsdlValue item : collection.items()) {
			if (item instanceof CsdlValue.Record record) {
				String version = text(record, "Version");
				String rawKind = text(record, "Kind");
				result.add(new CsdlRevision(version, kindOf(rawKind), rawKind, text(record, "Description")));
			}
		}
		return List.copyOf(result);
	}

	/**
	 * The version this element was first added in, if its revisions say.
	 * @param annotations the element's annotations
	 * @return the schema version of the first {@code Added} revision with a version, or
	 * empty
	 */
	public static Optional<String> addedIn(List<CsdlAnnotation> annotations) {
		return of(annotations).stream()
			.filter(r -> r.kind() == Kind.ADDED && r.version() != null)
			.map(CsdlRevision::version)
			.findFirst();
	}

	/**
	 * The version this element was deprecated in, if its revisions say.
	 * @param annotations the element's annotations
	 * @return the schema version of the first {@code Deprecated} revision with a version,
	 * or empty
	 */
	public static Optional<String> deprecatedIn(List<CsdlAnnotation> annotations) {
		return of(annotations).stream()
			.filter(r -> r.kind() == Kind.DEPRECATED && r.version() != null)
			.map(CsdlRevision::version)
			.findFirst();
	}

	private static @Nullable String text(CsdlValue.Record record, String property) {
		CsdlValue value = record.properties().get(property);
		return value == null ? null : value.asText().orElse(null);
	}

	private static Kind kindOf(@Nullable String raw) {
		if (raw == null) {
			return Kind.UNKNOWN;
		}
		String member = raw.substring(raw.lastIndexOf('/') + 1);
		return switch (member) {
			case "Added" -> Kind.ADDED;
			case "Modified" -> Kind.MODIFIED;
			case "Deprecated" -> Kind.DEPRECATED;
			default -> Kind.UNKNOWN;
		};
	}
}

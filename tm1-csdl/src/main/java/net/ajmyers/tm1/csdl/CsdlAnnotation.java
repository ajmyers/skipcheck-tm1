package net.ajmyers.tm1.csdl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * A CSDL annotation: a vocabulary term applied to a model element, such as
 * {@code Core.Description} or {@code Core.Revisions}.
 *
 * @param term the term as written, e.g. {@code Core.Revisions}
 * @param qualifier the qualifier, or {@code null}
 * @param value the value
 */
public record CsdlAnnotation(String term, @Nullable String qualifier, CsdlValue value) {

	/** Validates the components. */
	public CsdlAnnotation {
		Objects.requireNonNull(term, "term");
		Objects.requireNonNull(value, "value");
	}

	/**
	 * The first annotation with this term (ignoring qualifiers), if any.
	 * @param annotations the annotations to search
	 * @param term the term as written, e.g. {@code Core.Revisions}
	 * @return the first matching annotation, or empty if none has the term
	 */
	public static Optional<CsdlAnnotation> find(List<CsdlAnnotation> annotations, String term) {
		return annotations.stream().filter(a -> a.term().equals(term)).findFirst();
	}
}

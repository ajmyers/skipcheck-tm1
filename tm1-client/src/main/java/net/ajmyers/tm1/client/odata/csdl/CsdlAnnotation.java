package net.ajmyers.tm1.client.odata.csdl;

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

	public CsdlAnnotation {
		Objects.requireNonNull(term, "term");
		Objects.requireNonNull(value, "value");
	}

	/** The first annotation with this term (ignoring qualifiers), if any. */
	public static Optional<CsdlAnnotation> find(List<CsdlAnnotation> annotations, String term) {
		return annotations.stream().filter(a -> a.term().equals(term)).findFirst();
	}
}

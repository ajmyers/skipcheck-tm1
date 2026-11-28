// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.csdl;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * An {@code edmx:Reference} element in a CSDL document, importing namespaces from another
 * document (such as OData vocabularies or a referenced IBM schema like
 * {@code ibm.tm1s.api.v1.xml}).
 *
 * @param uri the {@code Uri} attribute, absolute or relative to the document
 * @param includes the {@code edmx:Include} elements, in document order
 */
public record CsdlReference(String uri, List<Include> includes) {

	/** Validates and copies the components. */
	public CsdlReference {
		Objects.requireNonNull(uri, "uri");
		includes = List.copyOf(includes);
	}

	/**
	 * An {@code edmx:Include} element naming a referenced namespace and its local alias.
	 *
	 * @param namespace the referenced schema's namespace, e.g. {@code ibm.tm1s.api.v1}
	 * @param alias the alias used in the referencing document, e.g. {@code tm1s}, or
	 * {@code null}
	 */
	public record Include(String namespace, @Nullable String alias) {

		/** Validates the components. */
		public Include {
			Objects.requireNonNull(namespace, "namespace");
		}

	}

}

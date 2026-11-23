// SPDX-License-Identifier: Apache-2.0

package examples;

import java.util.List;

import io.skipcheck.tm1.client.model.Element;
import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataLiterals;

/**
 * Examples for {@link Filter}, quoted in its Javadoc.
 */
final class FilterExamples {

	List<Filter> build() {
		// @start region = build
		Filter numericQ = Filter.and(Filter.startsWith(Element.NAME, "Q"),
				Element.TYPE.eq(ODataLiterals.enumMember("ibm.tm1.api.v1.ElementType", "Numeric")));
		// (startswith(Name,'Q') and (Type eq ibm.tm1.api.v1.ElementType'Numeric'))
		Filter hasRegion = Filter.any("Hierarchies", "h", (h) -> h.nav("Name").eq("Region"));
		// Hierarchies/any(h:h/Name eq 'Region')
		// @end
		return List.of(numericQ, hasRegion);
	}

}

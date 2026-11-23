// SPDX-License-Identifier: Apache-2.0

package examples;

import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataProperty;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * Examples for {@link ODataQuery}, quoted in its Javadoc.
 */
final class ODataQueryExamples {

	String build() {
		// @start region = build
		String query = ODataQuery.none()
			.select("Name", "Type")
			.expand("Hierarchies", h -> h.select("Name").top(5))
			.filter(Filter.startsWith(ODataProperty.of("Name"), "Q"))
			.toQueryString();
		// $select=Name,Type&$expand=Hierarchies($select=Name;$top=5)&$filter=startswith(Name,'Q'),
		// each value encoded
		// @end
		return query;
	}

	private ODataQueryExamples() {
	}

}

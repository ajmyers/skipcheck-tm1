// SPDX-License-Identifier: Apache-2.0

package examples;

import java.util.List;

import io.skipcheck.tm1.client.odata.ODataPath;

/**
 * Examples for {@link ODataPath}, quoted in its Javadoc.
 */
final class ODataPathExamples {

	List<ODataPath> build() {
		// @start region = build
		ODataPath element = ODataPath.of("Dimensions")
			.key("Region")
			.nav("Hierarchies")
			.key("Region")
			.nav("Elements")
			.key("O'Neil");
		// Dimensions('Region')/Hierarchies('Region')/Elements('O''Neil')
		ODataPath reserve = ODataPath.of("Cubes").key("Sales").action("tm1.Reserve");
		// Cubes('Sales')/tm1.Reserve
		// @end
		return List.of(element, reserve);
	}

}

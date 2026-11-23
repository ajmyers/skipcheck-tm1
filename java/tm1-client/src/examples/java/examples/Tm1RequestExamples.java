// SPDX-License-Identifier: Apache-2.0

package examples;

import java.util.List;
import java.util.Map;

import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import tools.jackson.databind.JsonNode;

/**
 * Examples for {@link Tm1Request}, quoted in its Javadoc.
 */
final class Tm1RequestExamples {

	List<Tm1Request<?>> build() {
		// @start region = build
		// A cube's name, read as plain JSON
		Tm1Request<JsonNode> cube = Tm1Request.get(ODataPath.of("Cubes").key("Sales"), ODataQuery.none().select("Name"),
				JsonNode.class);
		// An action with no answer
		Tm1Request<Void> reserve = Tm1Request.post(ODataPath.of("Cubes").key("Sales").action("tm1.Reserve"), Map.of(),
				Void.class);
		// @end
		return List.of(cube, reserve);
	}

}

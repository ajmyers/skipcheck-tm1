// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.data;

import java.util.Map;
import java.util.Objects;

import io.skipcheck.tm1.client.odata.ODataPath;

/**
 * Cell coordinates, as TM1 binds them: one element path per dimension of the cube, in the
 * cube's order.
 *
 * <pre class="code">
 * Map&lt;String, String&gt; cell = new LinkedHashMap&lt;&gt;();
 * cell.put("Region", "North");
 * cell.put("Measure", "Amount");
 * CubeUpdatePatch.builder().bindTuple(Tuples.of(cell)).value(42).build();
 * </pre>
 */
public final class Tuples {

	private Tuples() {
	}

	/**
	 * The element paths for a cell.
	 * @param tuple dimension name to element name, for every dimension of the cube in its
	 * order (a {@code LinkedHashMap}); each element is in its dimension's same-named
	 * hierarchy
	 * @return one path per element, such as
	 * {@code Dimensions('Region')/Hierarchies('Region')/Elements('North')}
	 */
	public static ODataPath[] of(Map<String, String> tuple) {
		return tuple.entrySet()
			.stream()
			.map(e -> element(e.getKey(), e.getKey(), e.getValue()))
			.toArray(ODataPath[]::new);
	}

	/**
	 * One element's path.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param element the element's name
	 * @return the path, {@code Dimensions('d')/Hierarchies('h')/Elements('e')}
	 */
	public static ODataPath element(String dimension, String hierarchy, String element) {
		return ODataPath.of("Dimensions")
			.key(Objects.requireNonNull(dimension, "dimension"))
			.nav("Hierarchies")
			.key(Objects.requireNonNull(hierarchy, "hierarchy"))
			.nav("Elements")
			.key(Objects.requireNonNull(element, "element"));
	}

}

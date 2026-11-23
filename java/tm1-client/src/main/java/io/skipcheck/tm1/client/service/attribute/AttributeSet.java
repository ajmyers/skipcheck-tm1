// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.attribute;

import java.util.Objects;

import io.skipcheck.tm1.client.odata.ODataPath;

/**
 * A set of attribute definitions: the attributes that one kind of object has, such as
 * every element of a hierarchy.
 */
public final class AttributeSet {

	private final ODataPath path;

	private AttributeSet(ODataPath path) {
		this.path = path;
	}

	/**
	 * Dimensions' attributes: {@code /DimensionAttributes}.
	 * @return the set
	 */
	public static AttributeSet dimensions() {
		return new AttributeSet(ODataPath.of("DimensionAttributes"));
	}

	/**
	 * The attributes of a dimension's hierarchies:
	 * {@code Dimensions('d')/HierarchyAttributes}.
	 * @param dimension the dimension's name
	 * @return the set
	 */
	public static AttributeSet hierarchies(String dimension) {
		return new AttributeSet(ODataPath.of("Dimensions")
			.key(Objects.requireNonNull(dimension, "dimension"))
			.nav("HierarchyAttributes"));
	}

	/**
	 * The attributes of a hierarchy's elements:
	 * {@code …/Hierarchies('h')/ElementAttributes}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @return the set
	 */
	public static AttributeSet elements(String dimension, String hierarchy) {
		return new AttributeSet(ODataPath.of("Dimensions")
			.key(Objects.requireNonNull(dimension, "dimension"))
			.nav("Hierarchies")
			.key(Objects.requireNonNull(hierarchy, "hierarchy"))
			.nav("ElementAttributes"));
	}

	/**
	 * The attributes of a hierarchy's subsets:
	 * {@code …/Hierarchies('h')/SubsetAttributes}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @return the set
	 */
	public static AttributeSet subsets(String dimension, String hierarchy) {
		return new AttributeSet(ODataPath.of("Dimensions")
			.key(Objects.requireNonNull(dimension, "dimension"))
			.nav("Hierarchies")
			.key(Objects.requireNonNull(hierarchy, "hierarchy"))
			.nav("SubsetAttributes"));
	}

	/**
	 * Cubes' attributes: {@code /CubeAttributes}.
	 * @return the set
	 */
	public static AttributeSet cubes() {
		return new AttributeSet(ODataPath.of("CubeAttributes"));
	}

	/**
	 * The attributes of a cube's views: {@code Cubes('c')/ViewAttributes}. TM1 (11.8)
	 * refuses to create one (400, "no public views") until the cube has a public
	 * <em>native</em> view; an MDX view doesn't count.
	 * @param cube the cube's name
	 * @return the set
	 */
	public static AttributeSet views(String cube) {
		return new AttributeSet(ODataPath.of("Cubes").key(Objects.requireNonNull(cube, "cube")).nav("ViewAttributes"));
	}

	/**
	 * Processes' attributes: {@code /ProcessAttributes}.
	 * @return the set
	 */
	public static AttributeSet processes() {
		return new AttributeSet(ODataPath.of("ProcessAttributes"));
	}

	/**
	 * Chores' attributes: {@code /ChoreAttributes}.
	 * @return the set
	 */
	public static AttributeSet chores() {
		return new AttributeSet(ODataPath.of("ChoreAttributes"));
	}

	ODataPath path() {
		return path;
	}

	@Override
	public String toString() {
		return path.toString();
	}

}

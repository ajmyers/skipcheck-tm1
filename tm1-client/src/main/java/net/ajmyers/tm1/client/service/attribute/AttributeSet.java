package net.ajmyers.tm1.client.service.attribute;

import java.util.Objects;

import net.ajmyers.tm1.client.odata.ODataPath;

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

	ODataPath path() {
		return path;
	}

	@Override
	public String toString() {
		return path.toString();
	}

}

package io.skipcheck.tm1.client.service.attribute;

import java.util.Objects;

import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.service.cube.ViewScope;
import io.skipcheck.tm1.client.service.dimension.SubsetScope;

/**
 * An object whose attribute values can be translated: it has {@code LocalizedAttributes}.
 */
public final class AttributeOwner {

	private final ODataPath path;

	private AttributeOwner(ODataPath path) {
		this.path = path;
	}

	/**
	 * A dimension.
	 * @param dimension the dimension's name
	 * @return the owner
	 */
	public static AttributeOwner dimension(String dimension) {
		return new AttributeOwner(ODataPath.of("Dimensions").key(Objects.requireNonNull(dimension, "dimension")));
	}

	/**
	 * A hierarchy.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @return the owner
	 */
	public static AttributeOwner hierarchy(String dimension, String hierarchy) {
		return new AttributeOwner(ODataPath.of("Dimensions")
			.key(Objects.requireNonNull(dimension, "dimension"))
			.nav("Hierarchies")
			.key(Objects.requireNonNull(hierarchy, "hierarchy")));
	}

	/**
	 * An element.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param element the element's name
	 * @return the owner
	 */
	public static AttributeOwner element(String dimension, String hierarchy, String element) {
		return new AttributeOwner(ODataPath.of("Dimensions")
			.key(Objects.requireNonNull(dimension, "dimension"))
			.nav("Hierarchies")
			.key(Objects.requireNonNull(hierarchy, "hierarchy"))
			.nav("Elements")
			.key(Objects.requireNonNull(element, "element")));
	}

	/**
	 * A member.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param member the member's name
	 * @return the owner
	 */
	public static AttributeOwner member(String dimension, String hierarchy, String member) {
		return new AttributeOwner(ODataPath.of("Dimensions")
			.key(Objects.requireNonNull(dimension, "dimension"))
			.nav("Hierarchies")
			.key(Objects.requireNonNull(hierarchy, "hierarchy"))
			.nav("Members")
			.key(Objects.requireNonNull(member, "member")));
	}

	/**
	 * A subset.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param scope where the subset is
	 * @param subset the subset's name
	 * @return the owner
	 */
	public static AttributeOwner subset(String dimension, String hierarchy, SubsetScope scope, String subset) {
		return new AttributeOwner(ODataPath.of("Dimensions")
			.key(Objects.requireNonNull(dimension, "dimension"))
			.nav("Hierarchies")
			.key(Objects.requireNonNull(hierarchy, "hierarchy"))
			.nav(scope.navigation())
			.key(Objects.requireNonNull(subset, "subset")));
	}

	/**
	 * A cube.
	 * @param cube the cube's name
	 * @return the owner
	 */
	public static AttributeOwner cube(String cube) {
		return new AttributeOwner(ODataPath.of("Cubes").key(Objects.requireNonNull(cube, "cube")));
	}

	/**
	 * A view.
	 * @param cube the cube's name
	 * @param scope where the view is
	 * @param view the view's name
	 * @return the owner
	 */
	public static AttributeOwner view(String cube, ViewScope scope, String view) {
		return new AttributeOwner(ODataPath.of("Cubes")
			.key(Objects.requireNonNull(cube, "cube"))
			.nav(scope.navigation())
			.key(Objects.requireNonNull(view, "view")));
	}

	/**
	 * A process.
	 * @param process the process's name
	 * @return the owner
	 */
	public static AttributeOwner process(String process) {
		return new AttributeOwner(ODataPath.of("Processes").key(Objects.requireNonNull(process, "process")));
	}

	/**
	 * A chore.
	 * @param chore the chore's name
	 * @return the owner
	 */
	public static AttributeOwner chore(String chore) {
		return new AttributeOwner(ODataPath.of("Chores").key(Objects.requireNonNull(chore, "chore")));
	}

	ODataPath path() {
		return path;
	}

	@Override
	public String toString() {
		return path.toString();
	}

}

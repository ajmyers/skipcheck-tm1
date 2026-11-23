package io.skipcheck.tm1.client.service.cube;

/** Who sees a view, and so which of a cube's collections holds it. */
public enum ViewScope {

	/** Everyone: {@code Cubes('c')/Views}. */
	PUBLIC("Views"),

	/** Only the user who made it: {@code Cubes('c')/PrivateViews}. */
	PRIVATE("PrivateViews");

	private final String navigation;

	ViewScope(String navigation) {
		this.navigation = navigation;
	}

	/**
	 * The cube's navigation property holding views of this scope.
	 * @return the property's name, such as {@code PrivateViews}
	 */
	public String navigation() {
		return navigation;
	}

}

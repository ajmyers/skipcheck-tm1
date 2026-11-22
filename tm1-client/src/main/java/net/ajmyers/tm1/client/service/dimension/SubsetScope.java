package net.ajmyers.tm1.client.service.dimension;

/** Who sees a subset, and so which of a hierarchy's collections holds it. */
public enum SubsetScope {

	/** Everyone: {@code Hierarchies('h')/Subsets}. */
	PUBLIC("Subsets"),

	/** Only the user who made it: {@code Hierarchies('h')/PrivateSubsets}. */
	PRIVATE("PrivateSubsets"),

	/** Only this session, gone when it ends: {@code Hierarchies('h')/SessionSubsets}. */
	SESSION("SessionSubsets");

	private final String navigation;

	SubsetScope(String navigation) {
		this.navigation = navigation;
	}

	/**
	 * The hierarchy's navigation property holding subsets of this scope.
	 * @return the property's name, such as {@code PrivateSubsets}
	 */
	public String navigation() {
		return navigation;
	}

}

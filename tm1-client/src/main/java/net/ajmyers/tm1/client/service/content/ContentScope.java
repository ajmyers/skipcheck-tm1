package net.ajmyers.tm1.client.service.content;

/** Who sees an entry, and so which of a folder's collections holds it. */
public enum ContentScope {

	/** Everyone: {@code …/Contents('id')}. */
	PUBLIC("Contents"),

	/** Only the user who made it: {@code …/PrivateContents('id')}. */
	PRIVATE("PrivateContents");

	private final String navigation;

	ContentScope(String navigation) {
		this.navigation = navigation;
	}

	/**
	 * The folder's navigation property holding entries of this scope.
	 * @return the property's name, such as {@code PrivateContents}
	 */
	public String navigation() {
		return navigation;
	}

}

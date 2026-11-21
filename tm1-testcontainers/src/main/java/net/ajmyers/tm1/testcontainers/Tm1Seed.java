package net.ajmyers.tm1.testcontainers;

/**
 * Objects the image puts into an empty database on first start (its {@code TM1_SEED}
 * setting). The samples come from the TM1 install itself, so they're only available where
 * the install includes them.
 */
public enum Tm1Seed {

	/** No objects: an empty database with the login {@code admin} and no password. */
	EMPTY("empty", "admin", ""),

	/** IBM's SData sample, with the login {@code admin} / {@code apple}. */
	SDATA("sdata", "admin", "apple");

	private final String value;

	private final String username;

	private final String password;

	Tm1Seed(String value, String username, String password) {
		this.value = value;
		this.username = username;
		this.password = password;
	}

	String value() {
		return value;
	}

	String username() {
		return username;
	}

	String password() {
		return password;
	}

}

package io.skipcheck.tm1.client.session;

import java.util.Locale;
import java.util.Objects;

/**
 * TM1 user names, as impersonation needs them (pinned in
 * {@code Tm1ImpersonationBehaviorTest}).
 */
final class UserNames {

	private UserNames() {
	}

	/**
	 * {@code user}, if it can be impersonated: not blank, which TM1 would take as no
	 * impersonation and run as the logged-in user, and only printable ASCII, since TM1
	 * can't match other characters through the header however they're encoded.
	 * @param user the user name
	 * @return the name, unchanged
	 * @throws IllegalArgumentException if it can't be impersonated
	 */
	static String checkImpersonated(String user) {
		Objects.requireNonNull(user, "user");
		if (user.isBlank()) {
			throw new IllegalArgumentException(
					"The user to impersonate is blank, which TM1 would take as no impersonation at all");
		}
		for (int i = 0; i < user.length(); i++) {
			char c = user.charAt(i);
			if (c < 0x20 || c > 0x7e) {
				throw new IllegalArgumentException("TM1 can't impersonate '" + user
						+ "': it doesn't match user names with characters other than printable ASCII through TM1-Impersonate");
			}
		}
		return user;
	}

	/**
	 * The name as TM1 compares it: ignoring case and spaces.
	 * @param user the user name
	 * @return the comparable form
	 */
	static String comparable(String user) {
		return user.replace(" ", "").toLowerCase(Locale.ROOT);
	}

}

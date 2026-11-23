// SPDX-License-Identifier: Apache-2.0

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
	 * impersonation and run as the logged-in user, and without control characters, which
	 * can't go in a header. Any other character is fine: TM1 (11.8) reads the header as
	 * UTF-8, and {@link HeaderText} sends it so.
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
			if (Character.isISOControl(user.charAt(i))) {
				throw new IllegalArgumentException(
						"The user to impersonate has a control character in its name, which can't be sent in a header");
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

// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

/**
 * A request was made on a session after {@link Tm1Session#close()}. A closed session
 * doesn't log in again, so this is a bug in the caller, not a TM1 failure: it isn't a
 * {@link io.skipcheck.tm1.client.error.Tm1Exception}.
 */
public final class Tm1SessionClosedException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	Tm1SessionClosedException(String serviceRoot) {
		super("The TM1 session at " + serviceRoot + " is closed");
	}

}

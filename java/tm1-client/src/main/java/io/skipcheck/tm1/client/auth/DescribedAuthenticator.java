// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.auth;

import java.util.List;

import org.jspecify.annotations.Nullable;

import reactor.core.publisher.Mono;

/**
 * Another authenticator under its own description: the ones that ask Cognos, as
 * {@link Tm1Authenticator}'s factories make them.
 */
final class DescribedAuthenticator implements Tm1Authenticator {

	private final Tm1Authenticator authenticator;

	private final String description;

	/** What it was made from, which is what makes two of them equal. */
	private final List<Object> madeFrom;

	DescribedAuthenticator(Tm1Authenticator authenticator, String description, List<Object> madeFrom) {
		this.authenticator = authenticator;
		this.description = description;
		this.madeFrom = madeFrom;
	}

	@Override
	public Mono<String> authorization() {
		return authenticator.authorization();
	}

	@Override
	public String describe() {
		return description;
	}

	/** The description only: never the credentials. */
	@Override
	public String toString() {
		return "Tm1Authenticator[" + description + "]";
	}

	/** Equal when they were made from the same address, namespace and credentials. */
	@Override
	public boolean equals(@Nullable Object other) {
		return this == other
				|| (other instanceof DescribedAuthenticator described && madeFrom.equals(described.madeFrom));
	}

	@Override
	public int hashCode() {
		return madeFrom.hashCode();
	}

}

// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.auth;

import org.jspecify.annotations.Nullable;

import reactor.core.publisher.Mono;

/**
 * Credentials that don't change: Basic, CAMNamespace and a single CAM passport, as
 * {@link Tm1Authenticator}'s factories make them.
 */
final class FixedAuthenticator implements Tm1Authenticator {

	private final String value;

	private final String description;

	FixedAuthenticator(String value, String description) {
		this.value = value;
		this.description = description;
	}

	@Override
	public Mono<String> authorization() {
		return Mono.just(value);
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

	/** Equal when the credentials are. */
	@Override
	public boolean equals(@Nullable Object other) {
		return this == other || (other instanceof FixedAuthenticator fixed && value.equals(fixed.value));
	}

	@Override
	public int hashCode() {
		return value.hashCode();
	}

}

// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import reactor.core.publisher.Mono;

/**
 * TM1 12 (Planning Analytics Engine) service-to-service application credentials: logs in
 * at {@code /<instance>/auth/v1/session} with HTTP Basic {@code clientId:clientSecret}
 * and a JSON body naming the TM1 user to act as.
 */
final class ApplicationAuthenticator implements Tm1Authenticator {

	private final String clientId;

	private final String clientSecret;

	private final String user;

	private final String headerValue;

	private final String description;

	ApplicationAuthenticator(String clientId, String clientSecret, String user) {
		this.clientId = checkClientId(clientId);
		this.clientSecret = checkNonBlank(clientSecret, "clientSecret");
		this.user = checkUser(user);
		this.headerValue = "Basic " + Base64.getEncoder()
			.encodeToString((this.clientId + ":" + this.clientSecret).getBytes(StandardCharsets.UTF_8));
		this.description = "Application " + this.clientId + " as " + this.user;
	}

	@Override
	public Mono<String> authorization() {
		return Mono.just(headerValue);
	}

	@Override
	public Optional<String> applicationUser() {
		return Optional.of(user);
	}

	@Override
	public String describe() {
		return description;
	}

	/** The description only: never the client secret. */
	@Override
	public String toString() {
		return "Tm1Authenticator[" + description + "]";
	}

	/** Equal when the client ID, client secret and user all match. */
	@Override
	public boolean equals(@Nullable Object other) {
		return this == other || (other instanceof ApplicationAuthenticator that && clientId.equals(that.clientId)
				&& clientSecret.equals(that.clientSecret) && user.equals(that.user));
	}

	@Override
	public int hashCode() {
		return Objects.hash(clientId, clientSecret, user);
	}

	static String checkToken(String token) {
		Objects.requireNonNull(token, "token");
		if (token.isBlank()) {
			throw new IllegalArgumentException("bearer token must not be blank");
		}
		for (int i = 0; i < token.length(); i++) {
			char c = token.charAt(i);
			if (c <= 0x20 || c >= 0x7F) {
				throw new IllegalArgumentException(
						"bearer token must contain only printable ASCII characters without whitespace");
			}
		}
		return token;
	}

	private static String checkClientId(String clientId) {
		checkNonBlank(clientId, "clientId");
		if (clientId.indexOf(':') >= 0) {
			throw new IllegalArgumentException("clientId must not contain ':'");
		}
		if (hasControlChars(clientId)) {
			throw new IllegalArgumentException("clientId must not contain control characters");
		}
		return clientId;
	}

	private static String checkUser(String user) {
		checkNonBlank(user, "user");
		if (hasControlChars(user)) {
			throw new IllegalArgumentException("user must not contain control characters");
		}
		return user;
	}

	private static String checkNonBlank(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}

	private static boolean hasControlChars(String value) {
		for (int i = 0; i < value.length(); i++) {
			if (Character.isISOControl(value.charAt(i))) {
				return true;
			}
		}
		return false;
	}

}

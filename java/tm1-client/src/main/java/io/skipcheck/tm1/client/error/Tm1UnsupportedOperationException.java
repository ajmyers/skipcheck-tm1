package io.skipcheck.tm1.client.error;

import lombok.Getter;

/**
 * The connected server doesn't define what a request needs, so the client didn't send it
 * (plan §9). Raised before any request, from the server's own {@code $metadata}: it says
 * what's missing and, when known, the schema version that added it, next to the schema
 * version the server reports.
 */
public final class Tm1UnsupportedOperationException extends Tm1Exception {

	private static final long serialVersionUID = 1L;

	/**
	 * What's missing, as described in the message, such as {@code Cube.Reserve}.
	 * @return the description
	 */
	@Getter
	private final String missing;

	/**
	 * Creates the exception.
	 * @param message the full message
	 * @param missing what's missing, such as {@code Cube.Reserve}
	 */
	public Tm1UnsupportedOperationException(String message, String missing) {
		super(message, null, null, null);
		this.missing = missing;
	}

	/** False: the server won't gain the operation by trying again. */
	@Override
	public boolean isRetryable() {
		return false;
	}

	/** False: nothing was sent. */
	@Override
	public boolean mayHaveApplied() {
		return false;
	}

}

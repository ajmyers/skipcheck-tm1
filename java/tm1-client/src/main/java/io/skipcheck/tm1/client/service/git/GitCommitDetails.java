package io.skipcheck.tm1.client.service.git;

import java.util.Objects;

/**
 * What a push's commit says, and who it's by.
 *
 * @param message the commit message
 * @param author the author's name
 * @param email the author's email address
 */
public record GitCommitDetails(String message, String author, String email) {

	/** Checks every part is there. */
	public GitCommitDetails {
		Objects.requireNonNull(message, "message");
		Objects.requireNonNull(author, "author");
		Objects.requireNonNull(email, "email");
	}

}

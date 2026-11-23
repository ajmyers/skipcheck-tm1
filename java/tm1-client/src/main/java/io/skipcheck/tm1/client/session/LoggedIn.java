package io.skipcheck.tm1.client.session;

/**
 * A logged-in TM1 session: the value of its {@code TM1SessionId} cookie, and its ID in
 * {@code Sessions}.
 */
record LoggedIn(String cookie, long sessionId) {

	/** Never prints the cookie, which is as good as the credentials. */
	@Override
	public String toString() {
		return "LoggedIn[sessionId=" + sessionId + "]";
	}
}

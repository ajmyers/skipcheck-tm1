package io.skipcheck.tm1.client.error;

/**
 * What a TM1 error means, as worked out by {@link Tm1ErrorClassifier}. More specific than
 * the HTTP status, since TM1 reports many different failures with the same status and
 * code.
 */
public enum Tm1ErrorKind {

	/** The object doesn't exist. TM1 sometimes reports this with 400 rather than 404. */
	NOT_FOUND,
	/** An object with that name already exists. */
	ALREADY_EXISTS,
	/** The object is locked. */
	LOCKED,
	/** The object can't be changed or deleted because something uses it. */
	IN_USE,
	/** The request conflicts with the object's state (409, otherwise unclassified). */
	CONFLICT,
	/** The ETag in {@code If-Match} is stale (412), or one is required (428). */
	PRECONDITION_FAILED,
	/** Not logged in, or the session expired (401). */
	UNAUTHENTICATED,
	/** The credentials were refused, so logging in again won't help. */
	CREDENTIAL_REJECTED,
	/** Not allowed (403). */
	FORBIDDEN,
	/**
	 * The user has no read rights to the object (TM1 code 65), e.g. a new grant not seen
	 * by a held session yet.
	 */
	NO_READ_RIGHTS,
	/** TM1 refused to impersonate the user (for example, an administrator). */
	IMPERSONATION_REFUSED,
	/** The user to impersonate doesn't exist. */
	UNKNOWN_USER,
	/**
	 * A cell write was refused: text in a numeric cell, a consolidated or rule-calculated
	 * cell, or no rights.
	 */
	DATA_SPREAD_FAILED,
	/** An element unique name in the request is invalid (TM1 code 248). */
	INVALID_UNIQUE_NAME,
	/** The operation was cancelled, e.g. a TI process cancelled from TM1. */
	CANCELLED,
	/** TM1 doesn't implement this request (501). */
	NOT_IMPLEMENTED,
	/** Anything else. */
	OTHER

}

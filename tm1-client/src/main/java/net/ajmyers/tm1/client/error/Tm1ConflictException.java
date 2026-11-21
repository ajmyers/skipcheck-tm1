package net.ajmyers.tm1.client.error;

/**
 * The request conflicts with existing state: the object exists, is locked or is in use.
 */
public final class Tm1ConflictException extends Tm1ApiException {

	private static final long serialVersionUID = 1L;

	Tm1ConflictException(int status, Tm1ErrorKind kind, ODataError error, String method, String url) {
		super(status, kind, error, method, url);
	}

}
